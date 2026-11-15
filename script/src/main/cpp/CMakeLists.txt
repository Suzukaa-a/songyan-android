# The script engine is built from the QuickJS-ng pin in QUICKJS-VERSIONS.env and the shared C
# bridge in src/, which is synced from the iOS repository (scripts/ios-sync-manifest). Only the
# four engine sources the bridge needs are compiled; bootstrap.js is embedded as a C string.
# This file is Android-only glue, so it is not part of the sync set.
cmake_minimum_required(VERSION 3.25)
project(SongyanScript C)

if(NOT ANDROID)
  message(FATAL_ERROR "This CMake project only builds the Android script library")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Worktrees share the main checkout's download cache: the pin, not the tree, names the archive.
get_filename_component(SCRIPT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)
find_package(Git REQUIRED)
get_filename_component(SONGYAN_ROOT "${SCRIPT_ROOT}/../../.." ABSOLUTE)
set(SHARED_ROOT "${SONGYAN_ROOT}")
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SONGYAN_ROOT}" rev-parse --path-format=absolute --git-common-dir
  OUTPUT_VARIABLE git_common_dir OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE git_result ERROR_QUIET)
if(git_result EQUAL 0 AND git_common_dir MATCHES "/\.git$")
  get_filename_component(SHARED_ROOT "${git_common_dir}" DIRECTORY)
endif()
set(SONGYAN_SCRIPT_CACHE "${SHARED_ROOT}/.local/script-vendor" CACHE PATH "QuickJS-ng source cache")
file(MAKE_DIRECTORY "${SONGYAN_SCRIPT_CACHE}")

# The pin is the single source of truth for the engine version and its archive digest. A tag
# without a digest, or a download whose digest differs, fails the build instead of linking an
# engine that changed under the same name.
file(STRINGS "${SCRIPT_ROOT}/QUICKJS-VERSIONS.env" pins REGEX "^[A-Z_0-9]+=")
foreach(pin IN LISTS pins)
  if(pin MATCHES "^([A-Z_0-9]+)=(.*)$")
    set("${CMAKE_MATCH_1}" "${CMAKE_MATCH_2}")
  endif()
endforeach()
if(NOT QUICKJS_TAG OR NOT QUICKJS_ARCHIVE_SHA256)
  message(FATAL_ERROR "QUICKJS-VERSIONS.env must set QUICKJS_TAG and QUICKJS_ARCHIVE_SHA256")
endif()
set(quickjs_name "quickjs-ng-${QUICKJS_TAG}")
set(quickjs_archive "${SONGYAN_SCRIPT_CACHE}/${quickjs_name}.tar.gz")
set(quickjs_source "${SONGYAN_SCRIPT_CACHE}/${quickjs_name}")
set(quickjs_url "${QUICKJS_REPOSITORY}/archive/refs/tags/${QUICKJS_TAG}.tar.gz")
# SONGYAN_QUICKJS_MIRROR lets an offline machine serve the same archive from a local mirror; the
# digest check still applies, so a mirror cannot substitute different bytes.
if(SONGYAN_QUICKJS_MIRROR)
  set(quickjs_url "${SONGYAN_QUICKJS_MIRROR}/${QUICKJS_TAG}.tar.gz")
endif()
if(NOT EXISTS "${quickjs_archive}")
  file(LOCK "${SONGYAN_SCRIPT_CACHE}/quickjs-download.lock" GUARD PROCESS)
  if(NOT EXISTS "${quickjs_archive}")
    message(STATUS "Downloading QuickJS-ng ${QUICKJS_TAG} (${quickjs_url})")
    file(DOWNLOAD "${quickjs_url}" "${quickjs_archive}.download"
      EXPECTED_HASH "SHA256=${QUICKJS_ARCHIVE_SHA256}"
      SHOW_PROGRESS TIMEOUT 600)
    file(RENAME "${quickjs_archive}.download" "${quickjs_archive}")
  endif()
  file(LOCK "${SONGYAN_SCRIPT_CACHE}/quickjs-download.lock" RELEASE)
endif()
file(SHA256 "${quickjs_archive}" quickjs_actual)
if(NOT quickjs_actual STREQUAL QUICKJS_ARCHIVE_SHA256)
  message(FATAL_ERROR "cached ${quickjs_archive} has digest ${quickjs_actual}, expected ${QUICKJS_ARCHIVE_SHA256}")
endif()

# Unpack beside the cache, then rename the single top-level directory into place, so an
# interrupted extraction never looks like a usable source tree. The staging directory carries a
# unique suffix: the repository forbids recursive deletes, so a stale staging tree from a
# previous failed run is never removed here - a name collision aborts the configure and the
# leftover directory under .local is cleaned by hand.
# The safety of this extraction rests on the SHA256 pin above: CMake 3.31's ARCHIVE_EXTRACT does
# not reject ../ path traversal, so a tampered archive could otherwise write outside the staging
# directory. The digest check makes an adversarial archive fail before this point.
# Several ABIs configure at once and share this cache, so the unpack is serialised and the
# second configure finds the tree the first one put in place.
if(NOT EXISTS "${quickjs_source}/quickjs.c")
  file(LOCK "${SONGYAN_SCRIPT_CACHE}/quickjs-unpack.lock" GUARD PROCESS)
  if(NOT EXISTS "${quickjs_source}/quickjs.c")
    string(TIMESTAMP unpack_stamp "%Y%m%d-%H%M%S")
    string(RANDOM LENGTH 6 unpack_rand)
    set(staging "${SONGYAN_SCRIPT_CACHE}/.unpack-${quickjs_name}-${unpack_stamp}-${unpack_rand}")
    if(EXISTS "${staging}")
      message(FATAL_ERROR "staging directory ${staging} already exists; remove it under .local by hand and rerun")
    endif()
    file(MAKE_DIRECTORY "${staging}")
    file(ARCHIVE_EXTRACT INPUT "${quickjs_archive}" DESTINATION "${staging}")
    file(GLOB unpacked LIST_DIRECTORIES true "${staging}/*")
    list(LENGTH unpacked unpacked_count)
    if(NOT unpacked_count EQUAL 1)
      message(FATAL_ERROR "the QuickJS-ng archive did not unpack to exactly one directory; staging left at ${staging} for inspection")
    endif()
    file(RENAME "${unpacked}" "${quickjs_source}")
    # Only the (now empty) staging directory itself is removed; its contents moved out with rename.
    file(REMOVE "${staging}")
  endif()
  file(LOCK "${SONGYAN_SCRIPT_CACHE}/quickjs-unpack.lock" RELEASE)
endif()
foreach(required IN ITEMS quickjs.c quickjs.h libregexp.c libunicode.c dtoa.c cutils.h list.h dtoa.h)
  if(NOT EXISTS "${quickjs_source}/${required}")
    message(FATAL_ERROR "${quickjs_source} is missing ${required}; delete the directory and rerun")
  endif()
endforeach()

# bootstrap.js is embedded as a C string literal the same way the iOS Makefile embeds it: printable
# ASCII except quote and backslash verbatim, every other byte as a three-digit octal escape.
# Special characters are produced with string(ASCII) values instead of source-level escapes, so no
# CMake quoting rule can ever alter a generated escape; semicolons in the source stay harmless
# because pieces are concatenated inside quoted variable references.
function(songyan_embed_text input output symbol)
  string(ASCII 34 embed_quote)
  string(ASCII 92 embed_backslash)
  file(READ "${input}" data HEX)
  string(REGEX REPLACE "\n" "" data "${data}")
  string(REGEX MATCHALL ".." bytes "${data}")
  set(literals "")
  set(chunk "")
  set(chunk_len 0)
  foreach(byte IN LISTS bytes)
    math(EXPR value "0x${byte}")
    if(value EQUAL 34)
      set(piece "${embed_backslash}${embed_quote}")
    elseif(value EQUAL 92)
      set(piece "${embed_backslash}${embed_backslash}")
    elseif(value GREATER_EQUAL 32 AND value LESS 127)
      string(ASCII "${value}" piece)
    else()
      math(EXPR o1 "${value} >> 6")
      math(EXPR o2 "(${value} >> 3) & 7")
      math(EXPR o3 "${value} & 7")
      set(piece "${embed_backslash}${o1}${o2}${o3}")
    endif()
    set(chunk "${chunk}${piece}")
    math(EXPR chunk_len "${chunk_len} + 1")
    if(chunk_len GREATER 900)
      set(literals "${literals}${embed_quote}${chunk}${embed_quote}")
      set(chunk "")
      set(chunk_len 0)
    endif()
  endforeach()
  if(NOT chunk STREQUAL "")
    set(literals "${literals}${embed_quote}${chunk}${embed_quote}")
  endif()
  file(WRITE "${output}"
    "/* Generated from bootstrap.js by script/src/main/cpp/CMakeLists.txt; do not edit. */\n")
  file(APPEND "${output}" "static const char ${symbol}[] =\n${literals};\n")
  file(APPEND "${output}" "static const size_t ${symbol}_length = sizeof(${symbol}) - 1;\n")
endfunction()

set(bootstrap_inc "${CMAKE_CURRENT_BINARY_DIR}/songyan_script_bootstrap.inc")
songyan_embed_text("${SCRIPT_ROOT}/src/bootstrap.js" "${bootstrap_inc}" songyan_script_bootstrap)

# The engine: only the four sources the bridge needs, compiled like the iOS Makefile does. It is
# an OBJECT library so the compiled engine can only reach libsongyan-script.so through the
# shared target below.
add_library(quickjs-engine OBJECT
  ${quickjs_source}/quickjs.c
  ${quickjs_source}/libregexp.c
  ${quickjs_source}/libunicode.c
  ${quickjs_source}/dtoa.c
)
target_include_directories(quickjs-engine PUBLIC ${quickjs_source})
target_compile_options(quickjs-engine PRIVATE -O2)
target_compile_definitions(quickjs-engine PRIVATE _GNU_SOURCE)

# The shared C bridge synced from the iOS repository. main.c and run_cli.c are the macOS
# command-line front end; on Android the host is the JNI layer in android/.
add_library(songyan-bridge OBJECT
  src/contract.c
  src/run.c
  src/fetch.c
)
# main.c and run_cli.c are the macOS command-line front end. The Debug library embeds them as an
# in-process entry (renamed from main) so the shared parsing and the canonical JSON emission run
# byte-identically in the device test harness; Release ships only the production entry points,
# so the CLI test surface never reaches a shipped build.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  target_sources(songyan-bridge PRIVATE src/run_cli.c src/main.c)
  target_compile_definitions(songyan-bridge PRIVATE main=songyan_script_cli_main)
endif()
target_include_directories(songyan-bridge PRIVATE
  ${SCRIPT_ROOT}/include
  ${SCRIPT_ROOT}/src
  ${quickjs_source}
  ${CMAKE_CURRENT_BINARY_DIR}
)
target_compile_options(songyan-bridge PRIVATE -O2 -Wall -Wextra)

# Everything is plain C without a C++ runtime, so the STL is unset and only liblog is added.
add_library(songyan-script SHARED android/script_jni.c)
target_include_directories(songyan-script PRIVATE
  ${SCRIPT_ROOT}/include
  ${SCRIPT_ROOT}/src
  ${quickjs_source}
)
# The debug-only CLI harness (Debug build type => SONGYAN_SCRIPT_CLI enabled) is what the
# isolated-process case runner calls; Release builds never register it.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  target_compile_definitions(songyan-script PRIVATE SONGYAN_SCRIPT_CLI=1)
endif()
target_link_libraries(songyan-script PRIVATE quickjs-engine songyan-bridge log)

# Only the JNI entry points are exported; the shared bridge stays callable through JNI only.
target_compile_options(songyan-script PRIVATE -fvisibility=hidden)
set_target_properties(songyan-script PROPERTIES
  LINK_FLAGS "-Wl,--gc-sections -Wl,--exclude-libs,ALL")
target_link_options(songyan-script PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/android/exports.map)


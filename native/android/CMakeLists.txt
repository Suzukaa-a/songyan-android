cmake_minimum_required(VERSION 3.25)
# The engine is built optimized in every Gradle variant. AGP passes -DCMAKE_BUILD_TYPE=Debug
# for the debug variant, which compiles librime and the plugins at -O0: typing latency and
# the benchmark numbers would then describe a build nobody ships. RelWithDebInfo keeps -g
# so release symbols can be uploaded; AGP strips the packaged copy.
set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Engine build type (forced)" FORCE)
project(SongyanRime LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_STL STREQUAL "c++_static")
  message(FATAL_ERROR "Use the Android NDK toolchain with ANDROID_STL=c++_static")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(NATIVE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
get_filename_component(SONGYAN_ROOT "${NATIVE_ROOT}/.." ABSOLUTE)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
find_package(Git REQUIRED)
# Worktrees share the main checkout's download and engine caches: a copied cmake-deps
# keeps CMakeCache paths of the tree it came from, and rebuilding the engine per worktree
# is the cost the cache exists to avoid.
set(SHARED_ROOT "${SONGYAN_ROOT}")
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SONGYAN_ROOT}" rev-parse --path-format=absolute --git-common-dir
  OUTPUT_VARIABLE git_common_dir OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE git_result ERROR_QUIET)
if(git_result EQUAL 0 AND git_common_dir MATCHES "/\\.git$")
  get_filename_component(SHARED_ROOT "${git_common_dir}" DIRECTORY)
endif()
set(SONGYAN_PATH_MAP "-ffile-prefix-map=${SONGYAN_ROOT}=.")
if(NOT SHARED_ROOT STREQUAL SONGYAN_ROOT)
  string(APPEND SONGYAN_PATH_MAP " -ffile-prefix-map=${SHARED_ROOT}=.")
endif()
separate_arguments(PATH_MAP_OPTIONS UNIX_COMMAND "${SONGYAN_PATH_MAP}")
add_compile_options(${PATH_MAP_OPTIONS})
# Only the JNI entry points (JNIEXPORT) and the build marker are exported; everything else
# is hidden so --gc-sections can drop unreferenced librime and plugin code.
add_compile_options(-fvisibility=hidden -ffunction-sections -fdata-sections)
add_compile_options($<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
  set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
  set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
  # The engine build runs from this configure step and inherits the variable; base_dir lets
  # worktrees hit each other's entries for the files compiled here.
  set(ENV{CCACHE_BASEDIR} "${SHARED_ROOT}")
endif()

include(FetchContent)
set(FETCHCONTENT_BASE_DIR "${SHARED_ROOT}/.local/cmake-deps" CACHE PATH "Pinned source cache" FORCE)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
# Gradle can configure multiple ABIs against the shared download cache.
file(MAKE_DIRECTORY "${FETCHCONTENT_BASE_DIR}")
file(LOCK "${FETCHCONTENT_BASE_DIR}/configure.lock" GUARD PROCESS)

foreach(pin_file VERSIONS LUA-VERSIONS GRAMMAR-VERSIONS PREDICT-VERSIONS)
  file(STRINGS "${NATIVE_ROOT}/${pin_file}.env" pins REGEX "^[A-Z_0-9]+=")
  foreach(pin IN LISTS pins)
    if(pin MATCHES "^CMAKE_")
      continue()
    endif()
    if(pin MATCHES "^([A-Z_0-9]+)=(.*)$")
      set("${CMAKE_MATCH_1}" "${CMAKE_MATCH_2}")
    endif()
  endforeach()
endforeach()
function(fetch_git name repository commit)
  if(EXISTS "${FETCHCONTENT_BASE_DIR}/${name}-src/.git")
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${FETCHCONTENT_BASE_DIR}/${name}-src" rev-parse HEAD
      OUTPUT_VARIABLE cached_commit OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
    if(cached_commit STREQUAL commit)
      string(TOUPPER "${name}" upper_name)
      set(FETCHCONTENT_SOURCE_DIR_${upper_name} "${FETCHCONTENT_BASE_DIR}/${name}-src")
    endif()
  endif()
  FetchContent_Declare(${name} GIT_REPOSITORY "${repository}" GIT_TAG "${commit}" GIT_SUBMODULES "" SOURCE_SUBDIR _populate_only)
  FetchContent_MakeAvailable(${name})
  # A checkout left on another commit (offline cache, interrupted update) would silently
  # build the old engine while the pin-derived marker in engine_marker.cc claims the new
  # one - and scripts/check-native-engine.sh trusts that marker. Refuse to configure.
  execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${${name}_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE actual_commit OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
  if(NOT actual_commit STREQUAL commit)
    message(FATAL_ERROR "Pinned source '${name}' is at ${actual_commit}, expected ${commit}. "
      "Delete ${FETCHCONTENT_BASE_DIR}/${name}-src and reconfigure.")
  endif()
  set(${name}_SOURCE_DIR "${${name}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()
fetch_git(librime "${LIBRIME_REPO}" "${LIBRIME_COMMIT}")
fetch_git(glog https://github.com/google/glog.git "${GLOG_COMMIT}")
fetch_git(leveldb https://github.com/google/leveldb.git "${LEVELDB_COMMIT}")
fetch_git(marisa-trie https://github.com/s-yata/marisa-trie.git "${MARISA_COMMIT}")
fetch_git(opencc https://github.com/BYVoid/OpenCC.git "${OPENCC_COMMIT}")
fetch_git(yaml-cpp https://github.com/jbeder/yaml-cpp.git "${YAMLCPP_COMMIT}")
fetch_git(librime-lua https://github.com/hchunhui/librime-lua.git "${RIME_LUA_COMMIT}")
fetch_git(octagram "${OCTAGRAM_REPOSITORY}" "${OCTAGRAM_COMMIT}")
fetch_git(predict "${PREDICT_REPOSITORY}" "${PREDICT_COMMIT}")
# Local archives avoid repeated downloads on this machine; hashes still come from iOS.
foreach(archive boost lua)
  if(archive STREQUAL boost)
    set(archive_name "${BOOST_TARBALL}")
    set(archive_url "https://archives.boost.io/release/${BOOST_VERSION}/source/${BOOST_TARBALL}")
    set(archive_hash "${BOOST_SHA256}")
  else()
    set(archive_name "lua-${LUA_VERSION}.tar.gz")
    set(archive_url "https://www.lua.org/ftp/${archive_name}")
    set(archive_hash "${LUA_SHA256}")
  endif()
  if(EXISTS "${SHARED_ROOT}/.local/downloads/${archive_name}")
    set(archive_url "file://${SHARED_ROOT}/.local/downloads/${archive_name}")
  endif()
  FetchContent_Declare(${archive} URL "${archive_url}" URL_HASH "SHA256=${archive_hash}" SOURCE_SUBDIR _populate_only)
  FetchContent_MakeAvailable(${archive})
endforeach()
file(LOCK "${FETCHCONTENT_BASE_DIR}/configure.lock" RELEASE)

# Engine archives (librime, dependencies, Lua, plugins) are keyed on every input of
# native/android/engine: the pins, the patch and instrumentation, the headers the plugins
# compile against, the engine project itself, and the toolchain. Debug and release share
# an entry because the engine flags do not depend on the Gradle variant. Path-map flags
# are left out on purpose: they only rewrite debug-info paths and differ per worktree.
file(GLOB ENGINE_KEY_FILES LIST_DIRECTORIES false RELATIVE "${NATIVE_ROOT}"
  "${NATIVE_ROOT}/*.env" "${NATIVE_ROOT}/patches/*" "${NATIVE_ROOT}/scripts/*" "${NATIVE_ROOT}/src/*.h"
  "${NATIVE_ROOT}/levers/include/*" "${NATIVE_ROOT}/android/engine/*")
list(SORT ENGINE_KEY_FILES)
list(TRANSFORM ENGINE_KEY_FILES PREPEND "${NATIVE_ROOT}/" OUTPUT_VARIABLE ENGINE_KEY_PATHS)
# Editing any key input re-runs this configure step, which then picks the new entry.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${ENGINE_KEY_PATHS})
file(STRINGS "${ANDROID_NDK}/source.properties" NDK_REVISION REGEX "^Pkg\\.Revision")
set(ENGINE_KEY_TEXT "songyan-engine-1\n${CMAKE_VERSION}\n${NDK_REVISION}\n${CMAKE_CXX_COMPILER_VERSION}\n"
  "${ANDROID_ABI}\n${ANDROID_PLATFORM}\n${ANDROID_STL}\n${CMAKE_C_FLAGS}\n${CMAKE_CXX_FLAGS}\n")
foreach(key_file IN LISTS ENGINE_KEY_FILES)
  file(SHA256 "${NATIVE_ROOT}/${key_file}" key_file_hash)
  string(APPEND ENGINE_KEY_TEXT "${key_file} ${key_file_hash}\n")
endforeach()
string(SHA256 ENGINE_KEY "${ENGINE_KEY_TEXT}")
string(SUBSTRING "${ENGINE_KEY}" 0 20 ENGINE_KEY)
if(DEFINED ENV{SONGYAN_NATIVE_CACHE})
  set(NATIVE_CACHE "$ENV{SONGYAN_NATIVE_CACHE}")
else()
  set(NATIVE_CACHE "${SHARED_ROOT}/.local/native-cache")
endif()
set(ENGINE_NAME "engine-${ANDROID_ABI}-${ENGINE_KEY}")
set(ENGINE_DIR "${NATIVE_CACHE}/${ENGINE_NAME}")
file(MAKE_DIRECTORY "${NATIVE_CACHE}")
# Variants and worktrees configuring the same ABI wait here instead of building it twice.
file(LOCK "${NATIVE_CACHE}/${ENGINE_NAME}.lock" GUARD PROCESS)
if(NOT EXISTS "${ENGINE_DIR}/.songyan-complete")
  message(STATUS "Building engine archives ${ENGINE_NAME}")
  set(engine_work "${NATIVE_CACHE}/${ENGINE_NAME}.partial")
  set(engine_build "${NATIVE_CACHE}/${ENGINE_NAME}.build")
  file(REMOVE_RECURSE "${engine_work}" "${engine_build}" "${ENGINE_DIR}")
  set(engine_launcher "")
  if(CCACHE_PROGRAM)
    set(engine_launcher "-DCMAKE_C_COMPILER_LAUNCHER=${CCACHE_PROGRAM}" "-DCMAKE_CXX_COMPILER_LAUNCHER=${CCACHE_PROGRAM}")
  endif()
  execute_process(COMMAND "${CMAKE_COMMAND}" -S "${CMAKE_CURRENT_SOURCE_DIR}/engine" -B "${engine_build}" -G Ninja
    "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}" "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
    "-DANDROID_ABI=${ANDROID_ABI}" "-DANDROID_PLATFORM=${ANDROID_PLATFORM}" -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=RelWithDebInfo "-DCMAKE_INSTALL_PREFIX=${engine_work}" "-DENGINE_PREFIX=${engine_work}"
    "-DNATIVE_ROOT=${NATIVE_ROOT}" "-DSONGYAN_PATH_MAP=${SONGYAN_PATH_MAP}" ${engine_launcher}
    "-Dlibrime_SOURCE_DIR=${librime_SOURCE_DIR}" "-Dglog_SOURCE_DIR=${glog_SOURCE_DIR}"
    "-Dleveldb_SOURCE_DIR=${leveldb_SOURCE_DIR}" "-Dmarisa-trie_SOURCE_DIR=${marisa-trie_SOURCE_DIR}"
    "-Dopencc_SOURCE_DIR=${opencc_SOURCE_DIR}" "-Dyaml-cpp_SOURCE_DIR=${yaml-cpp_SOURCE_DIR}"
    "-Dlibrime-lua_SOURCE_DIR=${librime-lua_SOURCE_DIR}" "-Doctagram_SOURCE_DIR=${octagram_SOURCE_DIR}"
    "-Dpredict_SOURCE_DIR=${predict_SOURCE_DIR}" "-Dboost_SOURCE_DIR=${boost_SOURCE_DIR}" "-Dlua_SOURCE_DIR=${lua_SOURCE_DIR}"
    COMMAND_ERROR_IS_FATAL ANY)
  execute_process(COMMAND "${CMAKE_COMMAND}" --build "${engine_build}" COMMAND_ERROR_IS_FATAL ANY)
  execute_process(COMMAND "${CMAKE_COMMAND}" --install "${engine_build}" COMMAND_ERROR_IS_FATAL ANY)
  # The marker is written last and the directory renamed into place, so an interrupted
  # build never leaves an entry that a later configure would accept.
  file(WRITE "${engine_work}/.songyan-complete" "${ENGINE_KEY_TEXT}")
  file(RENAME "${engine_work}" "${ENGINE_DIR}")
  file(REMOVE_RECURSE "${engine_build}")
endif()
file(LOCK "${NATIVE_CACHE}/${ENGINE_NAME}.lock" RELEASE)

set(RIME_SOURCE "${ENGINE_DIR}/librime")
foreach(engine_lib songyan_plugins rime opencc marisa yaml-cpp leveldb glog boost_regex songyan_lua)
  add_library(engine::${engine_lib} STATIC IMPORTED)
  set_target_properties(engine::${engine_lib} PROPERTIES IMPORTED_LOCATION "${ENGINE_DIR}/lib/lib${engine_lib}.a")
endforeach()
set(RIME_PUBLIC_HEADERS "${RIME_SOURCE}/src/rime_api.h" "${RIME_SOURCE}/src/rime_api_deprecated.h"
  "${RIME_SOURCE}/src/rime_api_stdbool.h" "${RIME_SOURCE}/src/rime_levers_api.h")
file(COPY ${RIME_PUBLIC_HEADERS} DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/include/RimeKit")
file(READ "${RIME_SOURCE}/CMakeLists.txt" RIME_CMAKE)
string(REGEX MATCH "set[(]rime_version ([0-9.]+)[)]" RIME_VERSION_MATCH "${RIME_CMAKE}")
set(RIME_VERSION "${CMAKE_MATCH_1}")
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/engine_marker.cc"
  CONTENT "extern \"C\" __attribute__((used, visibility(\"default\"))) const char* songyan_librime_marker() { return \"Songyan:librime:${RIME_VERSION}:${LIBRIME_COMMIT}\"; }\n")
file(GLOB JNI_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cc")
file(GLOB BRIDGE_SOURCES "${NATIVE_ROOT}/src/*.cc")
# Bionic names the same nanosecond timestamp field st_mtim.
set_source_files_properties("${NATIVE_ROOT}/src/rime_spelling.cc" PROPERTIES COMPILE_DEFINITIONS st_mtimespec=st_mtim)
add_library(songyan_rime SHARED "${CMAKE_CURRENT_BINARY_DIR}/engine_marker.cc" ${JNI_SOURCES} ${BRIDGE_SOURCES}
  "${NATIVE_ROOT}/src/rime_bridge.c" "${NATIVE_ROOT}/levers/RimeLeversSupport.c")
target_include_directories(songyan_rime PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/include" "${RIME_SOURCE}/src" "${RIME_SOURCE}/include"
  "${ENGINE_DIR}/include" "${boost_SOURCE_DIR}" "${lua_SOURCE_DIR}/src"
  "${NATIVE_ROOT}/src" "${NATIVE_ROOT}/levers/include" "${predict_SOURCE_DIR}/src")
target_compile_definitions(songyan_rime PRIVATE RB_WITH_LUA=1 RB_WITH_GRAMMAR=1 RB_WITH_T9=1 RB_WITH_PREDICT=1
  GLOG_USE_GLOG_EXPORT BOOST_DLL_USE_STD_FS Opencc_BUILT_AS_STATIC YAML_CPP_STATIC_DEFINE)
# The plugins register their Rime modules from static initializers, so their archive is
# linked whole, as their objects were before they moved into the cached engine build.
target_link_libraries(songyan_rime PRIVATE "$<LINK_LIBRARY:WHOLE_ARCHIVE,engine::songyan_plugins>"
  "$<LINK_GROUP:RESCAN,engine::rime,engine::opencc,engine::marisa,engine::yaml-cpp,engine::leveldb,engine::glog,engine::boost_regex,engine::songyan_lua>"
  log android dl m)
target_link_options(songyan_rime PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,defs"
  "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
# Template instantiations from headers (Boost exception types) ignore -fvisibility, so the
# version script is what actually keeps them out of the dynamic symbol table.
set_target_properties(songyan_rime PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

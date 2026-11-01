cmake_minimum_required(VERSION 3.25)
# The engine half of the native build: librime (patched), its dependencies, Lua and the
# pinned plugins, installed as static archives plus the headers the bridge compiles
# against. native/android/CMakeLists.txt runs this project once per cache key and links
# the result, so Gradle variants, ABIs re-runs and worktrees stop recompiling ~1000
# upstream files that only change when a pin, patch or toolchain changes. Everything
# that feeds this project must be part of that key (see songyan_engine_key there).
project(SongyanEngine LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_STL STREQUAL "c++_static")
  message(FATAL_ERROR "Use the Android NDK toolchain with ANDROID_STL=c++_static")
endif()
foreach(required NATIVE_ROOT ENGINE_PREFIX SONGYAN_PATH_MAP librime_SOURCE_DIR glog_SOURCE_DIR leveldb_SOURCE_DIR
    marisa-trie_SOURCE_DIR opencc_SOURCE_DIR yaml-cpp_SOURCE_DIR librime-lua_SOURCE_DIR octagram_SOURCE_DIR
    predict_SOURCE_DIR boost_SOURCE_DIR lua_SOURCE_DIR)
  if(NOT DEFINED ${required})
    message(FATAL_ERROR "${required} must be passed by native/android/CMakeLists.txt")
  endif()
endforeach()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
find_package(Git REQUIRED)
separate_arguments(PATH_MAP_OPTIONS UNIX_COMMAND "${SONGYAN_PATH_MAP}")
add_compile_options(${PATH_MAP_OPTIONS})
# Only the JNI entry points (JNIEXPORT) and the build marker are exported from the final
# library; everything here is hidden so --gc-sections can drop unreferenced code.
set(SONGYAN_SECTION_FLAGS "-fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections")
add_compile_options(-fvisibility=hidden -ffunction-sections -fdata-sections)
add_compile_options($<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)

set(PREFIX "${ENGINE_PREFIX}")
set(DEP_FLAGS "${SONGYAN_PATH_MAP} ${SONGYAN_SECTION_FLAGS}")
set(LAUNCHER_ARGS "")
if(CMAKE_CXX_COMPILER_LAUNCHER)
  set(LAUNCHER_ARGS "-DCMAKE_C_COMPILER_LAUNCHER=${CMAKE_CXX_COMPILER_LAUNCHER}"
    "-DCMAKE_CXX_COMPILER_LAUNCHER=${CMAKE_CXX_COMPILER_LAUNCHER}")
endif()
# Isolated installs let upstream librime use its own dependency discovery unchanged.
function(install_dependency name)
  set(build "${CMAKE_CURRENT_BINARY_DIR}/deps/${name}")
  execute_process(COMMAND "${CMAKE_COMMAND}" -S "${${name}_SOURCE_DIR}" -B "${build}" -G Ninja
    "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}" "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
    "-DANDROID_ABI=${ANDROID_ABI}" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF
    -DBUILD_TESTING=OFF "-DCMAKE_INSTALL_PREFIX=${PREFIX}" "-DCMAKE_PREFIX_PATH=${PREFIX}"
    "-DCMAKE_C_FLAGS=${DEP_FLAGS}" "-DCMAKE_CXX_FLAGS=${DEP_FLAGS} -I${PREFIX}/include" ${LAUNCHER_ARGS}
    ${ARGN} COMMAND_ERROR_IS_FATAL ANY OUTPUT_FILE "${build}-configure.log" ERROR_FILE "${build}-configure-errors.log")
  if(name STREQUAL opencc)
    # Only the library is needed; OpenCC's data target executes cross-built tools.
    execute_process(COMMAND "${CMAKE_COMMAND}" --build "${build}" --target libopencc COMMAND_ERROR_IS_FATAL ANY)
    execute_process(COMMAND "${CMAKE_COMMAND}" -DCMAKE_INSTALL_LOCAL_ONLY=TRUE -P "${build}/src/cmake_install.cmake" COMMAND_ERROR_IS_FATAL ANY)
    file(COPY "${build}/src/Opencc_Export.h" DESTINATION "${PREFIX}/include/opencc")
  else()
    execute_process(COMMAND "${CMAKE_COMMAND}" --build "${build}" --target install COMMAND_ERROR_IS_FATAL ANY)
  endif()
endfunction()
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/deps")
install_dependency(glog -DWITH_GFLAGS=OFF -DWITH_UNWIND=none)
install_dependency(leveldb -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF)
install_dependency(marisa-trie -DENABLE_TOOLS=OFF)
install_dependency(yaml-cpp -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF)
install_dependency(opencc -DUSE_SYSTEM_MARISA=ON "-DLIBMARISA=${PREFIX}/lib/libmarisa.a" -DSHARE_INSTALL_PREFIX=share)

# The patched librime tree is installed with the archives: the bridge compiles against
# librime's internal headers, and they must be the patched ones the archive was built from.
# Patches apply in name order, as the iOS RimeKit build does.
file(GLOB PATCH_FILES "${NATIVE_ROOT}/patches/*.patch")
list(SORT PATCH_FILES)
set(RIME_SOURCE "${PREFIX}/librime")
file(REMOVE_RECURSE "${RIME_SOURCE}")
file(COPY "${librime_SOURCE_DIR}/" DESTINATION "${RIME_SOURCE}" PATTERN .git EXCLUDE)
# Git must treat this copy as standalone rather than discovering an enclosing checkout.
foreach(patch_file IN LISTS PATCH_FILES)
  execute_process(COMMAND "${CMAKE_COMMAND}" -E env "GIT_CEILING_DIRECTORIES=${PREFIX}" "${GIT_EXECUTABLE}" apply "${patch_file}"
    WORKING_DIRECTORY "${RIME_SOURCE}" COMMAND_ERROR_IS_FATAL ANY)
endforeach()
file(READ "${RIME_SOURCE}/src/rime/dict/table.cc" TABLE_SOURCE)
string(FIND "${TABLE_SOURCE}" "re-read the pointers from the new mapping" TABLE_PATCH_INDEX)
if(TABLE_PATCH_INDEX EQUAL -1)
  message(FATAL_ERROR "librime table remap patch did not apply")
endif()
file(READ "${RIME_SOURCE}/src/rime/dict/tsv.cc" TSV_SOURCE)
string(FIND "${TSV_SOURCE}" "strict_formatter_" TSV_PATCH_INDEX)
if(TSV_PATCH_INDEX EQUAL -1)
  message(FATAL_ERROR "librime tsv patch did not apply")
endif()

set(GRAMMAR_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/octagram-instrumented")
file(REMOVE_RECURSE "${GRAMMAR_SOURCE}")
execute_process(COMMAND "${Python3_EXECUTABLE}" "${NATIVE_ROOT}/scripts/instrument-grammar.py" "${octagram_SOURCE_DIR}" "${GRAMMAR_SOURCE}"
  COMMAND_ERROR_IS_FATAL ANY)
file(READ "${GRAMMAR_SOURCE}/src/gram_db.cc" GRAM_DB_SOURCE)
string(FIND "${GRAM_DB_SOURCE}" "rb_grammar_bounded_lookup" GRAMMAR_HOOK_INDEX)
if(GRAMMAR_HOOK_INDEX EQUAL -1)
  message(FATAL_ERROR "octagram instrumentation is incomplete (no bounded lookup hook)")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
foreach(option BUILD_TEST BUILD_SAMPLE BUILD_DATA BUILD_MERGED_PLUGINS ENABLE_EXTERNAL_PLUGINS)
  set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_LOGGING ON CACHE BOOL "" FORCE)
set(Boost_NO_BOOST_CMAKE ON CACHE BOOL "" FORCE)
set(BOOST_ROOT "${boost_SOURCE_DIR}" CACHE PATH "" FORCE)
set(Boost_INCLUDE_DIR "${boost_SOURCE_DIR}" CACHE PATH "" FORCE)
list(PREPEND CMAKE_PREFIX_PATH "${PREFIX}")
list(APPEND CMAKE_FIND_ROOT_PATH "${PREFIX}")
add_subdirectory("${RIME_SOURCE}" librime EXCLUDE_FROM_ALL)
add_library(boost_regex STATIC "${boost_SOURCE_DIR}/libs/regex/src/posix_api.cpp")
target_include_directories(boost_regex PUBLIC "${boost_SOURCE_DIR}")
file(GLOB LUA_SOURCES "${lua_SOURCE_DIR}/src/*.c")
list(FILTER LUA_SOURCES EXCLUDE REGEX "/(lua|luac)\.c$")
add_library(songyan_lua STATIC ${LUA_SOURCES})
target_include_directories(songyan_lua PUBLIC "${lua_SOURCE_DIR}/src")
target_compile_definitions(songyan_lua PRIVATE LUA_COMPAT_5_3)
target_compile_options(songyan_lua PRIVATE -include "${CMAKE_CURRENT_SOURCE_DIR}/lua-config.h")
file(GLOB PLUGIN_SOURCES "${librime-lua_SOURCE_DIR}/src/*.cc" "${librime-lua_SOURCE_DIR}/src/lib/*.cc" "${librime-lua_SOURCE_DIR}/src/lib/*.c" "${GRAMMAR_SOURCE}/src/*.cc")
foreach(name predict_db predict_engine predict_module predict_translator predictor)
  list(APPEND PLUGIN_SOURCES "${predict_SOURCE_DIR}/src/${name}.cc")
endforeach()
# The plugins register their Rime modules from static initializers, so the final library
# links this archive whole; the compile settings match what the bridge itself uses.
add_library(songyan_plugins STATIC ${PLUGIN_SOURCES})
target_include_directories(songyan_plugins PRIVATE "${RIME_SOURCE}/src" "${RIME_SOURCE}/include"
  "${CMAKE_CURRENT_BINARY_DIR}/librime/src" "${PREFIX}/include" "${boost_SOURCE_DIR}"
  "${NATIVE_ROOT}/src" "${NATIVE_ROOT}/levers/include" "${predict_SOURCE_DIR}/src")
target_compile_definitions(songyan_plugins PRIVATE RB_WITH_LUA=1 RB_WITH_GRAMMAR=1 RB_WITH_T9=1 RB_WITH_PREDICT=1
  GLOG_USE_GLOG_EXPORT BOOST_DLL_USE_STD_FS Opencc_BUILT_AS_STATIC YAML_CPP_STATIC_DEFINE)
target_link_libraries(songyan_plugins PRIVATE songyan_lua)

# librime is EXCLUDE_FROM_ALL (only rime-static is wanted, not its tools or install rules),
# so the default target names the archives explicitly.
add_custom_target(songyan_engine ALL)
add_dependencies(songyan_engine rime-static boost_regex songyan_lua songyan_plugins)
install(TARGETS rime-static boost_regex songyan_lua songyan_plugins ARCHIVE DESTINATION lib)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/librime/src/rime/build_config.h" DESTINATION librime/src/rime)

// ---------------------------------------------------------------------------
// jni_guard.h — fail-closed guards for every JNI entry point in the engine.
//
// The engine library is built with C++ exceptions enabled (librime, yaml-cpp and
// libc++ all throw), but a C++ exception may not cross a JNI frame: ART invokes
// the native method from a C++ frame with no handler, so an escaping throw ends
// in std::terminate() and SIGABRT of the (input method) process. Each entry point
// therefore opens with SONGYAN_JNI_TRY and closes with SONGYAN_JNI_CATCH(<neutral
// value>) / SONGYAN_JNI_CATCH_VOID(), which records the reason and returns a
// value the Kotlin caller already treats as "operation failed".
//
// The recorded reason is a fixed 512-byte buffer: it is written from a catch
// handler, so it must never allocate. nativeEngineLastError() reads it back when
// the C bridge itself has no error to report.
// ---------------------------------------------------------------------------
#ifndef SONGYAN_JNI_GUARD_H_
#define SONGYAN_JNI_GUARD_H_

#include <jni.h>

#include <cstddef>
#include <cstring>
#include <exception>
#include <mutex>
#include <new>
#include <string>

namespace songyan_jni {

inline constexpr size_t kErrorCapacity = 512;

inline std::mutex& ErrorMutex() {
  static std::mutex mutex;
  return mutex;
}

inline char* ErrorBuffer() {
  static char buffer[kErrorCapacity] = {};
  return buffer;
}

// Copies at most kErrorCapacity-1 bytes; never allocates.
inline void RecordErrorUnlocked(const char* what) noexcept {
  char* buffer = ErrorBuffer();
  size_t length = 0;
  if (what != nullptr) {
    while (what[length] != '\0' && length + 1 < kErrorCapacity) {
      buffer[length] = what[length];
      ++length;
    }
  }
  buffer[length] = '\0';
}

// Records why a guarded call failed. Called from a catch handler, so every step
// is allocation-free and the (theoretical) mutex failure cannot throw further.
inline void RecordError(const char* what) noexcept {
  try {
    std::lock_guard<std::mutex> guard(ErrorMutex());
    RecordErrorUnlocked(what);
  } catch (...) {
    // Keep the previous message rather than throwing out of a catch handler.
  }
}

inline void ClearError() noexcept {
  try {
    std::lock_guard<std::mutex> guard(ErrorMutex());
    ErrorBuffer()[0] = '\0';
  } catch (...) {
  }
}

// Copy of the recorded reason, empty when the last guarded call did not fail.
// May allocate; call it from inside a guarded body (a bad_alloc there is caught
// and reported like any other failure).
inline std::string CopyError() {
  std::lock_guard<std::mutex> guard(ErrorMutex());
  return std::string(ErrorBuffer());
}

}  // namespace songyan_jni

// Opens the guarded region of a JNI entry point.
#define SONGYAN_JNI_TRY try {
// Closes it, returning default_value (the caller's fail-closed answer) and
// recording the reason when anything throws.
#define SONGYAN_JNI_CATCH(default_value)                                    \
  }                                                                         \
  catch (const std::bad_alloc&) {                                            \
    songyan_jni::RecordError("out of memory in native engine call");         \
    return default_value;                                                    \
  }                                                                          \
  catch (const std::exception& error) {                                      \
    songyan_jni::RecordError(error.what());                                  \
    return default_value;                                                    \
  }                                                                          \
  catch (...) {                                                              \
    songyan_jni::RecordError("unknown C++ exception in native engine call"); \
    return default_value;                                                    \
  }

// Same for entries that return void.
#define SONGYAN_JNI_CATCH_VOID()                                            \
  }                                                                         \
  catch (const std::bad_alloc&) {                                            \
    songyan_jni::RecordError("out of memory in native engine call");         \
    return;                                                                  \
  }                                                                          \
  catch (const std::exception& error) {                                      \
    songyan_jni::RecordError(error.what());                                  \
    return;                                                                  \
  }                                                                          \
  catch (...) {                                                              \
    songyan_jni::RecordError("unknown C++ exception in native engine call"); \
    return;                                                                  \
  }

#endif  // SONGYAN_JNI_GUARD_H_

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "jni_guard.h"
#include "rime_api.h"
#include "rime_bridge.h"
#include "RimeLeversSupport.h"

// Link shims: a statically linked plugin module is dropped unless something
// references its registration entry point. All four are C-linkage functions
// defined in native/src/*.cc, so they must not be declared in C++ linkage.
extern "C" {
#ifdef RB_WITH_LUA
void rb_require_lua(void);
#endif
#ifdef RB_WITH_GRAMMAR
void rb_require_grammar(void);
#endif
#ifdef RB_WITH_PREDICT
void rb_require_predict(void);
#endif
#ifdef RB_WITH_T9
void rb_require_t9(void);
#endif
}

// All rb_* calls must be serialized on one thread; the Kotlin engine owns that
// single-thread dispatcher and is the only caller of this class.
//
// Every exported entry point is wrapped in SONGYAN_JNI_TRY / SONGYAN_JNI_CATCH
// (see jni_guard.h): the engine library is compiled with C++ exceptions enabled,
// and an exception escaping a JNI frame would terminate the whole input method
// process. A caught failure returns the neutral value the Kotlin caller already
// treats as "operation failed", and its reason is exposed through
// nativeEngineLastError().

namespace {

// Cached in JNI_OnLoad: FindClass per call costs a class lookup on the key hot path, and
// a global reference stays valid on every thread regardless of the calling class loader.
jclass g_string_class = nullptr;

constexpr uint32_t kReplacement = 0xFFFD;

// Decodes one code point of strict UTF-8 and advances past it. Overlong forms, surrogate
// code points, values above U+10FFFF and truncated sequences yield U+FFFD; the byte that
// broke a sequence is not consumed so it is re-examined as a new lead byte.
uint32_t DecodeUtf8(const unsigned char** cursor) {
  const unsigned char* p = *cursor;
  const unsigned char lead = *p++;
  uint32_t cp;
  uint32_t minimum;
  int extra;
  if (lead < 0x80) {
    *cursor = p;
    return lead;
  } else if (lead >= 0xC2 && lead <= 0xDF) {
    cp = lead & 0x1F;
    minimum = 0x80;
    extra = 1;
  } else if ((lead & 0xF0) == 0xE0) {
    cp = lead & 0x0F;
    minimum = 0x800;
    extra = 2;
  } else if (lead >= 0xF0 && lead <= 0xF4) {
    cp = lead & 0x07;
    minimum = 0x10000;
    extra = 3;
  } else {
    *cursor = p;
    return kReplacement;
  }
  for (int i = 0; i < extra; ++i) {
    // The terminating NUL is not a continuation byte, so a truncated tail stops here.
    if ((*p & 0xC0) != 0x80) {
      *cursor = p;
      return kReplacement;
    }
    cp = (cp << 6) | (*p & 0x3F);
    ++p;
  }
  *cursor = p;
  if (cp < minimum || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return kReplacement;
  return cp;
}

// Every bridge string is plain UTF-8 but NewStringUTF expects modified UTF-8,
// so decode explicitly instead of letting astral characters desynchronize.
void AppendUtf8(std::vector<jchar>* out, const char* text) {
  const auto* p = reinterpret_cast<const unsigned char*>(text);
  while (*p != 0) {
    uint32_t cp = DecodeUtf8(&p);
    if (cp <= 0xFFFF) {
      out->push_back(static_cast<jchar>(cp));
    } else {
      cp -= 0x10000;
      out->push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
      out->push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
    }
  }
}

// Null for a null source, and also when NewString failed: in that case a Java
// OutOfMemoryError is pending, so callers must check `text != nullptr` and stop making
// JNI calls (other than returning) when the result is null.
jstring NewStringFromUtf8(JNIEnv* env, const char* text) {
  if (text == nullptr) return nullptr;
  std::vector<jchar> units;
  AppendUtf8(&units, text);
  return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

// Standard UTF-8 copy of a Java string; release with delete[].
char* CopyJavaString(JNIEnv* env, jstring value) {
  if (value == nullptr) return nullptr;
  const jsize length = env->GetStringLength(value);
  std::vector<jchar> units(static_cast<size_t>(length));
  if (length > 0) env->GetStringRegion(value, 0, length, units.data());
  std::string utf8;
  utf8.reserve(static_cast<size_t>(length) * 3 + 8);
  for (jsize i = 0; i < length; ++i) {
    uint32_t cp = units[static_cast<size_t>(i)];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length &&
        units[static_cast<size_t>(i) + 1] >= 0xDC00 && units[static_cast<size_t>(i) + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (units[static_cast<size_t>(i) + 1] - 0xDC00);
      ++i;
    }
    if (cp < 0x80) {
      utf8.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
      utf8.push_back(static_cast<char>(0xC0 | (cp >> 6)));
      utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      utf8.push_back(static_cast<char>(0xE0 | (cp >> 12)));
      utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
      utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
      utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
  }
  char* copy = new char[utf8.size() + 1];
  std::memcpy(copy, utf8.data(), utf8.size());
  copy[utf8.size()] = '\0';
  return copy;
}

// Owns the native encoding of one Java string argument for a single call.
class ScopedString {
 public:
  ScopedString(JNIEnv* env, jstring value) : text_(CopyJavaString(env, value)) {}
  ~ScopedString() { delete[] text_; }
  ScopedString(const ScopedString&) = delete;
  ScopedString& operator=(const ScopedString&) = delete;
  const char* c_str() const { return text_; }

 private:
  char* text_;
};

jobjectArray NewStringArray(JNIEnv* env, jsize size) {
  return env->NewObjectArray(size, g_string_class, nullptr);
}

// Copies every entry into a new String[]. Returns null as soon as one allocation fails
// (an exception is then pending and no further JNI call is made).
jobjectArray ToJavaStringArray(JNIEnv* env, const std::vector<std::string>& entries) {
  jobjectArray out = NewStringArray(env, static_cast<jsize>(entries.size()));
  if (out == nullptr) return nullptr;
  for (jsize i = 0; i < static_cast<jsize>(entries.size()); ++i) {
    jstring value = NewStringFromUtf8(env, entries[static_cast<size_t>(i)].c_str());
    if (value == nullptr) return nullptr;
    env->SetObjectArrayElement(out, i, value);
    env->DeleteLocalRef(value);
  }
  return out;
}

// Copies malloc'd bridge text into a Java string and always releases the
// original, including when the Java allocation throws (the throw is rethrown and
// then handled by the entry point's guard).
jstring NewStringOwning(JNIEnv* env, char* text, void (*release)(char*)) {
  if (text == nullptr) return nullptr;
  try {
    jstring out = NewStringFromUtf8(env, text);
    release(text);
    return out;
  } catch (...) {
    release(text);
    throw;
  }
}

// Snapshot crossing, decoded by RimeNative.decodeSnapshot. Primitive fields travel in a
// caller-provided int[] so no number is formatted into a String and parsed back; text
// travels in the returned String[]. Keep both layouts in sync with RimeNative.kt.
//   ints:    0 isComposing, 1 isAsciiMode, 2 isFullShape, 3 isAsciiPunct, 4 preeditCursor,
//            5 preeditSelStart, 6 preeditSelEnd, 7 pageNo, 8 pageSize, 9 isLastPage,
//            10 highlightedIndex, 11 labelCount, 12 candidateCount
//   strings: 0 schemaId, 1 schemaName, 2 input, 3 preedit, 4 commitText, 5 selectKeys,
//            6 highlighted syllables JSON (null unless requested and provable), then the labels,
//            then (text, comment) per candidate.
constexpr jsize kSnapshotInts = 13;
constexpr jsize kSnapshotStringHeader = 7;

// Frees the bridge snapshot on every exit path, including a throwing allocation.
struct SnapshotHolder {
  rb_snapshot* snapshot;
  ~SnapshotHolder() {
    if (snapshot != nullptr) rb_snapshot_free(snapshot);
  }
};

// Allocation failures stop the fill and return null: once a NewString fails, a Java
// OutOfMemoryError is pending, and continuing to call JNI is undefined. The null return
// makes the caller mark the snapshot unreadable (fail closed) and the pending error
// reaches Java when this frame returns.
jobjectArray SnapshotToArray(JNIEnv* env, const rb_snapshot* snapshot, uintptr_t session,
                             bool with_syllables, jintArray ints) {
  const int label_count = snapshot->select_labels ? snapshot->num_select_labels : 0;
  const int candidate_count = snapshot->candidates ? snapshot->num_candidates : 0;
  const jsize size = static_cast<jsize>(kSnapshotStringHeader + label_count + candidate_count * 2);
  jobjectArray out = NewStringArray(env, size);
  if (out == nullptr) return nullptr;

  // A null source means "this field is absent" (candidate comment); a null result for a
  // present source means the allocation failed.
  auto put = [&](jsize index, const char* value) -> bool {
    jstring element = NewStringFromUtf8(env, value);
    if (element == nullptr) return value == nullptr;
    env->SetObjectArrayElement(out, index, element);
    env->DeleteLocalRef(element);
    return true;
  };
  if (!put(0, snapshot->schema_id) || !put(1, snapshot->schema_name) ||
      !put(2, snapshot->input) || !put(3, snapshot->preedit) ||
      !put(4, snapshot->commit_text) || !put(5, snapshot->select_keys)) {
    return nullptr;
  }
  if (with_syllables && snapshot->is_composing != 0) {
    // Read in the same engine-thread hop as the snapshot: a second hop let a key press
    // slip in between and pair the syllables with a different highlighted candidate.
    char* syllables = rb_copy_highlighted_syllables_json(session);
    jstring element = NewStringOwning(env, syllables, [](char* text) { free(text); });
    if (element == nullptr) {
      if (syllables != nullptr) return nullptr;
    } else {
      env->SetObjectArrayElement(out, 6, element);
      env->DeleteLocalRef(element);
    }
  }
  for (int i = 0; i < label_count; ++i) {
    if (!put(static_cast<jsize>(kSnapshotStringHeader + i), snapshot->select_labels[i])) {
      return nullptr;
    }
  }
  const jsize base = static_cast<jsize>(kSnapshotStringHeader + label_count);
  for (int i = 0; i < candidate_count; ++i) {
    const rb_candidate& candidate = snapshot->candidates[i];
    if (!put(static_cast<jsize>(base + i * 2), candidate.text) ||
        !put(static_cast<jsize>(base + i * 2 + 1), candidate.comment)) {
      return nullptr;
    }
  }

  const jint values[kSnapshotInts] = {
      snapshot->is_composing,    snapshot->is_ascii_mode,       snapshot->is_full_shape,
      snapshot->is_ascii_punct,  snapshot->preedit_cursor,      snapshot->preedit_sel_start,
      snapshot->preedit_sel_end, snapshot->page_no,             snapshot->page_size,
      snapshot->is_last_page,    snapshot->highlighted_index,   label_count,
      candidate_count};
  env->SetIntArrayRegion(ints, 0, kSnapshotInts, values);
  if (env->ExceptionCheck()) return nullptr;
  return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
    return JNI_ERR;
  }
  jclass local = env->FindClass("java/lang/String");
  if (local == nullptr) return JNI_ERR;
  g_string_class = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  if (g_string_class == nullptr) return JNI_ERR;
  // Failing to load is better than aborting: a plugin anchor that cannot be
  // reached means this build is unusable, and JNI_ERR surfaces as a clean
  // UnsatisfiedLinkError instead of a process death.
  try {
#ifdef RB_WITH_LUA
    rb_require_lua();
#endif
#ifdef RB_WITH_GRAMMAR
    rb_require_grammar();
#endif
#ifdef RB_WITH_PREDICT
    rb_require_predict();
#endif
#ifdef RB_WITH_T9
    rb_require_t9();
#endif
  } catch (...) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

#define JNI_FN(name) Java_app_songyan_ime_rime_RimeNative_##name
#define ENTRY extern "C" JNIEXPORT
#define ENGINE_PTR(value) reinterpret_cast<rb_engine*>(value)
#define SESSION_ID(value) static_cast<uintptr_t>(value)

// Engine creation runs the whole initial deployment, so it is the call most
// likely to fail under memory pressure; clearing the recorded reason first keeps
// a stale message from a previous engine out of this engine's diagnostics.
ENTRY jlong JNI_FN(nativeEngineCreate)(JNIEnv* env, jclass, jstring shared, jstring user,
                                       jstring app, jstring version) {
  SONGYAN_JNI_TRY
  songyan_jni::ClearError();
  ScopedString shared_data(env, shared);
  ScopedString user_data(env, user);
  ScopedString app_name(env, app);
  ScopedString app_version(env, version);
  return reinterpret_cast<jlong>(rb_engine_create(shared_data.c_str(), user_data.c_str(),
                                                  app_name.c_str(), app_version.c_str()));
  SONGYAN_JNI_CATCH(static_cast<jlong>(0))
}

ENTRY jlong JNI_FN(nativeEngineCreatePrebuilt)(JNIEnv* env, jclass, jstring shared, jstring user,
                                               jstring app, jstring version, jstring prebuilt) {
  SONGYAN_JNI_TRY
  songyan_jni::ClearError();
  ScopedString shared_data(env, shared);
  ScopedString user_data(env, user);
  ScopedString app_name(env, app);
  ScopedString app_version(env, version);
  ScopedString prebuilt_dir(env, prebuilt);
  return reinterpret_cast<jlong>(rb_engine_create_prebuilt(
      shared_data.c_str(), user_data.c_str(), app_name.c_str(), app_version.c_str(),
      prebuilt_dir.c_str()));
  SONGYAN_JNI_CATCH(static_cast<jlong>(0))
}

ENTRY void JNI_FN(nativeEngineDestroy)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  rb_engine_destroy(ENGINE_PTR(engine));
  SONGYAN_JNI_CATCH_VOID()
}

ENTRY jboolean JNI_FN(nativeEngineReady)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  return rb_engine_ready(ENGINE_PTR(engine)) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

// A reason recorded by a guarded entry wins over the C bridge's own text: the
// bridge only knows about failures it returned, while the guard also covers a
// failed allocation or a throw inside the bridge itself.
ENTRY jstring JNI_FN(nativeEngineLastError)(JNIEnv* env, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  const std::string recorded = songyan_jni::CopyError();
  if (!recorded.empty()) return NewStringFromUtf8(env, recorded.c_str());
  return NewStringFromUtf8(env, rb_engine_last_error(ENGINE_PTR(engine)));
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jstring JNI_FN(nativeEngineVersion)(JNIEnv* env, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  return NewStringFromUtf8(env, rb_engine_version(ENGINE_PTR(engine)));
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jboolean JNI_FN(nativeComponentAvailable)(JNIEnv* env, jclass, jstring name) {
  SONGYAN_JNI_TRY
  ScopedString component(env, name);
  return rb_component_available(component.c_str()) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jint JNI_FN(nativeLastDeployState)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  return rb_last_deploy_state(ENGINE_PTR(engine));
  SONGYAN_JNI_CATCH(static_cast<jint>(0))  // RB_DEPLOY_NONE
}

ENTRY jlong JNI_FN(nativeSessionCreate)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  return static_cast<jlong>(rb_session_create(ENGINE_PTR(engine)));
  SONGYAN_JNI_CATCH(static_cast<jlong>(0))
}

ENTRY void JNI_FN(nativeSessionDestroy)(JNIEnv*, jclass, jlong engine, jlong session) {
  SONGYAN_JNI_TRY
  rb_session_destroy(ENGINE_PTR(engine), SESSION_ID(session));
  SONGYAN_JNI_CATCH_VOID()
}

ENTRY jboolean JNI_FN(nativeProcessKey)(JNIEnv*, jclass, jlong engine, jlong session, jint keycode,
                                        jint mask) {
  SONGYAN_JNI_TRY
  return rb_process_key(ENGINE_PTR(engine), SESSION_ID(session), keycode, mask) != 0 ? JNI_TRUE
                                                                                     : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeCommitComposition)(JNIEnv*, jclass, jlong engine, jlong session) {
  SONGYAN_JNI_TRY
  return rb_commit_composition(ENGINE_PTR(engine), SESSION_ID(session)) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeSelectCandidateOnPage)(JNIEnv*, jclass, jlong engine, jlong session,
                                                   jint index) {
  SONGYAN_JNI_TRY
  return rb_select_candidate_on_current_page(ENGINE_PTR(engine), SESSION_ID(session),
                                             static_cast<size_t>(index)) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

// Marks the candidate at the absolute menu index as deleted in the user dictionary (librime's own
// "forget this word"); a word that only exists in the read-only dictionary is untouched. The
// caller compares what is listed afterwards to learn whether anything actually went away.
ENTRY jboolean JNI_FN(nativeDeleteCandidate)(JNIEnv*, jclass, jlong engine, jlong session,
                                             jint index) {
  SONGYAN_JNI_TRY
  if (ENGINE_PTR(engine) == nullptr || index < 0) return JNI_FALSE;
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->delete_candidate == nullptr) return JNI_FALSE;
  return api->delete_candidate(SESSION_ID(session), static_cast<size_t>(index)) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

// Read-only run of the current segment's candidates by absolute index, as flat (text, comment)
// pairs. It never moves the page, the highlight or the selection, so browsing can run while the
// composition is on screen. An empty array is the real end of the list (or no menu); null means
// the engine could not answer.
ENTRY jobjectArray JNI_FN(nativeReadCandidates)(JNIEnv* env, jclass, jlong session, jint from_index,
                                                jint count) {
  SONGYAN_JNI_TRY
  if (SESSION_ID(session) == 0 || from_index < 0 || count <= 0 || count > 1024) return nullptr;
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->candidate_list_from_index == nullptr) return nullptr;
  std::vector<std::string> pairs;
  RimeCandidateListIterator iterator;
  if (api->candidate_list_from_index(SESSION_ID(session), &iterator, from_index) != 0) {
    for (jint read = 0; read < count && api->candidate_list_next(&iterator) != 0; ++read) {
      pairs.emplace_back(iterator.candidate.text != nullptr ? iterator.candidate.text : "");
      pairs.emplace_back(iterator.candidate.comment != nullptr ? iterator.candidate.comment : "");
    }
    api->candidate_list_end(&iterator);
  }
  return ToJavaStringArray(env, pairs);
  SONGYAN_JNI_CATCH(nullptr)
}

// Replaces the session's raw input without any key processing, so a prefix can be looked up
// against a schema's dictionary (word completion). Only used on a read-only lookup session.
ENTRY jboolean JNI_FN(nativeSetInput)(JNIEnv* env, jclass, jlong engine, jlong session,
                                      jstring input) {
  SONGYAN_JNI_TRY
  if (ENGINE_PTR(engine) == nullptr || SESSION_ID(session) == 0) return JNI_FALSE;
  ScopedString text(env, input);
  if (text.c_str() == nullptr) return JNI_FALSE;
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->set_input == nullptr) return JNI_FALSE;
  return api->set_input(SESSION_ID(session), text.c_str()) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeSelectCandidate)(JNIEnv*, jclass, jlong engine, jlong session,
                                             jint index) {
  SONGYAN_JNI_TRY
  return rb_select_candidate(ENGINE_PTR(engine), SESSION_ID(session),
                             static_cast<size_t>(index)) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeChangePage)(JNIEnv*, jclass, jlong engine, jlong session,
                                        jboolean backward) {
  SONGYAN_JNI_TRY
  return rb_change_page(ENGINE_PTR(engine), SESSION_ID(session), backward == JNI_TRUE ? 1 : 0) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeClearComposition)(JNIEnv*, jclass, jlong engine, jlong session) {
  SONGYAN_JNI_TRY
  return rb_clear_composition(ENGINE_PTR(engine), SESSION_ID(session)) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeSimulateKeySequence)(JNIEnv* env, jclass, jlong engine, jlong session,
                                                 jstring sequence) {
  SONGYAN_JNI_TRY
  ScopedString keys(env, sequence);
  return rb_simulate_key_sequence(ENGINE_PTR(engine), SESSION_ID(session), keys.c_str()) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeSetOption)(JNIEnv* env, jclass, jlong engine, jlong session,
                                       jstring option, jboolean value) {
  SONGYAN_JNI_TRY
  ScopedString name(env, option);
  return rb_set_option(ENGINE_PTR(engine), SESSION_ID(session), name.c_str(),
                       value == JNI_TRUE ? 1 : 0) != 0
             ? JNI_TRUE
             : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeGetOption)(JNIEnv* env, jclass, jlong engine, jlong session,
                                       jstring option) {
  SONGYAN_JNI_TRY
  ScopedString name(env, option);
  return rb_get_option(ENGINE_PTR(engine), SESSION_ID(session), name.c_str()) != 0 ? JNI_TRUE
                                                                                   : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeSelectSchema)(JNIEnv* env, jclass, jlong engine, jlong session,
                                          jstring schema_id) {
  SONGYAN_JNI_TRY
  ScopedString schema(env, schema_id);
  return rb_select_schema(ENGINE_PTR(engine), SESSION_ID(session), schema.c_str()) != 0 ? JNI_TRUE
                                                                                        : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jstring JNI_FN(nativeCurrentSchema)(JNIEnv* env, jclass, jlong engine, jlong session) {
  SONGYAN_JNI_TRY
  char buffer[256];
  buffer[0] = '\0';
  if (rb_get_current_schema(ENGINE_PTR(engine), SESSION_ID(session), buffer, sizeof(buffer)) == 0) {
    return nullptr;
  }
  return NewStringFromUtf8(env, buffer);
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jobjectArray JNI_FN(nativeSnapshot)(JNIEnv* env, jclass, jlong engine, jlong session,
                                          jintArray ints, jboolean with_syllables) {
  SONGYAN_JNI_TRY
  if (ints == nullptr || env->GetArrayLength(ints) < kSnapshotInts) return nullptr;
  SnapshotHolder holder{rb_snapshot_get(ENGINE_PTR(engine), SESSION_ID(session))};
  if (holder.snapshot == nullptr) return nullptr;
  return SnapshotToArray(env, holder.snapshot, SESSION_ID(session), with_syllables == JNI_TRUE,
                         ints);
  SONGYAN_JNI_CATCH(nullptr)
}

// Test hook for the strict UTF-8 decoder (overlong forms, surrogates, > U+10FFFF): the
// engine never emits such bytes itself, so it is the only way to exercise those paths.
ENTRY jstring JNI_FN(nativeUtf8Probe)(JNIEnv* env, jclass, jbyteArray bytes) {
  SONGYAN_JNI_TRY
  if (bytes == nullptr) return nullptr;
  const jsize length = env->GetArrayLength(bytes);
  std::vector<char> copy(static_cast<size_t>(length) + 1, '\0');
  if (length > 0) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(copy.data()));
  if (env->ExceptionCheck()) return nullptr;
  return NewStringFromUtf8(env, copy.data());
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jboolean JNI_FN(nativeSyncUserData)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  return rb_sync_user_data(ENGINE_PTR(engine)) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeStartMaintenance)(JNIEnv*, jclass, jlong engine, jboolean full) {
  SONGYAN_JNI_TRY
  return rb_start_maintenance(ENGINE_PTR(engine), full == JNI_TRUE ? 1 : 0) != 0 ? JNI_TRUE
                                                                                 : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY void JNI_FN(nativeJoinMaintenance)(JNIEnv*, jclass, jlong engine) {
  SONGYAN_JNI_TRY
  rb_join_maintenance(ENGINE_PTR(engine));
  SONGYAN_JNI_CATCH_VOID()
}

ENTRY void JNI_FN(nativeSpellingReset)(JNIEnv*, jclass) {
  SONGYAN_JNI_TRY
  rb_spelling_reset();
  SONGYAN_JNI_CATCH_VOID()
}

ENTRY jlong JNI_FN(nativeSpellingDecodeCount)(JNIEnv*, jclass) {
  SONGYAN_JNI_TRY
  return static_cast<jlong>(rb_spelling_decode_count());
  SONGYAN_JNI_CATCH(static_cast<jlong>(0))
}


// Bridge *_json helpers return malloc'd text owned by the caller; each is copied
// once into a Java string and released before returning, on the success path and
// on the throwing path alike.
#define JSON_SESSION_FN(name, bridge_call, release_call)                        \
  ENTRY jstring JNI_FN(name)(JNIEnv* env, jclass, jlong session) {              \
    SONGYAN_JNI_TRY                                                             \
    return NewStringOwning(env, bridge_call(SESSION_ID(session)),               \
                           [](char* text) { release_call(text); });             \
    SONGYAN_JNI_CATCH(nullptr)                                                  \
  }

JSON_SESSION_FN(nativeHighlightedSpelling, rb_copy_highlighted_spelling, free)
JSON_SESSION_FN(nativeHighlightedSyllablesJson, rb_copy_highlighted_syllables_json, free)
JSON_SESSION_FN(nativeDisplayContextJson, rb_copy_display_context_json,
                rb_copy_display_context_free)
JSON_SESSION_FN(nativeRewriteVerificationJson, rb_copy_rewrite_verification_json, free)
JSON_SESSION_FN(nativeContextMetadataJson, rb_copy_context_metadata_json, free)
JSON_SESSION_FN(nativeSyllableDisplayMapJson, rb_syllable_display_map_json,
                rb_syllable_display_map_free)

ENTRY jstring JNI_FN(nativeCandidateSyllablesJson)(JNIEnv* env, jclass, jlong session, jint index) {
  SONGYAN_JNI_TRY
  return NewStringOwning(env, rb_copy_candidate_syllables_json(SESSION_ID(session), index),
                         [](char* text) { free(text); });
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jstring JNI_FN(nativeAmbiguitySpellingsJson)(JNIEnv* env, jclass, jlong session,
                                                   jint max_items) {
  SONGYAN_JNI_TRY
  return NewStringOwning(env, rb_copy_ambiguity_spellings_json(SESSION_ID(session), max_items),
                         [](char* text) { free(text); });
  SONGYAN_JNI_CATCH(nullptr)
}

// A throw here may land before, inside or after the rewrite/rollback, so the
// honest answer is the enum's "rollback not verified" value: the caller must not
// assume the input is either the old or the new one (never FAILED, which claims
// the original input was restored).
ENTRY jint JNI_FN(nativeApplyAmbiguitySpelling)(JNIEnv* env, jclass, jlong session,
                                                jstring expected_input, jint segment_start,
                                                jint segment_end, jstring spelling) {
  SONGYAN_JNI_TRY
  ScopedString input(env, expected_input);
  ScopedString token(env, spelling);
  return rb_apply_ambiguity_spelling(SESSION_ID(session), input.c_str(), segment_start, segment_end,
                                     token.c_str());
  SONGYAN_JNI_CATCH(RB_AMBIG_FAILED_ROLLBACK_UNVERIFIED)
}

ENTRY jint JNI_FN(nativeRestoreAmbiguitySpelling)(JNIEnv* env, jclass, jlong session,
                                                  jstring expected_input, jint segment_start,
                                                  jint segment_end, jstring expected_spelling,
                                                  jstring reduced_code) {
  SONGYAN_JNI_TRY
  ScopedString input(env, expected_input);
  ScopedString expected(env, expected_spelling);
  ScopedString reduced(env, reduced_code);
  return rb_restore_ambiguity_spelling(SESSION_ID(session), input.c_str(), segment_start,
                                       segment_end, expected.c_str(), reduced.c_str());
  SONGYAN_JNI_CATCH(RB_AMBIG_FAILED_ROLLBACK_UNVERIFIED)
}

ENTRY void JNI_FN(nativeGrammarDiagnosticsReset)(JNIEnv*, jclass, jboolean enabled) {
  SONGYAN_JNI_TRY
  rb_grammar_diagnostics_reset(enabled == JNI_TRUE ? 1 : 0);
  SONGYAN_JNI_CATCH_VOID()
}

ENTRY jstring JNI_FN(nativeGrammarDiagnosticsJson)(JNIEnv* env, jclass) {
  SONGYAN_JNI_TRY
  return NewStringOwning(env, rb_grammar_diagnostics_json(), rb_grammar_diagnostics_free);
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jboolean JNI_FN(nativeGrammarSessionLoaded)(JNIEnv* env, jclass, jlong session,
                                                  jstring model_path) {
  SONGYAN_JNI_TRY
  ScopedString path(env, model_path);
  return rb_grammar_session_loaded(SESSION_ID(session), path.c_str()) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

// Engine config reads used for schema discovery and capability checks. The result
// is a flat list of (key, value) pairs IN REQUEST ORDER, but ONLY for keys that
// exist: a missing key contributes no entry. Callers must therefore decode by the
// returned key, never by the request position (RimeNative.decodeSchemaValues).
ENTRY jobjectArray JNI_FN(nativeSchemaValues)(JNIEnv* env, jclass, jstring schema_id,
                                              jobjectArray keys) {
  SONGYAN_JNI_TRY
  ScopedString schema(env, schema_id);
  if (schema.c_str() == nullptr || keys == nullptr) return nullptr;
  const jsize key_count = env->GetArrayLength(keys);
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->schema_open == nullptr) return nullptr;
  RimeConfig config;
  if (api->schema_open(schema.c_str(), &config) == 0) return nullptr;
  std::vector<std::string> entries;
  for (jsize i = 0; i < key_count; ++i) {
    auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
    // A null element must never reach config_get_cstring: std::string(nullptr)
    // throws inside librime. It simply contributes no entry, like a missing key.
    if (key == nullptr) continue;
    ScopedString name(env, key);
    env->DeleteLocalRef(key);
    if (name.c_str() == nullptr) continue;
    const char* value = api->config_get_cstring(&config, name.c_str());
    if (value == nullptr) continue;
    entries.emplace_back(name.c_str());
    entries.emplace_back(value);
  }
  api->config_close(&config);
  return ToJavaStringArray(env, entries);
  SONGYAN_JNI_CATCH(nullptr)
}

// A config scalar list; null when the schema or the key is not a list.
ENTRY jobjectArray JNI_FN(nativeSchemaStringList)(JNIEnv* env, jclass, jstring schema_id,
                                                  jstring key) {
  SONGYAN_JNI_TRY
  ScopedString schema(env, schema_id);
  ScopedString path(env, key);
  if (schema.c_str() == nullptr || path.c_str() == nullptr) return nullptr;
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->schema_open == nullptr) return nullptr;
  RimeConfig config;
  if (api->schema_open(schema.c_str(), &config) == 0) return nullptr;
  std::vector<std::string> values;
  RimeConfigIterator iterator;
  if (api->config_begin_list(&iterator, &config, path.c_str()) != 0) {
    while (api->config_next(&iterator) != 0) {
      const char* value = api->config_get_cstring(&config, iterator.path);
      if (value != nullptr) values.emplace_back(value);
    }
    api->config_end(&iterator);
  }
  api->config_close(&config);
  return ToJavaStringArray(env, values);
  SONGYAN_JNI_CATCH(nullptr)
}

// Enabled schema list as flat (id, name) pairs, copied before freeing librime's copy.
ENTRY jobjectArray JNI_FN(nativeSchemaList)(JNIEnv* env, jclass) {
  SONGYAN_JNI_TRY
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->get_schema_list == nullptr) return nullptr;
  RimeSchemaList list;
  if (api->get_schema_list(&list) == 0) return nullptr;
  std::vector<std::string> entries;
  if (list.list != nullptr) {
    for (size_t i = 0; i < list.size; ++i) {
      entries.emplace_back(list.list[i].schema_id != nullptr ? list.list[i].schema_id : "");
      entries.emplace_back(list.list[i].name != nullptr ? list.list[i].name : "");
    }
  }
  api->free_schema_list(&list);
  return ToJavaStringArray(env, entries);
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jboolean JNI_FN(nativeLeversAvailable)(JNIEnv*, jclass) {
  SONGYAN_JNI_TRY
  return rls_available() != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeLeversPrepareInstallation)(JNIEnv*, jclass) {
  SONGYAN_JNI_TRY
  return rls_prepare_installation() != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jobjectArray JNI_FN(nativeUserDictList)(JNIEnv* env, jclass) {
  SONGYAN_JNI_TRY
  void* list = nullptr;
  if (rls_user_dict_list_create(&list) == 0) return nullptr;
  std::vector<std::string> names;
  for (const char* name = rls_user_dict_list_next(list); name != nullptr;
       name = rls_user_dict_list_next(list)) {
    names.emplace_back(name);
  }
  rls_user_dict_list_destroy(list);
  return ToJavaStringArray(env, names);
  SONGYAN_JNI_CATCH(nullptr)
}

ENTRY jboolean JNI_FN(nativeBackupUserDict)(JNIEnv* env, jclass, jstring name) {
  SONGYAN_JNI_TRY
  ScopedString dict(env, name);
  return rls_backup_user_dict(dict.c_str()) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

ENTRY jboolean JNI_FN(nativeRestoreUserDict)(JNIEnv* env, jclass, jstring file) {
  SONGYAN_JNI_TRY
  ScopedString path(env, file);
  return rls_restore_user_dict(path.c_str()) != 0 ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

// The directory levers backups are written to (sync_dir/user_id); valid after
// nativeLeversPrepareInstallation, which settles the installation id.
ENTRY jstring JNI_FN(nativeUserDataSyncDir)(JNIEnv* env, jclass) {
  SONGYAN_JNI_TRY
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->get_user_data_sync_dir == nullptr) return nullptr;
  std::vector<char> buffer(4096, '\0');
  api->get_user_data_sync_dir(buffer.data(), buffer.size() - 1);
  if (buffer[0] != '/') return nullptr;
  return NewStringFromUtf8(env, buffer.data());
  SONGYAN_JNI_CATCH(nullptr)
}

// Compiles one schema file (and its dictionary and prism) into the build
// directory without touching schema_list; the deployment process uses it for
// generated keyboard companions. The caller must hold no session.
ENTRY jboolean JNI_FN(nativeDeploySchema)(JNIEnv* env, jclass, jstring file) {
  SONGYAN_JNI_TRY
  RimeApi* api = rime_get_api();
  if (api == nullptr || api->deploy_schema == nullptr) return JNI_FALSE;
  ScopedString path(env, file);
  return api->deploy_schema(path.c_str()) ? JNI_TRUE : JNI_FALSE;
  SONGYAN_JNI_CATCH(JNI_FALSE)
}

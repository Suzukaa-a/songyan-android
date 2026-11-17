/* Android host for the shared script bridge: the process guard and the JNI entry point the
 * isolated service calls. The contract itself lives in the synced C sources (../src); this file
 * is Android-only glue.
 *
 * Rule 4 of the script foundation: the engine loads only in an isolated process. The guard runs
 * in JNI_OnLoad, before any engine state exists, so a non-isolated process cannot load the
 * library at all: System.loadLibrary throws and no JS engine is reachable. */
#include <jni.h>

#include <dlfcn.h>

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "songyan_script.h"
#include "songyan_script_run.h"

/* One live production run per library load; the Kotlin host serializes runs. */
static songyan_script_run *active_run;
static songyan_script_header active_header;
static char *active_logs[512];
static size_t active_log_lengths[512];
static size_t active_log_count;
static JavaVM *script_vm;
static int script_process_allowed;
static int guard_ok(JNIEnv *env);

#define SCRIPT_LOG_TAG "SongyanScript"
#define SCRIPT_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SCRIPT_LOG_TAG, __VA_ARGS__)

/* ---- Production run path (Debug and Release builds) ------------------------------
 *
 * Drives the shared run state machine directly. Address authorization and fetch travel through
 * the Java host (Binder to the proxy process). The transport call is synchronous on the engine
 * thread - the run's injected duration limit still bounds it - and the reply crosses through
 * setFetchReply. The CLI entry below stays for the shared case harness only. */

typedef struct {
    JavaVM *vm;
    jobject host; /* global ref to the Kotlin ScriptRunHost instance */
} script_run_context;

static script_run_context active_context;

static JNIEnv *run_env(script_run_context *context) {
    JNIEnv *env = NULL;
    if ((*context->vm)->GetEnv(context->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return NULL;
    }
    return env;
}

static songyan_script_address_decision run_authorize(void *userdata, const char *url,
                                                      songyan_script_fetch_phase phase) {
    script_run_context *context = userdata;
    JNIEnv *env = run_env(context);
    if (env == NULL) {
        return SONGYAN_SCRIPT_ADDRESS_DENIED;
    }
    jclass klass = (*env)->GetObjectClass(env, context->host);
    jmethodID method = (*env)->GetMethodID(env, klass, "authorize", "(Ljava/lang/String;I)Z");
    if (method == NULL) {
        (*env)->DeleteLocalRef(env, klass);
        return SONGYAN_SCRIPT_ADDRESS_DENIED;
    }
    jstring url_string = (*env)->NewStringUTF(env, url);
    jboolean allowed = url_string == NULL
                          ? JNI_FALSE
                          : (*env)->CallBooleanMethod(env, context->host, method, url_string,
                                                      (jint)phase);
    if (url_string != NULL) {
        (*env)->DeleteLocalRef(env, url_string);
    }
    (*env)->DeleteLocalRef(env, klass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return SONGYAN_SCRIPT_ADDRESS_DENIED;
    }
    return allowed == JNI_TRUE ? SONGYAN_SCRIPT_ADDRESS_ALLOWED : SONGYAN_SCRIPT_ADDRESS_DENIED;
}

/* The Java host fills this during run_fetch's synchronous call. */
typedef struct {
    int accepted; /* 0 rejected, 1 accepted */
    unsigned status;
    unsigned char *body;
    size_t body_length;
    char *content_type;
    char *final_url;
    char *headers_json;
    char *error;
} script_fetch_reply;

static script_fetch_reply pending_reply;

JNIEXPORT void JNICALL
Java_app_songyan_ime_script_ScriptNative_setFetchReply(
    JNIEnv *env, jclass type, jboolean accepted, jint status, jbyteArray body,
    jstring content_type, jstring final_url, jstring headers_json, jstring error) {
    (void)type;
    free(pending_reply.body);
    free(pending_reply.content_type);
    free(pending_reply.final_url);
    free(pending_reply.headers_json);
    free(pending_reply.error);
    memset(&pending_reply, 0, sizeof(pending_reply));
    pending_reply.accepted = accepted == JNI_TRUE ? 1 : 0;
    pending_reply.status = (unsigned)status;
    if (body != NULL) {
        pending_reply.body_length = (size_t)(*env)->GetArrayLength(env, body);
        if (pending_reply.body_length > 0) {
            pending_reply.body = malloc(pending_reply.body_length);
            (*env)->GetByteArrayRegion(env, body, 0, (jsize)pending_reply.body_length,
                                       (jbyte *)pending_reply.body);
        }
    }
    if (content_type != NULL) {
        const char *text = (*env)->GetStringUTFChars(env, content_type, NULL);
        pending_reply.content_type = strdup(text);
        (*env)->ReleaseStringUTFChars(env, content_type, text);
    }
    if (final_url != NULL) {
        const char *text = (*env)->GetStringUTFChars(env, final_url, NULL);
        pending_reply.final_url = strdup(text);
        (*env)->ReleaseStringUTFChars(env, final_url, text);
    }
    if (headers_json != NULL) {
        const char *text = (*env)->GetStringUTFChars(env, headers_json, NULL);
        pending_reply.headers_json = strdup(text);
        (*env)->ReleaseStringUTFChars(env, headers_json, text);
    }
    if (error != NULL) {
        const char *text = (*env)->GetStringUTFChars(env, error, NULL);
        pending_reply.error = strdup(text);
        (*env)->ReleaseStringUTFChars(env, error, text);
    }
}

static songyan_script_fetch_action run_fetch(void *userdata, songyan_script_run *run,
                                              const songyan_script_fetch_request *request,
                                              songyan_script_fetch_response *response,
                                              const char **error_message) {
    script_run_context *context = userdata;
    JNIEnv *env = run_env(context);
    if (env == NULL) {
        *error_message = "the host thread is detached";
        return SONGYAN_SCRIPT_FETCH_REJECTED;
    }
    jclass klass = (*env)->GetObjectClass(env, context->host);
    jmethodID method = (*env)->GetMethodID(env, klass, "fetch",
                                           "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[BJ)V");
    if (method == NULL) {
        (*env)->DeleteLocalRef(env, klass);
        *error_message = "the host has no transport";
        return SONGYAN_SCRIPT_FETCH_REJECTED;
    }
    jstring url_string = (*env)->NewStringUTF(env, request->url);
    jstring method_string = (*env)->NewStringUTF(env, request->method);
    jstring headers_string = request->headers_json == NULL
                                 ? NULL
                                 : (*env)->NewStringUTF(env, request->headers_json);
    jbyteArray body_array = NULL;
    if (request->body != NULL && request->body_length > 0) {
        body_array = (*env)->NewByteArray(env, (jsize)request->body_length);
        if (body_array != NULL) {
            (*env)->SetByteArrayRegion(env, body_array, 0, (jsize)request->body_length,
                                       (const jbyte *)request->body);
        }
    }
    /* Synchronous on purpose: the transport runs in the proxy process over Binder and the engine
     * thread waits. The bridge stops its compute clock for this call, and the request gets what is
     * left of the run's duration, so a slow server is bounded by the run and nothing else. The
     * reply lands in pending_reply through setFetchReply. */
    memset(&pending_reply, 0, sizeof(pending_reply));
    (*env)->CallVoidMethod(env, context->host, method, url_string, method_string,
                           headers_string, body_array, (jlong)songyan_script_run_ms_left(run));
    (*env)->DeleteLocalRef(env, url_string);
    (*env)->DeleteLocalRef(env, method_string);
    if (headers_string != NULL) {
        (*env)->DeleteLocalRef(env, headers_string);
    }
    if (body_array != NULL) {
        (*env)->DeleteLocalRef(env, body_array);
    }
    (*env)->DeleteLocalRef(env, klass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        *error_message = "the transport threw";
        return SONGYAN_SCRIPT_FETCH_REJECTED;
    }
    if (pending_reply.accepted == 0) {
        *error_message = pending_reply.error == NULL ? "the transport refused" : pending_reply.error;
        return SONGYAN_SCRIPT_FETCH_REJECTED;
    }
    response->status = pending_reply.status;
    response->body = pending_reply.body;
    response->body_length = pending_reply.body_length;
    response->content_type = pending_reply.content_type;
    response->final_url = pending_reply.final_url;
    response->headers_json = pending_reply.headers_json;
    /* The bridge copies the response, so the reply buffers are freed on the next call. */
    return SONGYAN_SCRIPT_FETCH_ACCEPTED;
}

static void run_log(void *userdata, const char *text, size_t length) {
    script_run_context *context = userdata;
    JNIEnv *env = run_env(context);
    if (env == NULL) {
        return;
    }
    jclass klass = (*env)->GetObjectClass(env, context->host);
    jmethodID method = (*env)->GetMethodID(env, klass, "log", "([B)V");
    if (method == NULL) {
        (*env)->DeleteLocalRef(env, klass);
        return;
    }
    /* Keep the line for the result JSON (the same retention the CLI host has). */
    if (active_log_count < sizeof(active_logs) / sizeof(active_logs[0])) {
        active_logs[active_log_count] = malloc(length + 1);
        if (active_logs[active_log_count] != NULL) {
            memcpy(active_logs[active_log_count], text, length);
            active_logs[active_log_count][length] = '\0';
            active_log_lengths[active_log_count] = length;
            active_log_count++;
        }
    }
    /* Strict UTF-8 crosses as bytes; JNI strings are modified UTF-8. */
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes != NULL) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)text);
        (*env)->CallVoidMethod(env, context->host, method, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    (*env)->DeleteLocalRef(env, klass);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
} jstring_buffer;

static void buffer_push(jstring_buffer *buffer, const char *text, size_t length) {
    while (buffer->length + length + 1 > buffer->capacity) {
        buffer->capacity = buffer->capacity == 0 ? 256 : buffer->capacity * 2;
        buffer->data = realloc(buffer->data, buffer->capacity);
    }
    memcpy(buffer->data + buffer->length, text, length);
    buffer->length += length;
    buffer->data[buffer->length] = '\0';
}

#define BUFFER_LITERAL(literal) (literal), (sizeof(literal) - 1)

static void buffer_json_escape(jstring_buffer *buffer, const char *text, size_t length) {
    char scratch[8];
    for (size_t index = 0; index < length; index++) {
        unsigned char character = (unsigned char)text[index];
        switch (character) {
        case '"': buffer_push(buffer, "\\\"", 2); break;
        case '\\': buffer_push(buffer, "\\\\", 2); break;
        case '\n': buffer_push(buffer, "\\n", 2); break;
        case '\r': buffer_push(buffer, "\\r", 2); break;
        case '\t': buffer_push(buffer, "\\t", 2); break;
        default:
            if (character < 0x20) {
                snprintf(scratch, sizeof(scratch), "\\u%04x", character);
                buffer_push(buffer, scratch, 6);
            } else {
                buffer_push(buffer, (const char *)&character, 1);
            }
            break;
        }
    }
}

static jbyteArray result_json(JNIEnv *env, const songyan_script_run_result *result,
                              char **logs, size_t *log_lengths, size_t log_count) {
    jstring_buffer buffer = {0};
    if (result->error == SONGYAN_SCRIPT_OK) {
        buffer_push(&buffer, BUFFER_LITERAL("{\n  \"ok\": true,\n  \"outputs\": ["));
        for (size_t index = 0; index < result->output_count; index++) {
            buffer_push(&buffer, index == 0 ? "\n" : ",\n", index == 0 ? 1 : 2);
            buffer_push(&buffer, "    \"", 5);
            buffer_json_escape(&buffer, result->outputs[index], result->output_lengths[index]);
            buffer_push(&buffer, "\"", 1);
        }
        if (result->output_count > 0) {
            buffer_push(&buffer, "\n  ", 3);
        }
        buffer_push(&buffer, BUFFER_LITERAL("],\n  \"logs\": ["));
        for (size_t index = 0; index < log_count; index++) {
            buffer_push(&buffer, index == 0 ? "\n" : ",\n", index == 0 ? 1 : 2);
            buffer_push(&buffer, "    \"", 5);
            buffer_json_escape(&buffer, logs[index], log_lengths[index]);
            buffer_push(&buffer, "\"", 1);
        }
        if (log_count > 0) {
            buffer_push(&buffer, "\n  ", 3);
        }
        buffer_push(&buffer, BUFFER_LITERAL("],\n  \"logsTruncated\": false\n}"));
    } else {
        buffer_push(&buffer, BUFFER_LITERAL("{\n  \"ok\": false,\n  \"error\": \""));
        buffer_push(&buffer, songyan_script_error_name(result->error),
                    strlen(songyan_script_error_name(result->error)));
        buffer_push(&buffer, BUFFER_LITERAL("\",\n  \"message\": \""));
        buffer_json_escape(&buffer, result->message == NULL ? "" : result->message,
                           result->message == NULL ? 0 : strlen(result->message));
        buffer_push(&buffer, BUFFER_LITERAL("\",\n  \"line\": "));
        char line_text[24];
        snprintf(line_text, sizeof(line_text), "%zu", result->line);
        buffer_push(&buffer, line_text, strlen(line_text));
        buffer_push(&buffer, "\n}", 2);
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)buffer.length);
    if (out != NULL && buffer.length > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)buffer.length, (const jbyte *)buffer.data);
    }
    free(buffer.data);
    return out;
}

JNIEXPORT jlong JNICALL
Java_app_songyan_ime_script_ScriptNative_createRun(
    JNIEnv *env, jclass type, jbyteArray source, jbyteArray settings, jbyteArray text,
    jbyteArray audio, jint source_bytes, jint heap_bytes, jint duration_ms, jint compute_ms,
    jint request_count, jint response_bytes, jint input_text_bytes) {
    (void)type;
    if (!guard_ok(env)) return 0;
    jclass host_class = (*env)->FindClass(env, "app/songyan/ime/script/ScriptRunHost");
    if (host_class == NULL) {
        SCRIPT_LOGE("createRun: ScriptRunHost not found");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jmethodID use_method = (*env)->GetStaticMethodID(env, host_class, "use",
                                                   "()Lapp/songyan/ime/script/ScriptRunHost;");
    if (use_method == NULL) {
        SCRIPT_LOGE("createRun: ScriptRunHost.use missing");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jobject host = (*env)->CallStaticObjectMethod(env, host_class, use_method);
    if (host == NULL || (*env)->ExceptionCheck(env)) {
        SCRIPT_LOGE("createRun: host instance unavailable");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jsize source_length = (*env)->GetArrayLength(env, source);
    jbyte *source_ptr = (*env)->GetByteArrayElements(env, source, NULL);
    songyan_script_error code = songyan_script_header_parse(
        (const char *)source_ptr, (size_t)source_length, &active_header, NULL);
    if (code != SONGYAN_SCRIPT_OK) {
        SCRIPT_LOGE("createRun: header refused (%d)", (int)code);
        (*env)->ReleaseByteArrayElements(env, source, source_ptr, JNI_ABORT);
        return 0;
    }
    songyan_script_limits limits;
    limits.source_bytes = (size_t)source_bytes;
    limits.heap_bytes = (size_t)heap_bytes;
    limits.duration_ms = (uint32_t)duration_ms;
    limits.compute_ms = (uint32_t)compute_ms;
    limits.request_count = (uint32_t)request_count;
    limits.response_bytes = (size_t)response_bytes;
    limits.input_text_bytes = (size_t)input_text_bytes;
    jsize settings_length = settings == NULL ? 0 : (*env)->GetArrayLength(env, settings);
    /* settings_json is a C string in the contract (no length field), so the JNI byte array needs
     * its own NUL terminator; the run copies it before the temporary is released. */
    char *settings_copy = NULL;
    if (settings_length > 0) {
        settings_copy = malloc((size_t)settings_length + 1);
        if (settings_copy != NULL) {
            (*env)->GetByteArrayRegion(env, settings, 0, settings_length, (jbyte *)settings_copy);
            settings_copy[settings_length] = '\0';
        }
    }
    jsize text_length = text == NULL ? 0 : (*env)->GetArrayLength(env, text);
    const char *text_ptr = text_length > 0 ? (const char *)(*env)->GetByteArrayElements(env, text, NULL) : NULL;
    /* @input audio: the WAV bytes become the script's `audio` ArrayBuffer. ScriptIsolatedService
     * read them out of the caller's descriptor; a text run passes NULL here, which leaves the host
     * with the text capability only and lets the bridge refuse an audio script as it always did. */
    jsize audio_length = audio == NULL ? 0 : (*env)->GetArrayLength(env, audio);
    jbyte *audio_ptr = audio_length > 0 ? (*env)->GetByteArrayElements(env, audio, NULL) : NULL;
    songyan_script_run_config config;
    memset(&config, 0, sizeof(config));
    config.source = (const char *)source_ptr;
    config.source_length = (size_t)source_length;
    config.header = &active_header;
    config.host_capabilities = SONGYAN_SCRIPT_CAPABILITY_TEXT;
    config.limits = &limits;
    config.settings_json = settings_copy;
    config.text = text_ptr;
    config.text_length = (size_t)text_length;
    if (audio_ptr != NULL && audio_length > 0) {
        config.audio = (const unsigned char *)audio_ptr;
        config.audio_length = (size_t)audio_length;
        config.host_capabilities |= SONGYAN_SCRIPT_CAPABILITY_AUDIO;
    }
    active_context.vm = script_vm;
    active_context.host = (*env)->NewGlobalRef(env, host);
    config.host.userdata = &active_context;
    config.host.authorize = run_authorize;
    config.host.fetch = run_fetch;
    config.host.log = run_log;
    active_run = songyan_script_run_create(&config);
    if (active_run == NULL) {
        SCRIPT_LOGE("createRun: the bridge refused the configuration");
    }
    (*env)->ReleaseByteArrayElements(env, source, source_ptr, JNI_ABORT);
    free(settings_copy);
    if (text_ptr != NULL) {
        (*env)->ReleaseByteArrayElements(env, text, (jbyte *)text_ptr, JNI_ABORT);
    }
    if (audio_ptr != NULL) {
        (*env)->ReleaseByteArrayElements(env, audio, audio_ptr, JNI_ABORT);
    }
    (*env)->DeleteLocalRef(env, host_class);
    (*env)->DeleteLocalRef(env, host);
    active_log_count = 0;
    return (jlong)(intptr_t)active_run;
}

/* The log callback stores lines natively so the result JSON carries them like the CLI's. */

JNIEXPORT jint JNICALL
Java_app_songyan_ime_script_ScriptNative_stepRun(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    (void)handle;
    if (!guard_ok(env)) return -1;
    return (jint)songyan_script_run_step(active_run);
}

JNIEXPORT void JNICALL
Java_app_songyan_ime_script_ScriptNative_cancelRun(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    (void)handle;
    if (!guard_ok(env)) return;
    songyan_script_run_cancel(active_run);
}

JNIEXPORT void JNICALL
Java_app_songyan_ime_script_ScriptNative_destroyRun(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    (void)handle;
    if (!guard_ok(env)) return;
    if (active_run != NULL) {
        songyan_script_run_destroy(active_run);
        active_run = NULL;
    }
    if (active_context.host != NULL) {
        (*env)->DeleteGlobalRef(env, active_context.host);
        active_context.host = NULL;
    }
    songyan_script_header_free(&active_header);
    for (size_t index = 0; index < active_log_count; index++) {
        free(active_logs[index]);
    }
    active_log_count = 0;
}

JNIEXPORT jbyteArray JNICALL
Java_app_songyan_ime_script_ScriptNative_takeResultJson(JNIEnv *env, jclass type, jlong handle,
                                                        jint step) {
    (void)type;
    (void)handle;
    if (!guard_ok(env)) return NULL;
    songyan_script_run_result result;
    memset(&result, 0, sizeof(result));
    songyan_script_run_result_take(active_run, &result);
    jbyteArray report;
    if (step == 5) {
        /* INVALID: the bridge reports an invalid configuration as STEP_INVALID and leaves the
         * result's error empty, with the reason in its message. Name that state in the Android
         * report so the host does not show it as a successful empty result. */
        jstring_buffer buffer = {0};
        buffer_push(&buffer, BUFFER_LITERAL(
            "{\n  \"ok\": false,\n  \"error\": \"invalidConfiguration\",\n  \"message\": \""));
        buffer_json_escape(&buffer, result.message == NULL ? "" : result.message,
                           result.message == NULL ? 0 : strlen(result.message));
        buffer_push(&buffer, BUFFER_LITERAL("\",\n  \"line\": 0\n}"));
        report = (*env)->NewByteArray(env, (jsize)buffer.length);
        if (report != NULL && buffer.length > 0) {
            (*env)->SetByteArrayRegion(env, report, 0, (jsize)buffer.length,
                                       (const jbyte *)buffer.data);
        }
        free(buffer.data);
    } else {
        report = result_json(env, &result, active_logs, active_log_lengths, active_log_count);
    }
    songyan_script_run_result_free(&result);
    for (size_t index = 0; index < active_log_count; index++) {
        free(active_logs[index]);
    }
    active_log_count = 0;
    return report;
}

static void buffer_json_string(jstring_buffer *buffer, const char *text) {
    buffer_push(buffer, "\"", 1);
    if (text != NULL) buffer_json_escape(buffer, text, strlen(text));
    buffer_push(buffer, "\"", 1);
}

static void buffer_json_strings(jstring_buffer *buffer, const char *const *items, size_t count) {
    buffer_push(buffer, "[", 1);
    for (size_t index = 0; index < count; index++) {
        if (index > 0) buffer_push(buffer, ",", 1);
        buffer_json_string(buffer, items[index]);
    }
    buffer_push(buffer, "]", 1);
}

/* The production header parse. It reports the same keys as the CLI's `header` command, which
 * only Debug libraries contain, so the proxy decodes both with one reader. Returns NULL when
 * memory runs out. */
JNIEXPORT jbyteArray JNICALL
Java_app_songyan_ime_script_ScriptNative_parseHeader(JNIEnv *env, jclass type, jbyteArray source) {
    (void)type;
    if (!guard_ok(env)) return NULL;
    jsize length = (*env)->GetArrayLength(env, source);
    char *bytes = malloc((size_t)length + 1);
    if (bytes == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)bytes);
    bytes[length] = '\0';
    songyan_script_header header;
    songyan_script_header_error error;
    memset(&error, 0, sizeof(error));
    songyan_script_error code = songyan_script_header_parse(bytes, (size_t)length, &header, &error);
    free(bytes);
    if (code == SONGYAN_SCRIPT_ERROR_OUT_OF_MEMORY) return NULL;
    jstring_buffer buffer = {0};
    char number[24];
    if (code == SONGYAN_SCRIPT_OK) {
        buffer_push(&buffer, BUFFER_LITERAL("{\"ok\":true,\"error\":\"ok\",\"line\":0,\"header\":{\"api\":"));
        snprintf(number, sizeof(number), "%u", header.api);
        buffer_push(&buffer, number, strlen(number));
        buffer_push(&buffer, BUFFER_LITERAL(",\"name\":"));
        buffer_json_string(&buffer, header.name);
        buffer_push(&buffer, BUFFER_LITERAL(",\"description\":"));
        if (header.description == NULL) {
            buffer_push(&buffer, BUFFER_LITERAL("null"));
        } else {
            buffer_json_string(&buffer, header.description);
        }
        buffer_push(&buffer, BUFFER_LITERAL(",\"connects\":"));
        buffer_json_strings(&buffer, (const char *const *)header.connects, header.connect_count);
        buffer_push(&buffer, BUFFER_LITERAL(",\"settings\":["));
        for (size_t index = 0; index < header.setting_count; index++) {
            const songyan_script_setting *setting = &header.settings[index];
            buffer_push(&buffer, index > 0 ? ",{\"key\":" : "{\"key\":", index > 0 ? 8 : 7);
            buffer_json_string(&buffer, setting->key);
            buffer_push(&buffer, BUFFER_LITERAL(",\"kind\":"));
            buffer_json_string(&buffer, songyan_script_setting_kind_name(setting->kind));
            buffer_push(&buffer, BUFFER_LITERAL(",\"label\":"));
            buffer_json_string(&buffer, setting->label);
            if (setting->kind == SONGYAN_SCRIPT_SETTING_CHOICE) {
                buffer_push(&buffer, BUFFER_LITERAL(",\"options\":"));
                buffer_json_strings(&buffer, (const char *const *)setting->options,
                                    setting->option_count);
            }
            buffer_push(&buffer, "}", 1);
        }
        buffer_push(&buffer, BUFFER_LITERAL("],\"secrets\":["));
        for (size_t index = 0; index < header.secret_count; index++) {
            const songyan_script_secret *secret = &header.secrets[index];
            buffer_push(&buffer, index > 0 ? ",{\"key\":" : "{\"key\":", index > 0 ? 8 : 7);
            buffer_json_string(&buffer, secret->key);
            buffer_push(&buffer, BUFFER_LITERAL(",\"label\":"));
            buffer_json_string(&buffer, secret->label);
            buffer_push(&buffer, "}", 1);
        }
        const char *needs[2];
        size_t need_count = 0;
        if ((header.needs & SONGYAN_SCRIPT_CAPABILITY_TEXT) != 0) {
            needs[need_count++] = songyan_script_capability_name(SONGYAN_SCRIPT_CAPABILITY_TEXT);
        }
        if ((header.needs & SONGYAN_SCRIPT_CAPABILITY_AUDIO) != 0) {
            needs[need_count++] = songyan_script_capability_name(SONGYAN_SCRIPT_CAPABILITY_AUDIO);
        }
        buffer_push(&buffer, BUFFER_LITERAL("],\"needs\":"));
        buffer_json_strings(&buffer, needs, need_count);
        if (header.timeout_seconds != 0) {
            buffer_push(&buffer, BUFFER_LITERAL(",\"timeoutSeconds\":"));
            snprintf(number, sizeof(number), "%u", header.timeout_seconds);
            buffer_push(&buffer, number, strlen(number));
        }
        buffer_push(&buffer, BUFFER_LITERAL("}}"));
        songyan_script_header_free(&header);
    } else {
        buffer_push(&buffer, BUFFER_LITERAL("{\"ok\":false,\"error\":"));
        buffer_json_string(&buffer, songyan_script_error_name(code));
        buffer_push(&buffer, BUFFER_LITERAL(",\"line\":"));
        snprintf(number, sizeof(number), "%zu", error.line);
        buffer_push(&buffer, number, strlen(number));
        buffer_push(&buffer, BUFFER_LITERAL(",\"message\":"));
        buffer_json_string(&buffer, error.message);
        if (error.message_truncated) {
            buffer_push(&buffer, BUFFER_LITERAL(",\"messageTruncated\":true"));
        }
        buffer_push(&buffer, "}", 1);
    }
    jbyteArray report = (*env)->NewByteArray(env, (jsize)buffer.length);
    if (report != NULL) {
        (*env)->SetByteArrayRegion(env, report, 0, (jsize)buffer.length, (const jbyte *)buffer.data);
    }
    free(buffer.data);
    return report;
}

#ifdef SONGYAN_SCRIPT_CLI
/* Declared in the synced main.c, compiled into the Debug library with
 * -Dmain=songyan_script_cli_main so the shared parsing and the canonical JSON serialization run
 * unmodified. Release builds do not compile the CLI front end at all. */
int songyan_script_cli_main(int argc, char **argv);
#endif

static JavaVM *script_vm;
static int script_process_allowed;


/* Returns 1 when the Java runtime reports the current process as isolated. Any failure to
 * answer (missing class, thrown exception, unexpected type) counts as not isolated: the guard
 * fails closed. */
static int process_is_isolated(JNIEnv *env) {
    jclass klass = (*env)->FindClass(env, "android/os/Process");
    if (klass == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    jmethodID method = (*env)->GetStaticMethodID(env, klass, "isIsolated", "()Z");
    if (method == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, klass);
        return 0;
    }
    jboolean isolated = (*env)->CallStaticBooleanMethod(env, klass, method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, klass);
        return 0;
    }
    (*env)->DeleteLocalRef(env, klass);
    return isolated == JNI_TRUE;
}

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (!process_is_isolated(env)) {
        SCRIPT_LOGE("the script engine may only load in an isolated process");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        /* A failed JNI_OnLoad makes System.loadLibrary throw UnsatisfiedLinkError, so the
         * caller cannot reach any engine entry point afterwards. */
        return JNI_ERR;
    }
    script_vm = vm;
    script_process_allowed = 1;
    return JNI_VERSION_1_6;
}

/* Every entry point re-checks the flag: a direct symbol lookup that skipped JNI_OnLoad still
 * cannot run the engine. */
static int guard_ok(JNIEnv *env) {
    if (script_process_allowed) return 1;
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (error != NULL) {
        (*env)->ThrowNew(env, error, "the script engine may only load in an isolated process");
        (*env)->DeleteLocalRef(env, error);
    }
    return 0;
}

static char *copy_jstring(JNIEnv *env, jstring text) {
    if (text == NULL) {
        return NULL;
    }
    const char *chars = (*env)->GetStringUTFChars(env, text, NULL);
    if (chars == NULL) {
        return NULL;
    }
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, text, chars);
    return copy;
}

#ifdef SONGYAN_SCRIPT_CLI
/* SELinux denies isolated_app reopening tmpfs files by path, so the shared CLI (which reads
 * case files with fopen) cannot use /proc/self/fd/<n> on Android. This wrapper intercepts fopen
 * inside this library: a /proc/self/fd/<n> path becomes fdopen(dup(n)) and needs no path open.
 * The symbol has default visibility so the dynamic linker resolves the CLI's fopen calls here. */
FILE *fopen(const char *path, const char *mode);
static FILE *(*real_fopen)(const char *, const char *);

FILE *songyan_script_fopen(const char *path, const char *mode) {
    static const char prefix[] = "/proc/self/fd/";
    size_t prefix_length = sizeof(prefix) - 1;
    if (strncmp(path, prefix, prefix_length) == 0) {
        const char *digits = path + prefix_length;
        char *end = NULL;
        long number = strtol(digits, &end, 10);
        if (end != NULL && *end == '\0' && number >= 0) {
            int descriptor = dup((int)number);
            if (descriptor >= 0) {
                FILE *stream = fdopen(descriptor, mode);
                if (stream != NULL) {
                    return stream;
                }
                close(descriptor);
            }
        }
    }
    if (real_fopen == NULL) {
        real_fopen = dlsym(RTLD_NEXT, "fopen");
        if (real_fopen == NULL) {
            return NULL;
        }
    }
    return real_fopen(path, mode);
}

/* The shared bridge objects call fopen; the interposer must be exported for them to bind to it,
 * so it is aliased under the plain name via the version script (see exports.map). */
__asm__(".globl fopen\n.set fopen, songyan_script_fopen\n");
#endif /* SONGYAN_SCRIPT_CLI */

#ifdef SONGYAN_SCRIPT_CLI
/* Runs the shared CLI with argv built from Java and returns {Integer exitCode, byte[] stdout,
 * byte[] stderr}, so the test host on the app side can diff the canonical JSON byte for byte against
 * the shared expected.json files. stdout/stderr are redirected to in-memory streams around the
 * call and restored afterwards. Case inputs are /proc/self/fd/<n> paths to memfds the caller
 * opened, which is how the shared CLI reads them inside an isolated process. */
JNIEXPORT jobjectArray JNICALL
Java_app_songyan_ime_script_ScriptNative_runCli(JNIEnv *env, jclass type,
                                                 jobjectArray arguments) {
    (void)type;
    if (!guard_ok(env)) return NULL;
    jsize count = (*env)->GetArrayLength(env, arguments);
    if (count < 0 || count > 512) {
        return NULL;
    }
    char **argv = calloc((size_t)count + 2, sizeof(char *));
    if (argv == NULL) {
        return NULL;
    }
    argv[0] = "songyan-script";
    for (jsize index = 0; index < count; index++) {
        jstring argument = (jstring)(*env)->GetObjectArrayElement(env, arguments, index);
        argv[index + 1] = copy_jstring(env, argument);
        if (argument != NULL) {
            (*env)->DeleteLocalRef(env, argument);
        }
        if (argv[index + 1] == NULL) {
            for (jsize freed = 1; freed <= index; freed++) {
                free(argv[freed]);
            }
            free(argv);
            return NULL;
        }
    }
    argv[count + 1] = NULL;

    char *out_bytes = NULL;
    size_t out_size = 0;
    char *err_bytes = NULL;
    size_t err_size = 0;
    FILE *out_capture = open_memstream(&out_bytes, &out_size);
    FILE *err_capture = open_memstream(&err_bytes, &err_size);
    int exit_code = -1;
    if (out_capture != NULL && err_capture != NULL) {
        fflush(stdout);
        fflush(stderr);
        FILE *real_stdout = stdout;
        FILE *real_stderr = stderr;
        stdout = out_capture;
        stderr = err_capture;
        exit_code = songyan_script_cli_main((int)count + 1, argv);
        fflush(out_capture);
        fflush(err_capture);
        stdout = real_stdout;
        stderr = real_stderr;
    }
    fclose(out_capture);
    fclose(err_capture);
    for (jsize index = 1; index <= count; index++) {
        free(argv[index]);
    }
    free(argv);
    if (out_bytes == NULL) {
        free(err_bytes);
        return NULL;
    }

    /* stdout/stderr cross as byte[]: NewStringUTF decodes modified UTF-8 and would corrupt
     * 4-byte UTF-8 sequences (emoji), so the Java side decodes strict UTF-8 instead. */
    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
    jclass byte_class = (*env)->FindClass(env, "[B");
    if (object_class == NULL || byte_class == NULL) {
        free(out_bytes);
        free(err_bytes);
        return NULL;
    }
    jobjectArray result = (*env)->NewObjectArray(env, 3, object_class, NULL);
    jclass integer_class = (*env)->FindClass(env, "java/lang/Integer");
    if (result == NULL || integer_class == NULL) {
        free(out_bytes);
        free(err_bytes);
        return NULL;
    }
    jmethodID value_of = (*env)->GetStaticMethodID(env, integer_class, "valueOf",
                                                   "(I)Ljava/lang/Integer;");
    if (value_of == NULL) {
        free(out_bytes);
        free(err_bytes);
        return NULL;
    }
    jobject exit_boxed = (*env)->CallStaticObjectMethod(env, integer_class, value_of, exit_code);
    jbyteArray out_array = (*env)->NewByteArray(env, (jsize)out_size);
    jbyteArray err_array = (*env)->NewByteArray(env, (jsize)(err_bytes == NULL ? 0 : err_size));
    if (exit_boxed == NULL || out_array == NULL || err_array == NULL) {
        free(out_bytes);
        free(err_bytes);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out_array, 0, (jsize)out_size, (const jbyte *)out_bytes);
    if (err_bytes != NULL && err_size > 0) {
        (*env)->SetByteArrayRegion(env, err_array, 0, (jsize)err_size, (const jbyte *)err_bytes);
    }
    free(out_bytes);
    free(err_bytes);
    (*env)->SetObjectArrayElement(env, result, 0, exit_boxed);
    (*env)->SetObjectArrayElement(env, result, 1, out_array);
    (*env)->SetObjectArrayElement(env, result, 2, err_array);
    return result;
}
#endif /* SONGYAN_SCRIPT_CLI */


#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <android/log.h>

#include <mobi.h>

#define LOG_TAG "NativeMobiBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int mkdir_p(const char *path) {
    char tmp[1024];
    char *p = NULL;
    size_t len;

    if (!path || !*path) return -1;
    snprintf(tmp, sizeof(tmp), "%s", path);
    len = strlen(tmp);
    if (len == 0) return 0;
    if (tmp[len - 1] == '/') tmp[len - 1] = '\0';

    for (p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    return mkdir(tmp, 0755);
}

static int ensure_dir(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) return 0;
        return -1;
    }
    return mkdir_p(path);
}

static int write_file_data(const char *path, const unsigned char *data, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f) {
        LOGE("Failed to open file for writing: %s (errno: %d)", path, errno);
        return -1;
    }
    if (size > 0 && data != NULL) {
        if (fwrite(data, 1, size, f) != size) {
            LOGE("Failed to write %zu bytes to %s", size, path);
            fclose(f);
            return -1;
        }
    }
    fclose(f);
    return 0;
}

static jobjectArray make_result(
    JNIEnv *env,
    const char *status,
    const char *format,
    const char *title,
    const char *author,
    const char *language,
    const char *error_msg
) {
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    if (!string_cls) {
        return NULL;
    }

    jobjectArray arr = (*env)->NewObjectArray(env, 6, string_cls, NULL);
    if (!arr) {
        return NULL;
    }

    const char *values[6] = {status, format, title, author, language, error_msg};
    for (int i = 0; i < 6; i++) {
        if (values[i] != NULL) {
            jstring s = (*env)->NewStringUTF(env, values[i]);
            if (s) {
                (*env)->SetObjectArrayElement(env, arr, i, s);
                (*env)->DeleteLocalRef(env, s);
            }
        }
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_playertwo1_bookconverter_data_NativeMobiBridge_extractMobiToDir(
    JNIEnv *env,
    jobject thiz,
    jstring inputPath,
    jstring outputDir
) {
    if (!inputPath || !outputDir) {
        return make_result(env, "ERROR", "", NULL, NULL, NULL, "Invalid input/output null argument");
    }

    const char *c_input = (*env)->GetStringUTFChars(env, inputPath, NULL);
    const char *c_output = (*env)->GetStringUTFChars(env, outputDir, NULL);
    if (!c_input || !c_output) {
        if (c_input) (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        if (c_output) (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", "", NULL, NULL, NULL, "Failed to get path UTF strings");
    }

    char markup_dir[1024];
    char resources_dir[1024];
    snprintf(markup_dir, sizeof(markup_dir), "%s/markup", c_output);
    snprintf(resources_dir, sizeof(resources_dir), "%s/resources", c_output);

    if (ensure_dir(c_output) != 0 || ensure_dir(markup_dir) != 0 || ensure_dir(resources_dir) != 0) {
        LOGE("Failed to create destination directories in %s", c_output);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", "", NULL, NULL, NULL, "Failed to create destination directories");
    }

    MOBIData *m = mobi_init();
    if (!m) {
        LOGE("Failed to initialize MOBIData");
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", "", NULL, NULL, NULL, "Failed to initialize libmobi");
    }

    MOBI_RET ret = mobi_load_filename(m, c_input);
    if (ret == MOBI_FILE_ENCRYPTED || mobi_is_encrypted(m)) {
        LOGI("Detected DRM in file: %s", c_input);
        size_t file_ver = mobi_get_fileversion(m);
        const char *fmt = (file_ver >= 8) ? "AZW3" : "MOBI";
        char *meta_t = mobi_meta_get_title(m);
        char *meta_a = mobi_meta_get_author(m);
        char *meta_l = mobi_meta_get_language(m);

        jobjectArray res = make_result(env, "DRM", fmt, meta_t, meta_a, meta_l, "Document is protected by DRM");

        if (meta_t) free(meta_t);
        if (meta_a) free(meta_a);
        if (meta_l) free(meta_l);

        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return res;
    }

    if (ret != MOBI_SUCCESS) {
        LOGE("mobi_load_filename failed with code %d for %s", ret, c_input);
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", "", NULL, NULL, NULL, "Failed to load MOBI/AZW3 file");
    }

    if (mobi_is_encrypted(m)) {
        LOGI("Document encrypted check triggered for: %s", c_input);
        size_t file_ver = mobi_get_fileversion(m);
        const char *fmt = (file_ver >= 8) ? "AZW3" : "MOBI";
        char *meta_t = mobi_meta_get_title(m);
        char *meta_a = mobi_meta_get_author(m);
        char *meta_l = mobi_meta_get_language(m);

        jobjectArray res = make_result(env, "DRM", fmt, meta_t, meta_a, meta_l, "Document is protected by DRM");

        if (meta_t) free(meta_t);
        if (meta_a) free(meta_a);
        if (meta_l) free(meta_l);

        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return res;
    }

    size_t file_ver = mobi_get_fileversion(m);
    const char *fmt = (file_ver >= 8) ? "AZW3" : "MOBI";
    char *meta_title = mobi_meta_get_title(m);
    char *meta_author = mobi_meta_get_author(m);
    char *meta_language = mobi_meta_get_language(m);

    MOBIRawml *rawml = mobi_init_rawml(m);
    if (!rawml) {
        LOGE("Failed to allocate MOBIRawml");
        if (meta_title) free(meta_title);
        if (meta_author) free(meta_author);
        if (meta_language) free(meta_language);
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", fmt, NULL, NULL, NULL, "Memory allocation failed for rawml");
    }

    ret = mobi_parse_rawml(rawml, m);
    if (ret != MOBI_SUCCESS) {
        LOGE("mobi_parse_rawml failed with code %d", ret);
        mobi_free_rawml(rawml);
        if (meta_title) free(meta_title);
        if (meta_author) free(meta_author);
        if (meta_language) free(meta_language);
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
        (*env)->ReleaseStringUTFChars(env, outputDir, c_output);
        return make_result(env, "ERROR", fmt, NULL, NULL, NULL, "Failed to parse MOBI rawml records");
    }

    /* 1. Extract markup parts */
    int part_idx = 0;
    MOBIPart *curr = rawml->markup;
    while (curr != NULL) {
        char part_path[1024];
        snprintf(part_path, sizeof(part_path), "%s/markup/part_%04d.html", c_output, part_idx++);
        write_file_data(part_path, curr->data, curr->size);
        curr = curr->next;
    }

    /* 2. Extract resource parts */
    curr = rawml->resources;
    while (curr != NULL) {
        MOBIFileMeta file_meta = mobi_get_filemeta_by_type(curr->type);
        const char *ext = (file_meta.extension[0] != '\0') ? file_meta.extension : "dat";

        char res_path[1024];
        snprintf(res_path, sizeof(res_path), "%s/resources/res_%05zu.%s", c_output, curr->uid, ext);
        write_file_data(res_path, curr->data, curr->size);

        char alias_path[1024];
        snprintf(alias_path, sizeof(alias_path), "%s/resources/resource%05zu.%s", c_output, curr->uid, ext);
        if (strcmp(res_path, alias_path) != 0) {
            write_file_data(alias_path, curr->data, curr->size);
        }

        curr = curr->next;
    }

    /* 3. Extract flow parts (supplementary styles / text if any) */
    if (rawml->flow != NULL) {
        curr = rawml->flow;
        if (curr != NULL) {
            curr = curr->next; /* skip raw concatenated html */
        }
        while (curr != NULL) {
            MOBIFileMeta file_meta = mobi_get_filemeta_by_type(curr->type);
            const char *ext = (file_meta.extension[0] != '\0') ? file_meta.extension : "css";
            char flow_path[1024];
            snprintf(flow_path, sizeof(flow_path), "%s/resources/flow_%05zu.%s", c_output, curr->uid, ext);
            write_file_data(flow_path, curr->data, curr->size);
            curr = curr->next;
        }
    }

    mobi_free_rawml(rawml);
    mobi_free(m);

    jobjectArray res = make_result(env, "OK", fmt, meta_title, meta_author, meta_language, NULL);

    if (meta_title) free(meta_title);
    if (meta_author) free(meta_author);
    if (meta_language) free(meta_language);

    (*env)->ReleaseStringUTFChars(env, inputPath, c_input);
    (*env)->ReleaseStringUTFChars(env, outputDir, c_output);

    return res;
}

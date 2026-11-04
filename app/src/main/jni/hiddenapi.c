/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

#include <jni.h>

/*
 * Targeted allowlist of signature prefixes strictly required by OpenManager.
 * Acotando estos prefijos se evita una exención global ("" o "L") que expondría
 * todas las APIs ocultas del sistema (como telefonía, keystore interno o cuentas),
 * limitando el acceso reflectivo únicamente a los componentes necesarios para el
 * gestor de archivos y operaciones root.
 */
static const char *const EXEMPTION_PREFIXES[] = {
    // Activity and process management
    "Landroid/app/ActivityManager;",
    "Landroid/app/ActivityThread;",
    "Landroid/app/IActivityManager;",
    // Context and application info
    "Landroid/content/Context;",
    "Landroid/content/pm/ApplicationInfo;",
    // OS, IPC and low-level system services
    "Landroid/os/Parcel;",
    "Landroid/os/SELinux;",
    "Landroid/os/ServiceManager;",
    "Landroid/os/storage/StorageManager;",
    "Landroid/os/storage/StorageVolume;",
    // Media provider volume resolution
    "Landroid/provider/MediaStore;",
    // Quick Settings tile token
    "Landroid/service/quicksettings/TileService;",
    // POSIX error handling
    "Landroid/system/ErrnoException;",
    // UI layout / touch handling
    "Landroid/view/ViewGroup;",
    "Landroid/widget/TextView;",
    // High-performance NIO channels
    "Ljava/nio/NioUtils;",
    "Lsun/nio/ch/FileChannelImpl;"
};

#define EXEMPTION_COUNT (sizeof(EXEMPTION_PREFIXES) / sizeof(EXEMPTION_PREFIXES[0]))

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    void *vmEnv = NULL;
    if ((*vm)->GetEnv(vm, &vmEnv, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    JNIEnv *env = vmEnv;
    jclass vmRuntimeClass = (*env)->FindClass(env, "dalvik/system/VMRuntime");
    if (!vmRuntimeClass) {
        return JNI_ERR;
    }
    jmethodID vmRuntimeGetRuntimeMethod = (*env)->GetStaticMethodID(env, vmRuntimeClass,
            "getRuntime", "()Ldalvik/system/VMRuntime;");
    if (!vmRuntimeGetRuntimeMethod) {
        (*env)->DeleteLocalRef(env, vmRuntimeClass);
        return JNI_ERR;
    }
    jobject vmRuntime = (*env)->CallStaticObjectMethod(env, vmRuntimeClass,
            vmRuntimeGetRuntimeMethod);
    if (!vmRuntime) {
        (*env)->DeleteLocalRef(env, vmRuntimeClass);
        return JNI_ERR;
    }
    jmethodID vmRuntimeSetHiddenApiExemptionsMethod = (*env)->GetMethodID(env, vmRuntimeClass,
            "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (!vmRuntimeSetHiddenApiExemptionsMethod) {
        (*env)->DeleteLocalRef(env, vmRuntime);
        (*env)->DeleteLocalRef(env, vmRuntimeClass);
        return JNI_ERR;
    }
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) {
        (*env)->DeleteLocalRef(env, vmRuntime);
        (*env)->DeleteLocalRef(env, vmRuntimeClass);
        return JNI_ERR;
    }

    jobjectArray signaturePrefixes = (*env)->NewObjectArray(env, (jsize)EXEMPTION_COUNT, stringClass, NULL);
    if (!signaturePrefixes) {
        (*env)->DeleteLocalRef(env, stringClass);
        (*env)->DeleteLocalRef(env, vmRuntime);
        (*env)->DeleteLocalRef(env, vmRuntimeClass);
        return JNI_ERR;
    }

    for (jsize i = 0; i < (jsize)EXEMPTION_COUNT; ++i) {
        jstring prefixStr = (*env)->NewStringUTF(env, EXEMPTION_PREFIXES[i]);
        if (!prefixStr) {
            (*env)->DeleteLocalRef(env, signaturePrefixes);
            (*env)->DeleteLocalRef(env, stringClass);
            (*env)->DeleteLocalRef(env, vmRuntime);
            (*env)->DeleteLocalRef(env, vmRuntimeClass);
            return JNI_ERR;
        }
        (*env)->SetObjectArrayElement(env, signaturePrefixes, i, prefixStr);
        (*env)->DeleteLocalRef(env, prefixStr);
    }

    (*env)->DeleteLocalRef(env, stringClass);

    (*env)->CallVoidMethod(env, vmRuntime, vmRuntimeSetHiddenApiExemptionsMethod,
                           signaturePrefixes);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, signaturePrefixes);
    (*env)->DeleteLocalRef(env, vmRuntime);
    (*env)->DeleteLocalRef(env, vmRuntimeClass);
    return JNI_VERSION_1_6;
}

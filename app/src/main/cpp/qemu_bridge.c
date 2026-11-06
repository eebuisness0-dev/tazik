/*
 * Порт `QemuBridge.swift`: загружает библиотеку эмулятора Inferno и ведёт её
 * жизненный цикл.
 *
 * Android, как и iOS, не умеет fork/exec, поэтому эмулятор работает внутри
 * этого процесса: .so открывается через dlopen, и `qemu_init` /
 * `qemu_main_loop` / `qemu_cleanup` зовутся на отдельном потоке. `qemu_init`
 * возвращается, держа BQL, а `qemu_main_loop` ожидает входить с ним же, так
 * что оба обязаны идти на одном потоке.
 *
 * Символы `inferno_*` — экспорт самой библиотеки MakrSas; они вызываются
 * напрямую после dlopen. Отдельный обработчик сбоев отсюда убран: он ронял
 * INSTALL (SIGILL в autiasp из корутины QEMU), а сборка Inferno ведёт себя
 * без него правильно.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <jni.h>
#include <string.h>
#include <dirent.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "tazik-qemu"

static JavaVM *g_vm;
static void *g_lib;
static pthread_t g_thread;
static _Atomic int g_state = 0; /* 0 = idle, 1 = поток с машиной жив, 2 = завершена */
static char g_error[512];

/* Кэш колбэка в Kotlin: выставляется в nativeStart, зовётся из потока QEMU. */
static jclass g_cls;
static jmethodID g_on_state;

typedef void (*init_fn)(int, char **);
typedef int (*main_loop_fn)(void);
typedef void (*cleanup_fn)(int);
typedef int (*bool_fn)(void);
typedef int (*read_fn)(void *, int, uint32_t *);
typedef void (*touch_fn)(int32_t, int32_t, int);
typedef void (*key_fn)(uint32_t, int);
typedef void (*stats_fn)(uint64_t *);
typedef int (*haptics_fn)(float *, int, uint32_t);
typedef void (*battery_fn)(int32_t, int, int);
typedef int (*link_fn)(void);
typedef void (*attach_fn)(void);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static void set_error(const char *text) {
    snprintf(g_error, sizeof(g_error), "%s", text ? text : "unknown");
}

static void notify_state(int state, jint status) {
    JavaVM *vm = g_vm;
    if (!vm || !g_cls || !g_on_state) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        /* уже привязан */
    } else if ((*vm)->AttachCurrentThread(vm, &env, NULL) == JNI_OK) {
        attached = 1;
    } else {
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_cls, g_on_state, (jint)state, status);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*vm)->DetachCurrentThread(vm);
}

typedef struct {
    int argc;
    char **argv;
    char **env; /* строки «ИМЯ=значение» */
    int envc;
    int attach_display;
    char *stderr_log;
    char *work_dir;
    char *lib_path;
} start_args;

static void *qemu_thread_main(void *raw) {
    start_args *a = (start_args *)raw;

    for (int i = 0; i < a->envc; i++) {
        char *pair = a->env[i];
        char *eq = strchr(pair, '=');
        if (!eq) continue;
        *eq = 0;
        setenv(pair, eq + 1, 1);
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s=%s", pair, eq + 1);
        *eq = '=';
    }
    if (a->stderr_log) {
        if (!freopen(a->stderr_log, "a", stderr)) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "stderr → %s не открылся", a->stderr_log);
        }
    }
    if (a->work_dir && chdir(a->work_dir) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "chdir(%s) не удался", a->work_dir);
    }

    init_fn qemu_init = (init_fn)dlsym(g_lib, "qemu_init");
    main_loop_fn qemu_main_loop = (main_loop_fn)dlsym(g_lib, "qemu_main_loop");
    cleanup_fn qemu_cleanup = (cleanup_fn)dlsym(g_lib, "qemu_cleanup");
    if (!qemu_init || !qemu_main_loop || !qemu_cleanup) {
        set_error("библиотека не экспортирует qemu_init/qemu_main_loop/qemu_cleanup");
        atomic_store(&g_state, 0);
        notify_state(3, 0);
        goto done;
    }

    /* Собственный массив argv, живущий весь прогон. */
    char **argv = (char **)calloc((size_t)a->argc + 1, sizeof(char *));
    if (!argv) {
        set_error("не хватило памяти на argv");
        atomic_store(&g_state, 0);
        notify_state(3, 0);
        goto done;
    }
    for (int i = 0; i < a->argc; i++) argv[i] = strdup(a->argv[i]);

    atomic_store(&g_state, 1);
    notify_state(1, 0);

    /* QEMU читает argv[0] как имя программы и начинает разбор опций с индекса
     * 1; голый аргумент без «-» он считает позиционным диском. Съехавший список
     * ломает разбор самым загадочным образом — ловим это сразу. */
    if (a->argc > 0 && a->argv[0][0] == '-') {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "argv[0] — опция («%s»), а не имя программы: разбор аргументов сдвинется", a->argv[0]);
    }

    qemu_init(a->argc, argv);

    /* `qemu_init` вернулся, держа большой замок, а `qemu_main_loop` ожидает
     * входить с ним же, так что эта щель — единственное безопасное место,
     * чтобы прицепиться к дисплею машины. */
    if (a->attach_display) {
        attach_fn attach = (attach_fn)dlsym(g_lib, "inferno_display_attach");
        if (attach) attach();
    }

    int status = qemu_main_loop();
    qemu_cleanup(status);

    /* Отпустить большой замок, как это делает собственный main QEMU после
     * cleanup. `qemu_init` вручил его этому потоку, и поток, кончающий с ним
     * же, оставляет его навсегда: закрытие тогда звало бы exit(), уведомители
     * выхода QEMU просили бы замок первым делом, и приложение висло бы вместо
     * закрытия. */
    bool_fn locked = (bool_fn)dlsym(g_lib, "bql_locked");
    void (*unlock)(void) = (void (*)(void))dlsym(g_lib, "bql_unlock");
    if (locked && unlock && locked()) unlock();

    atomic_store(&g_state, 2);
    notify_state(2, (jint)status);

    for (int i = 0; i < a->argc; i++) free(argv[i]);
    free(argv);

done:
    for (int i = 0; i < a->envc; i++) free(a->env[i]);
    free(a->env);
    for (int i = 0; i < a->argc; i++) free(a->argv[i]);
    free(a->argv);
    free(a->stderr_log);
    free(a->work_dir);
    free(a->lib_path);
    free(a);
    return NULL;
}

/* --- Стабилизатор шедулинга --------------------------------------------*
 * Разброс темпа между запусками на одном устройстве при прочих равных —
 * след мигания потоков vCPU между ядрами: очереди dwc3 не успевают
 * наполняться, эмулятор отвечает NAK, гость простаивает. Пока машина жива,
 * прибиваем все потоки процесса к самым частотным ядрам (в пределах
 * текущего cpuset, не более 6) и поднимаем nice. Отказы (EPERM/EINVAL)
 * молча игнорируются. */
static int qb_max_freq_of(int cpu) {
    char path[96];
    long v = -1;
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = fopen(path, "r");
    if (f) {
        if (fscanf(f, "%ld", &v) != 1) v = -1;
        fclose(f);
    }
    return (int)v;
}

static int qb_build_mask(cpu_set_t* out) {
    cpu_set_t cur;
    CPU_ZERO(&cur);
    if (sched_getaffinity(0, sizeof(cur), &cur) != 0) return -1;
    int eff = CPU_COUNT(&cur);
    if (eff < 3) return -1; /* слишком тесный cpuset — не трогаем */

    long ncpu = sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu <= 0 || ncpu > 64) ncpu = 64;
    int  order[64];
    long freq[64];
    int  nf = 0;
    for (int c = 0; c < ncpu; c++) {
        if (!CPU_ISSET(c, &cur)) continue;
        order[nf] = c;
        freq[nf]  = qb_max_freq_of(c);
        nf++;
    }
    for (int i = 1; i < nf; i++) { /* сортировка по убыванию частоты */
        int  oc = order[i];
        long fc = freq[i];
        int  j  = i - 1;
        while (j >= 0 && freq[j] < fc) {
            order[j + 1] = order[j];
            freq[j + 1]  = freq[j];
            j--;
        }
        order[j + 1] = oc;
        freq[j + 1]  = fc;
    }
    int want = (eff > 6) ? 6 : eff;
    if (want > nf) want = nf;
    if (want <= 0) return -1;
    CPU_ZERO(out);
    for (int i = 0; i < want; i++) CPU_SET(order[i], out);
    return CPU_COUNT(out);
}

static void* qbridge_boost_main(void* arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "qb-boost");
    /* По умолчанию выключен: на Frogger прибивание давало обратный эффект
     * (run42: 1 ok/s, run43: 63 ok/s против ~400 ok/s без прибивания).
     * Включается переменной INFERNO_QBBOOST=1. */
    {
        const char* e = getenv("INFERNO_QBBOOST");
        if (e == NULL || strcmp(e, "1") != 0) return NULL;
    }
    int last_pinned = -1;
    for (int round = 0; round < 12; round++) {
        usleep(5 * 1000 * 1000);
        if (atomic_load(&g_state) != 1) return NULL;
        cpu_set_t mask;
        int m = qb_build_mask(&mask);
        if (m <= 0) return NULL;
        DIR* d = opendir("/proc/self/task");
        if (!d) return NULL;
        int pinned = 0, scanned = 0, nice_ok = 0, nice_fail = 0;
        struct dirent* de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
            int tid = atoi(de->d_name);
            if (tid <= 0) continue;
            char path[64];
            snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
            FILE* f = fopen(path, "r");
            if (!f) continue;
            char comm[64];
            size_t got = fread(comm, 1, sizeof(comm) - 1, f);
            fclose(f);
            comm[got] = 0;
            for (char* p = comm; *p; p++) if (*p == '\n') { *p = 0; break; }
            scanned++;
            if (strcmp(comm, "qb-boost") == 0) continue;
            if (sched_setaffinity(tid, sizeof(mask), &mask) == 0) pinned++;
            if (setpriority(PRIO_PROCESS, tid, -10) == 0) nice_ok++; else nice_fail++;
        }
        closedir(d);
        if (pinned != last_pinned) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "qb: pinned %d/%d потоков на %d производ. ядер; nice ok/fail=%d/%d",
                                pinned, scanned, m, nice_ok, nice_fail);
            last_pinned = pinned;
        }
    }
    return NULL;
}

/* dlopen библиотеки эмулятора и проверка трёх точек входа. */
static jboolean bridge_open(JNIEnv *env, jstring lib_path) {
    const char *path = (*env)->GetStringUTFChars(env, lib_path, NULL);
    if (!path) {
        set_error("путь к библиотеке не читается");
        return JNI_FALSE;
    }
    void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, lib_path, path);
    if (!lib) {
        const char *reason = dlerror();
        char buffer[512];
        snprintf(buffer, sizeof(buffer), "dlopen failed: %s", reason ? reason : "unknown");
        set_error(buffer);
        return JNI_FALSE;
    }
    g_lib = lib;
    return JNI_TRUE;
}

/* второй qemu_init требует свежий образ библиотеки */
static void bridge_close(void) {
    if (g_lib) {
        /* call_rcu живёт вечно: уцелев под dlclose, он станет вторым
         * потребителем очереди RCU следующей машины (порча кучи, SEGV). */
        void (*rcu_stop)(void) = (void (*)(void))dlsym(g_lib, "qemu_rcu_stop");
        __android_log_print(ANDROID_LOG_INFO, TAG, "bridge_close: rcu_stop=%p", (void*)rcu_stop);
        if (rcu_stop) rcu_stop();
        dlclose(g_lib);
        g_lib = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeStart(
        JNIEnv *env, jclass clazz,
        jstring lib_path, jobjectArray args, jobjectArray env_pairs,
        jboolean attach_display, jstring work_dir, jstring stderr_log) {

    if (atomic_load(&g_state) == 1) return JNI_TRUE;
    if (atomic_load(&g_state) == 2) bridge_close();

    /* stderr в лог-файл ДО dlopen: конструкторы библиотеки (rcu) тоже
     * должны попадать в лог. */
    if (stderr_log) {
        const char *text = (*env)->GetStringUTFChars(env, stderr_log, NULL);
        if (text) {
            if (!freopen(text, "a", stderr)) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "stderr → %s не открылся", text);
            }
            (*env)->ReleaseStringUTFChars(env, stderr_log, text);
        }
    }

    if (!bridge_open(env, lib_path)) return JNI_FALSE;

    /* Кэш колбэка — глобальная ссылка: поток ниже зовёт её без JNIEnv. */
    if (!g_cls) {
        jclass local = (*env)->FindClass(env, "dev/tazik/inferno/engine/QemuNative");
        if (!local) {
            set_error("класс QemuNative не найден");
            return JNI_FALSE;
        }
        g_cls = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
        g_on_state = (*env)->GetStaticMethodID(env, g_cls, "onState", "(II)V");
        if (!g_on_state) {
            set_error("метод QemuNative.onState не найден");
            return JNI_FALSE;
        }
    }

    start_args *a = (start_args *)calloc(1, sizeof(start_args));
    if (!a) {
        set_error("не хватило памяти");
        return JNI_FALSE;
    }

    jsize argc = (*env)->GetArrayLength(env, args);
    a->argc = argc;
    a->argv = (char **)calloc((size_t)(argc > 0 ? argc : 1), sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *text = (*env)->GetStringUTFChars(env, item, NULL);
        a->argv[i] = strdup(text ? text : "");
        (*env)->ReleaseStringUTFChars(env, item, text);
        (*env)->DeleteLocalRef(env, item);
    }

    jsize envc = (*env)->GetArrayLength(env, env_pairs);
    a->envc = envc;
    a->env = (char **)calloc((size_t)(envc > 0 ? envc : 1), sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, env_pairs, i);
        const char *text = (*env)->GetStringUTFChars(env, item, NULL);
        a->env[i] = strdup(text ? text : "");
        (*env)->ReleaseStringUTFChars(env, item, text);
        (*env)->DeleteLocalRef(env, item);
    }

    a->attach_display = attach_display ? 1 : 0;

    if (work_dir) {
        const char *text = (*env)->GetStringUTFChars(env, work_dir, NULL);
        if (text) a->work_dir = strdup(text);
        (*env)->ReleaseStringUTFChars(env, work_dir, text);
    }
    if (stderr_log) {
        const char *text = (*env)->GetStringUTFChars(env, stderr_log, NULL);
        if (text) a->stderr_log = strdup(text);
        (*env)->ReleaseStringUTFChars(env, stderr_log, text);
    }
    {
        const char *text = (*env)->GetStringUTFChars(env, lib_path, NULL);
        a->lib_path = strdup(text ? text : "");
        (*env)->ReleaseStringUTFChars(env, lib_path, text);
    }

    g_error[0] = 0;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    /* Буфер трансляции и эмуляция устройств хотят места дышать. */
    pthread_attr_setstacksize(&attr, 4 * 1024 * 1024);
    if (pthread_create(&g_thread, &attr, qemu_thread_main, a) != 0) {
        set_error("не удалось завести поток эмулятора");
        pthread_attr_destroy(&attr);
        /* qemu_thread_main освобождает a только при запуске. */
        for (int i = 0; i < a->envc; i++) free(a->env[i]);
        free(a->env);
        for (int i = 0; i < a->argc; i++) free(a->argv[i]);
        free(a->argv);
        free(a->stderr_log);
        free(a->work_dir);
        free(a->lib_path);
        free(a);
        return JNI_FALSE;
    }
    pthread_attr_destroy(&attr);

    /* Фон-сканер шедулинга: живёт 60 с, раз в 5 с приводит потоки к маске. */
    {
        pthread_attr_t battr;
        pthread_attr_init(&battr);
        pthread_attr_setdetachstate(&battr, PTHREAD_CREATE_DETACHED);
        pthread_t boost_thread;
        if (pthread_create(&boost_thread, &battr, qbridge_boost_main, NULL) == 0) {
            pthread_setname_np(boost_thread, "qb-boost");
        }
        pthread_attr_destroy(&battr);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeIsRunning(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&g_state) == 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeLastError(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, g_error);
}

JNIEXPORT jboolean JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeHasSymbol(JNIEnv *env, jclass clazz, jstring name) {
    (void)clazz;
    if (!g_lib) return JNI_FALSE;
    const char *symbol = (*env)->GetStringUTFChars(env, name, NULL);
    void *address = symbol ? dlsym(g_lib, symbol) : NULL;
    (*env)->ReleaseStringUTFChars(env, name, symbol);
    return address ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeDisplayRead(
        JNIEnv *env, jclass clazz, jobject buffer, jint bytes, jintArray info) {
    (void)clazz;
    if (!g_lib) return 0;
    read_fn read = (read_fn)dlsym(g_lib, "inferno_display_read");
    if (!read) return 0;
    void *raw = buffer ? (*env)->GetDirectBufferAddress(env, buffer) : NULL;
    uint32_t fields[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    jint outcome = read(bytes > 0 ? raw : NULL, bytes, fields);
    if (info) {
        (*env)->SetIntArrayRegion(env, info, 0, 8, (const jint *)fields);
    }
    return outcome;
}

JNIEXPORT jlongArray JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeDisplayStats(JNIEnv *env, jclass clazz) {
    (void)clazz;
    jlongArray out = (*env)->NewLongArray(env, 2);
    if (!g_lib || !out) return out;
    stats_fn stats = (stats_fn)dlsym(g_lib, "inferno_display_stats");
    jlong values[2] = {0, 0};
    if (stats) {
        uint64_t counters[2] = {0, 0};
        stats(counters);
        values[0] = (jlong)counters[0];
        values[1] = (jlong)counters[1];
    }
    (*env)->SetLongArrayRegion(env, out, 0, 2, values);
    return out;
}

JNIEXPORT void JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeInputTouch(
        JNIEnv *env, jclass clazz, jint x, jint y, jboolean pressed) {
    (void)env;
    (void)clazz;
    if (!g_lib) return;
    touch_fn touch = (touch_fn)dlsym(g_lib, "inferno_input_touch");
    if (touch) touch(x, y, pressed ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeInputFunctionKey(
        JNIEnv *env, jclass clazz, jint key, jboolean pressed) {
    (void)env;
    (void)clazz;
    if (!g_lib) return;
    key_fn key_fn_p = (key_fn)dlsym(g_lib, "inferno_input_function_key");
    if (key_fn_p) key_fn_p((uint32_t)key, pressed ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeBatterySet(
        JNIEnv *env, jclass clazz, jint percent, jboolean external, jboolean charging) {
    (void)env;
    (void)clazz;
    if (!g_lib) return;
    battery_fn set = (battery_fn)dlsym(g_lib, "inferno_battery_set");
    if (set) set(percent, external ? 1 : 0, charging ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeNetLinkUp(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_lib) return JNI_FALSE;
    link_fn up = (link_fn)dlsym(g_lib, "inferno_net_link_up");
    return up && up() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_tazik_inferno_engine_QemuNative_nativeHapticsRead(
        JNIEnv *env, jclass clazz, jfloatArray frames, jint capacity, jlong wait_ms) {
    (void)clazz;
    if (!g_lib || !frames) return 0;
    haptics_fn read = (haptics_fn)dlsym(g_lib, "inferno_haptics_read");
    if (!read) return 0;
    jsize length = (*env)->GetArrayLength(env, frames);
    int want = capacity * 2;
    if (length < want) want = length;
    jfloat *body = want > 0 ? (*env)->GetFloatArrayElements(env, frames, NULL) : NULL;
    /* Таймаут библиотека ждёт в миллисекундах. */
    int count = body ? read(body, (size_t)(want / 2), (uint32_t)wait_ms) : 0;
    if (body) {
        (*env)->ReleaseFloatArrayElements(env, frames, body, 0);
    }
    return (jint)count;
}

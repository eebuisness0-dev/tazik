**Tazik** is an Android app that runs iOS 14 on real hardware — a port of
[MakrSas/Inferno](https://github.com/MakrSas/Inferno), a QEMU fork with support
for Apple T8030 (iPhone 11).

---

## Features

- **iPhone Mode** — fullscreen iOS on the device screen.
- **Virtual Phone Mode** — floating guest window above other apps.
- **On-device restore** — ramdisk, ASR and patches run on the phone itself, no PC required.
- **Device manager** — multiple virtual iPhones with separate NVMe disk images.
- **IPSW installer** — firmware import with optional SHA1 verification against ipsw.me.
- **RU / EN / UA** interface languages.

---

## Quick start

1. Grab the APK from [Releases](../../releases) and install it.
2. Create a device → **Install iOS** → pick an IPSW (iPhone12,1, iOS 14.x).
3. After the restore finishes, hit **Launch** → **iPhone Mode**.

**Requirements:**

- Android 7.0+ (API 26), Snapdragon 7s Gen 3 or newer recommended
- Up to **20 GB** of free storage for the iOS image

---

## Building

```bash
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected device
```

The APK ships the JNI loader (`libtazikqemu.so`). The guest library
(`libqemu-aarch64-softmmu.so`, machine `t8030`) is built separately from the
Inferno fork:

```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk-r28
./scripts/build-android-deps.sh        # zlib, pixman, libfdt -> android/deps
./scripts/build-inferno-android.sh     # clones MakrSas/Inferno, builds -> android/
```

A prebuilt guest library is bundled at `app/src/main/jniLibs/`, so a fresh
clone builds out of the box. CI workflows (`build.yml`, `inferno-qemu.yml`)
build both parts from source.

---

## License

This project is licensed under the MIT license — see [LICENSE](LICENSE).

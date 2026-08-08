# JARVIS — Local Android AI

A small arm64-only Android app that loads a GGUF model directly from Android storage and runs CPU inference through the vendored upstream `llama.cpp` source.

## Build

1. Open this folder in Android Studio.
2. Install Android SDK 34, NDK 26+, and CMake 3.22.1.
3. Let Gradle sync.
4. Build **app > assembleDebug** or run:

```bash
./gradlew assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Runtime

- Android 10+ / arm64-v8a only.
- Pick a compatible `.gguf` model from the Model screen.
- Battery Saver uses a smaller context and reduces idle orb animation.
- The model is memory-mapped by llama.cpp; the app keeps the selected file descriptor open while the model is loaded.
- Voice is tap-to-talk; there is no background continuous microphone listener.

## Important

This project includes the supplied `llama.cpp` source snapshot under `app/src/main/cpp/llama.cpp` and uses its current C API. It does **not** bundle a model file, because GGUF models can be hundreds of MB or several GB.

The first build of the native engine can take time. A real Android SDK/NDK/CMake environment is required to produce the APK.

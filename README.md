# MyLlama

MyLlama is an Android AI agent built on [`llama.cpp`](https://github.com/ggml-org/llama.cpp). It supports local GGUF models and OpenAI-compatible chat models from one mobile UI.

## Features

- Android 7.0+ (`minSdk 24`) on `arm64-v8a` devices.
- Local `.gguf` import through Android document picker.
- Hugging Face model browser:
  - hot models
  - latest models
  - search
  - GGUF quantization picker
  - file size display when Hugging Face exposes it
  - optional mirror URL and access token
- OpenAI-compatible model configuration through `/v1/chat/completions`.
- Task modes:
  - chat
  - writing
  - translation
  - image prompt generation
- CPU and GPU backend selection.
- Settings for context size, thread count, output token limit, Hugging Face config, and OpenAI-compatible config.
- Backup and restore for conversation history and settings.

`Llama` text models do not generate image pixels. The image mode generates prompts for Stable Diffusion, Flux, or similar diffusion engines. Real bitmap generation needs a separate diffusion runtime and model.

## App Flow

- The app opens to the normal conversation screen.
- The top bar has settings, the app title, and a new-chat button.
- The bottom plus button opens quick actions for new task-specific conversations and model setup.
- Add Model supports both local/Hugging Face GGUF models and OpenAI-compatible models.

## Build

Requirements:

- Android Studio or Android SDK command line tools.
- Android SDK 36. The APK supports Android 7.0+ (`minSdk 24`).
- Android NDK `29.0.13113456`.
- CMake `3.31.6`.
- JDK 17.

Debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

The generated APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The app currently packages `arm64-v8a` only.

## Source Layout

- `app/`: Android UI and model management.
- `lib/`: Kotlin/JNI inference wrapper.
- `src/`, `include/`, `common/`, `ggml/`: vendored `llama.cpp` source used by the native build.
- `cmake/`, `vendor/`: native build support files.

# Nexora

Nexora is an Android-native, offline-first GGUF inference app. It does not ship a default model, download model weights, require an API key, or send prompts to a cloud service.

## Current scope

- Import one GGUF file using Android Storage Access Framework.
- Copy the selected model into app-private storage and validate its GGUF magic header.
- Keep multiple imported models in a local index; the user explicitly chooses the model to load.
- Load one model at a time and release it from memory on request.
- Run local chat and coding prompts through the upstream llama.cpp C API.
- Stream decoded output token pieces into the chat UI.
- Display Android ABI, CPU core count, RAM availability and llama.cpp runtime information.
- Keep vision disabled until an actual multimodal image encoder and preprocessing path are integrated.

## No default model

The initial model list is empty. Nexora contains no bundled weights and performs no automatic model selection or model download. Imported model files remain in app-private storage and are never uploaded.

## Runtime

The native runtime is fetched from the official ggml-org/llama.cpp repository at the fixed commit associated with release v0.8.0 (8642541b255bfb5dc39cd351a178be09013a55d6). This project calls the real llama.cpp model loading, tokenization, decoding, sampling and chat-template APIs. There is no deterministic or scripted response fallback.

The default distributable build targets arm64-v8a and uses llama.cpp's ARM CPU backend with KleidiAI optimizations where supported. Snapdragon and MediaTek devices can run this CPU path. Vulkan is an optional native build configuration and is not advertised as enabled in the default CPU APK. Actual GPU acceleration requires a successful Vulkan build and a compatible device driver.

## Model compatibility and memory

The app validates the GGUF extension and file header, but that does not guarantee every architecture, quantization, context size or model family can run. llama.cpp performs the definitive model load. Split/sharded GGUF imports are currently rejected. Large models may fail to load or exhaust memory; the app checks available RAM conservatively before native allocation. Only one model is active at a time.

The configured inference context is 2048 tokens, capped to the model's reported trained context. A context-full response asks the user to start a new chat rather than silently discarding conversation history.

## Build requirements

- JDK 21
- Android SDK platform 36
- Android NDK 29.0.13113456
- CMake 3.31.6
- Gradle Wrapper 9.3.1

Build and test with:

    ./gradlew clean testDebugUnitTest assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions builds and tests every push and pull request. A successful push to main updates the rolling nightly prerelease with the ARM64 APK. No Actions artifact upload is used.

## Known limitations

- Vision/multimodal image understanding is not active yet.
- Vulkan is opt-in and must be built and tested separately; the default APK uses CPU inference.
- Import currently accepts one GGUF file and does not resolve multi-part model shards.
- GGUF metadata inspection alone is not treated as proof of model compatibility.

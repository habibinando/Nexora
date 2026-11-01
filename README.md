# Nexora

Nexora is an Android-native, offline-first GGUF inference app. It does not ship a default model, download model weights, require an API key, or send prompts to a cloud service.

## Current scope

- Import one GGUF file using Android Storage Access Framework.
- Copy the selected model into app-private storage and validate its GGUF magic header.
- Keep multiple imported models in a local index; the user explicitly chooses the model to load.
- Load one model at a time and release it from memory on request.
- Run local chat and coding prompts through the upstream llama.cpp C API.
- Stream decoded output token pieces into the chat UI with throttled Compose updates.
- Configure the requested output limit from 1 to 100,000 tokens; show the effective native limit after accounting for remaining model context.
- Show live generation status, token count and throughput in a collapsible progress panel; move the completed response into the normal message view.
- Render fenced code and detect common unfenced source-code responses in Coding mode, with per-block and whole-response copy actions.
- Display Android ABI, CPU core count, RAM availability and llama.cpp runtime information.
- Run multimodal image understanding with the upstream llama.cpp MTMD vision encoder when a compatible projector (mmproj) is imported.

## No default model

The initial model list is empty. Nexora contains no bundled weights and performs no automatic model selection or model download. Imported model files remain in app-private storage and are never uploaded.

## Runtime

The native runtime is fetched from the official ggml-org/llama.cpp repository at the fixed commit associated with release v0.8.0 (8642541b255bfb5dc39cd351a178be09013a55d6). This project calls the real llama.cpp model loading, tokenization, decoding, sampling and chat-template APIs. Vision builds the upstream MTMD library and uses its image preprocessing and image-embedding evaluation APIs. There is no deterministic, scripted, RGB-statistic, or fabricated response fallback.

The default distributable build targets arm64-v8a and uses llama.cpp's ARM CPU backend with KleidiAI optimizations where supported. Snapdragon and MediaTek devices can run this CPU path. Vulkan is an optional native build configuration and is not advertised as enabled in the default CPU APK. Actual GPU acceleration requires a successful Vulkan build and a compatible device driver.

## Model compatibility and memory

The app validates GGUF file signatures, but that does not guarantee every architecture, quantization, context size or model family can run. llama.cpp performs the definitive model load. Split/sharded GGUF imports are rejected. For vision, the user must import the matching mmproj GGUF file for the selected model; the MTMD runtime validates whether it actually supports image input. A random projector file is not assumed compatible. Large models may fail to load or exhaust memory; the app checks available RAM conservatively before native allocation. Only one model is active at a time.

The load-time context target adapts to estimated free-memory headroom (4,096, 8,192, 16,384, or 32,768 tokens) and is capped to the GGUF model's reported trained context. Requested output can be configured up to 100,000 tokens, but the native runtime sets the effective generation limit to the remaining context after prompt evaluation. Therefore, 100,000 is an application setting ceiling, not a promise that every model can emit 100,000 tokens in one turn. The UI reports when the remaining context limits a request; it never silently drops earlier conversation history.

## Build requirements

- JDK 21
- Android SDK platform 36
- Android NDK 29.0.13113456
- CMake 3.31.6
- Gradle Wrapper 9.3.1

Build and test both variants with:

    ./gradlew clean testDebugUnitTest assembleDebug assembleRelease

APK output:

    app/build/outputs/apk/debug/app-debug.apk
    app/build/outputs/apk/release/app-release.apk

The release variant enables R8 code/resource shrinking and compiles the native CMake graph in Release mode. JNI keeps for `com.nexora.ai.NativeLlama` preserve the class and native method names required by the C++ symbol exports.

## Release signing

For a dedicated release certificate, provide these environment variables:

- `NEXORA_RELEASE_STORE_FILE`
- `NEXORA_RELEASE_STORE_PASSWORD`
- `NEXORA_RELEASE_KEY_ALIAS`
- `NEXORA_RELEASE_KEY_PASSWORD`

GitHub Actions accepts the matching repository secrets plus `NEXORA_RELEASE_KEYSTORE_BASE64`, decodes the keystore into the runner's temporary directory, and passes its path to Gradle. When these values are absent, the release build falls back to the Android debug signing certificate so local and CI release APKs remain installable. That fallback is for testing, not Play Store/production distribution; configure a private signing key before production release.

For PR #5, the verified debug and release APKs are published as assets on the rolling prerelease [pr-5-latest](https://github.com/habibinando/Nexora/releases/tag/pr-5-latest). The workflow uses GitHub Releases rather than Actions artifact uploads, so it does not consume Actions artifact storage. The `main` workflow-dispatch nightly also publishes the release APK.

## Known limitations

- Vision requires a compatible multimodal GGUF model and its matching mmproj file. Text-only models remain text-only.
- Vulkan is opt-in and must be built and tested separately; the default APK uses CPU inference.
- Import currently accepts one GGUF file and does not resolve multi-part model shards.
- GGUF metadata inspection alone is not treated as proof of model compatibility.

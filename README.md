# Llama Launcher

**A one-tap Android front end for [llama.cpp](https://github.com/ggml-org/llama.cpp) running in [Termux](https://github.com/termux/termux-app).**
Pick a model, adjust the parameters, start `llama-server` and chat in the built-in web UI, all in one app.

[Deutsche Version](README.de.md)

> **What works on the tablet and what doesn't?** Measurements, recommended settings, GPU crashes, dead ends (OpenCL, MTP) and the complete Termux/llama.cpp setup with build script are documented in the wiki: **[Termux and Building llama.cpp](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303)**

Llama Launcher does not bundle llama.cpp. The server keeps running in Termux, where llama.cpp can use the GPU via Vulkan (Mesa Turnip on Adreno). The app sends Termux the commands through Termux's official [RUN_COMMAND interface](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) and shows the llama.cpp web UI in a WebView.

Built and tested on a Honor MagicPad 3 Pro (Snapdragon 8 Elite Gen 5, Adreno 840, Android 16).

## Features

- **Model list** from your models folder (one subfolder level). mmproj files, split parts 2..n, MTP drafters and LoRA adapters are recognised and not listed as models.
- **Settings remembered per model.** Selecting a model loads the values you last started it with.
- **All important llama-server options** with short explanations:
  context size (slider, 1K–64K), KV cache type, micro-batch (`-ub`), prompt cache (`--cache-ram`), context checkpoints, CPU threads, load mode, chat template (Jinja / `--no-jinja`), reasoning (`--reasoning auto|off|on`), log verbosity, plus a free field for extra parameters.
- **MTP drafter** (speculative decoding): `mtp-*.gguf` files are offered automatically, with draft tokens and GPU/CPU choice.
- **LoRA adapters** with strength 1.00–2.50, detected by their GGUF header (`adapter.type`), not by file name.
- **Vision:** a matching `mmproj-*.gguf` next to the model is loaded automatically.
- **Live command line** preview. Options that equal the llama.cpp default are left out.
- **Status** (starting / loading model / ready) from llama-server's `/health` endpoint.
- **Live log** of llama-server. If the server exits unexpectedly, the app shows the exit code and the last log lines.
- **Built-in chat:** the llama.cpp web UI opens automatically once the model is loaded. Image upload works.
- **Start / stop** from the app. Stop also ends a server started from a shell script on the same port.
- Optional **autostart** with the last settings when the app opens.
- Detects which options the installed llama.cpp build supports (`--load-mode`, `--no-jinja`, `--spec-type`, `--lora-scaled`, `--reasoning`) and falls back for older builds.
- **English and German UI**, chosen automatically from the system language or set manually in the settings.
- Plain Java and the Android SDK only, **no third-party libraries**. The UI is built in code (no layout XML).

## Requirements

| What | Details |
|---|---|
| Android | 11 or newer (minSdk 30), arm64 |
| Termux | From [GitHub](https://github.com/termux/termux-app/releases) or F-Droid, **not** the Play Store version |
| llama.cpp | Built in Termux, by default expected at `~/llama.cpp/build-vk/bin/llama-server` (path can be changed in the app) |
| Termux storage access | `termux-setup-storage` (Termux writes the server log to shared storage) |
| Termux setting | `allow-external-apps = true` in `~/.termux/termux.properties` |
| App permissions | "Run commands in Termux environment" and "All files access" (to list `.gguf` files and read the log) |
| Models | `.gguf` files in `/storage/emulated/0/Models` (folder can be changed in the app) |

Termux:Widget and Termux:API are **not** needed for the app.

## Setup

1. **Build llama.cpp in Termux** with Vulkan. These are the commands used on the MagicPad 3 Pro (Adreno 840); the fixed CPU flags avoid a build failure with automatic SVE/SME detection there:

   ```bash
   pkg install git cmake clang shaderc vulkan-headers spirv-headers vulkan-tools
   vulkaninfo --summary   # must show the Mesa Turnip driver; if not: pkg search mesa-vulkan-icd
   git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/llama.cpp
   cmake -S ~/llama.cpp -B ~/llama.cpp/build-vk -DCMAKE_BUILD_TYPE=Release \
         -DGGML_VULKAN=ON -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm
   cmake --build ~/llama.cpp/build-vk -j8 --target llama-server
   ```

   A complete build/update script (including an optional patch that gives the Android UI GPU priority) is in the [wiki](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303).

2. **Allow Termux to accept commands from apps** (once, in Termux):

   ```bash
   termux-setup-storage
   mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings
   ```

3. **Build and install the app.** Open the project in Android Studio or in [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) on the device and build the debug APK.
   The Gradle files match Code on the Go 26.40–26.42 (Android Gradle Plugin 9.3.1, Gradle 9.6.1, compileSdk 36).

4. **Open the app.** In the "Setup" box at the top:
   - grant "Run commands in Termux environment"
   - allow "All files access"
   - tap "Test": it must report that Termux and llama-server were found

5. Pick a model and tap **Start**. The app switches to the log, then to the chat once the model is loaded.

## How it works

```
Llama Launcher (this app)                     Termux
-------------------------                     ------
settings UI  --RUN_COMMAND (bash -c ...)-->   llama-server ... > shared-storage log
             <--PendingIntent result (exit)-- (task ends when the server exits)
status       --HTTP GET /health----------->   llama-server :8123
chat WebView --http://127.0.0.1:8123------>   llama.cpp web UI
log view     --reads log file (all files access)
```

- **Start:** a background Termux task runs `llama-server` with the chosen options and keeps running as long as the server runs. `termux-wake-lock` is held meanwhile. When the server exits, Termux returns the exit code to the app.
- **Stop:** `pkill -f "^[[:graph:]]*llama-server .*--port <port>"`, then `kill -9` after 10 s if needed.
- **Test:** checks that `llama-server` exists and which options its `--help` lists.

| File | Purpose |
|---|---|
| `MainActivity.java` | UI, status polling, start/stop, log, WebView |
| `TermuxBridge.java` | RUN_COMMAND intent and the shell scripts for start, stop and test |
| `TermuxResultReceiver.java` | Receives the results of Termux commands |
| `ServerSettings.java` | Per-model settings and building the llama-server command line |
| `ModelScanner.java` | Finds models, MTP drafters, LoRA adapters and mmproj files |
| `Prefs.java` | Stored settings |

## Good to know

- Chats in the built-in web UI are stored in the app's WebView, separate from your browser.
- Downloads (e.g. chat export) don't work in the built-in WebView. Use "Web UI in browser" in the settings for that.
- The log is written to `Documents/LlamaLauncher/llama-server.log`.
- The UI follows the system language (German on German systems, English everywhere else). Under "App" > "Language" in the settings you can set German or English manually.
- The default micro-batch is `-ub 32`: prefill is a little slower, but the UI lags much less at full context.

## Troubleshooting

- **"Termux rejected the command":** `allow-external-apps = true` is missing, or `termux-reload-settings` was not run.
- **llama-server not found:** check the path under "Paths".
- **No models listed:** "All files access" is not granted, or the models folder path is wrong.
- **Build fails with `AAPT2 ... Daemon startup failed` in Code on the Go:** check whether the IDE's own `aapt2` is an arm64 binary:
  `od -A d -t x1 -N 20 /data/data/com.itsaky.androidide/files/home/android-sdk/build-tools/36.0.0/aapt2`.
  Bytes 19–20 must be `b7 00` (arm64). `3e 00` means an x86-64 binary, which is a problem with the IDE installation, not with this project.

## Links

- [Wiki: Termux and Building llama.cpp](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303) – full setup, build script, measurements, what works and what doesn't
- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- [Termux RUN_COMMAND intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

## License

Copyright 2026 Inteks. Licensed under the [Apache License, Version 2.0](LICENSE).

# Llama Launcher

**Android-Oberfläche für [llama.cpp](https://github.com/ggml-org/llama.cpp) in [Termux](https://github.com/termux/termux-app), per Fingertipp.**
Modell wählen, Parameter einstellen, `llama-server` starten und in der eingebauten Web-UI chatten, alles in einer App.

[English version](README.md)

> **Was geht auf dem Tablet und was nicht?** Messwerte, empfohlene Einstellungen, GPU-Abstürze, Sackgassen (OpenCL, MTP) und die komplette Einrichtung von Termux und llama.cpp mit Bauskript stehen im Wiki (Englisch, mit Link zur deutschen Fassung): **[Termux and Building llama.cpp](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303)**

Llama Launcher bringt kein eigenes llama.cpp mit. Der Server läuft weiter in Termux, wo llama.cpp die GPU über Vulkan nutzen kann (Mesa Turnip auf Adreno). Die App schickt Termux die Befehle über dessen offizielle [RUN_COMMAND-Schnittstelle](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) und zeigt die Web-UI von llama.cpp in einem WebView.

Gebaut und getestet auf einem Honor MagicPad 3 Pro (Snapdragon 8 Elite Gen 5, Adreno 840, Android 16).

## Funktionen

- **Modellliste** aus dem Modellordner (eine Ebene Unterordner). mmproj-Dateien, Teil 2..n gesplitteter Modelle, MTP-Drafter und LoRA-Adapter werden erkannt und nicht als Modell angeboten.
- **Einstellungen pro Modell gespeichert.** Bei der Modellwahl werden die Werte des letzten Starts geladen.
- **Alle wichtigen Optionen von llama-server** mit kurzer Erklärung:
  Kontextlänge (Schieberegler 1K–64K), KV-Cache, Micro-Batch (`-ub`), Prompt-Cache (`--cache-ram`), Kontext-Checkpoints, CPU-Threads, Load-Mode, Chat-Template (Jinja / `--no-jinja`), Reasoning (`--reasoning auto|off|on`), Log-Detailgrad und ein freies Feld für weitere Parameter.
- **MTP-Drafter** (Speculative Decoding): `mtp-*.gguf` wird automatisch angeboten, mit Draft-Tokens und Wahl GPU/CPU.
- **LoRA-Adapter** mit Stärke 1.00–2.50, erkannt am GGUF-Kopf (`adapter.type`), nicht am Dateinamen.
- **Vision:** Ein passendes `mmproj-*.gguf` neben dem Modell wird automatisch mitgeladen.
- **Kommandozeile live** sichtbar. Optionen mit llama.cpp-Standardwert werden weggelassen.
- **Status** (startet / lädt Modell / bereit) über den Endpunkt `/health` von llama-server.
- **Live-Log** von llama-server. Beendet sich der Server unerwartet, zeigt die App Exit-Code und letzte Log-Zeilen.
- **Eingebauter Chat:** Die Web-UI von llama.cpp öffnet sich automatisch, sobald das Modell geladen ist. Bilder anhängen geht.
- **Starten / Stoppen** aus der App. Stoppen beendet auch einen Server, der per Shell-Skript auf demselben Port gestartet wurde.
- Optionaler **Autostart** mit den letzten Einstellungen beim Öffnen der App.
- Erkennt, welche Optionen der installierte llama.cpp-Build kennt (`--load-mode`, `--no-jinja`, `--spec-type`, `--lora-scaled`, `--reasoning`), und weicht bei älteren Builds aus.
- Nur Java und Android-SDK, **keine Fremdbibliotheken**. Die Oberfläche wird im Code aufgebaut (keine Layout-XML).

## Voraussetzungen

| Was | Details |
|---|---|
| Android | ab 11 (minSdk 30), arm64 |
| Termux | von [GitHub](https://github.com/termux/termux-app/releases) oder F-Droid, **nicht** die Play-Store-Version |
| llama.cpp | in Termux gebaut, standardmäßig unter `~/llama.cpp/build-vk/bin/llama-server` erwartet (Pfad in der App änderbar) |
| Speicherzugriff von Termux | `termux-setup-storage` (Termux schreibt das Server-Log in den gemeinsamen Speicher) |
| Termux-Einstellung | `allow-external-apps = true` in `~/.termux/termux.properties` |
| Berechtigungen der App | „Befehle in Termux ausführen“ und „Zugriff auf alle Dateien“ (für die `.gguf`-Liste und das Log) |
| Modelle | `.gguf`-Dateien in `/storage/emulated/0/Models` (Ordner in der App änderbar) |

Termux:Widget und Termux:API braucht die App **nicht**.

## Einrichtung

1. **llama.cpp in Termux bauen**, mit Vulkan. So auf dem MagicPad 3 Pro (Adreno 840); die festen CPU-Flags verhindern dort einen Bauabbruch durch die automatische SVE/SME-Erkennung:

   ```bash
   pkg install git cmake clang shaderc vulkan-headers spirv-headers vulkan-tools
   vulkaninfo --summary   # muss den Mesa-Turnip-Treiber zeigen; sonst: pkg search mesa-vulkan-icd
   git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/llama.cpp
   cmake -S ~/llama.cpp -B ~/llama.cpp/build-vk -DCMAKE_BUILD_TYPE=Release \
         -DGGML_VULKAN=ON -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm
   cmake --build ~/llama.cpp/build-vk -j8 --target llama-server
   ```

   Ein komplettes Bau- und Update-Skript (mit optionalem Patch, der der Android-UI bei der GPU Vorrang gibt) steht im [Wiki](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303).

2. **Termux Befehle von Apps erlauben** (einmalig, in Termux):

   ```bash
   termux-setup-storage
   mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings
   ```

3. **App bauen und installieren.** Projekt in Android Studio oder in [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) auf dem Gerät öffnen und die Debug-APK bauen.
   Die Gradle-Dateien passen zu Code on the Go 26.40–26.42 (Android Gradle Plugin 9.3.1, Gradle 9.6.1, compileSdk 36).

4. **App öffnen.** Oben im Kasten „Einrichtung“:
   - „Befehle in Termux ausführen“ erteilen
   - „Zugriff auf alle Dateien“ erlauben
   - „Testen“ antippen: muss melden, dass Termux und llama-server gefunden wurden

5. Modell wählen und **Starten** antippen. Die App wechselt ins Log und, sobald das Modell geladen ist, in den Chat.

## So funktioniert es

```
Llama Launcher (diese App)                    Termux
--------------------------                    ------
Einstellungen --RUN_COMMAND (bash -c ...)-->  llama-server ... > Log im gemeinsamen Speicher
              <--PendingIntent (Exit-Code)--  (Aufgabe endet mit dem Server)
Status        --HTTP GET /health----------->  llama-server :8123
Chat-WebView  --http://127.0.0.1:8123------>  Web-UI von llama.cpp
Log-Ansicht   --liest die Log-Datei (Zugriff auf alle Dateien)
```

- **Start:** Eine Hintergrund-Aufgabe in Termux startet `llama-server` mit den gewählten Optionen und läuft, solange der Server läuft. Währenddessen hält sie `termux-wake-lock`. Endet der Server, meldet Termux der App den Exit-Code.
- **Stopp:** `pkill -f "^[[:graph:]]*llama-server .*--port <Port>"`, nach 10 s notfalls `kill -9`.
- **Test:** prüft, ob `llama-server` vorhanden ist und welche Optionen `--help` nennt.

| Datei | Zweck |
|---|---|
| `MainActivity.java` | Oberfläche, Status-Abfrage, Start/Stopp, Log, WebView |
| `TermuxBridge.java` | RUN_COMMAND-Intent und die Shell-Skripte für Start, Stopp und Test |
| `TermuxResultReceiver.java` | Empfängt die Ergebnisse der Termux-Befehle |
| `ServerSettings.java` | Einstellungen pro Modell und Aufbau der Kommandozeile |
| `ModelScanner.java` | Findet Modelle, MTP-Drafter, LoRA-Adapter und mmproj-Dateien |
| `Prefs.java` | Gespeicherte Einstellungen |

## Gut zu wissen

- Die Chats der eingebauten Web-UI liegen im WebView der App, getrennt vom Browser.
- Herunterladen (z. B. Chat-Export) geht im eingebauten WebView nicht. Dafür in den Einstellungen „Web-UI im Browser“ nutzen.
- Das Log liegt unter `Documents/LlamaLauncher/llama-server.log`.

## Fehlersuche

- **„Termux hat den Befehl abgelehnt“:** `allow-external-apps = true` fehlt oder `termux-reload-settings` wurde nicht ausgeführt.
- **llama-server nicht gefunden:** Pfad unter „Pfade“ prüfen.
- **Keine Modelle in der Liste:** „Zugriff auf alle Dateien“ fehlt oder der Modellordner stimmt nicht.
- **Bau in Code on the Go scheitert mit `AAPT2 ... Daemon startup failed`:** Prüfen, ob das `aapt2` der IDE eine arm64-Datei ist:
  `od -A d -t x1 -N 20 /data/data/com.itsaky.androidide/files/home/android-sdk/build-tools/36.0.0/aapt2`.
  Byte 19–20 müssen `b7 00` (arm64) sein. `3e 00` heißt x86-64 – dann liegt das Problem bei der Installation der IDE, nicht an diesem Projekt.

## Links

- [Wiki: Termux and Building llama.cpp](https://concrete-bobcat-e1f.notion.site/Termux-and-Building-llama-cpp-3ec54030382a817983dbdd7ea52c2303) – komplette Einrichtung, Bauskript, Messwerte, was geht und was nicht
- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- [Termux RUN_COMMAND-Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

## Lizenz

Copyright 2026 Inteks. Lizenziert unter der [Apache License, Version 2.0](LICENSE).

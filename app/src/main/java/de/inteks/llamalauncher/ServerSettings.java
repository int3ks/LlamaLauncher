package de.inteks.llamalauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer einen Server-Start (pro Modell gespeichert) und der Aufbau der Kommandozeile.
 * Gleiche Regeln wie llama-start.sh: Parameter, die dem llama.cpp-Standard entsprechen,
 * werden nicht uebergeben.
 */
final class ServerSettings {

    // llama.cpp-Standardwerte (werden nicht uebergeben)
    static final String LLAMA_DEF_KV = "f16";
    static final int LLAMA_DEF_UB = 512;
    static final String LLAMA_DEF_LOADMODE = "auto";
    static final int LLAMA_DEF_VERBOSITY = 3;
    static final int LLAMA_DEF_CACHE_RAM = 8192;
    static final int LLAMA_DEF_CHECKPOINTS = 32;
    static final int LLAMA_DEF_DRAFT_N = 3;

    // Auswahllisten
    static final String[] KV_TYPES = {"f16", "q8_0", "q5_0", "q4_0"};
    static final String[] KV_LABELS = {
            "f16 - keine Quantisierung, doppelter Speicher",
            "q8_0 - praktisch kein Verlust, halber Speicher (empfohlen)",
            "q5_0 - kleiner, Verlust bei langen Chats moeglich",
            "q4_0 - am kleinsten, Verlust bei langen Chats moeglich"};
    static final int[] UB_VALUES = {32, 64, 128, 256, 512};
    static final String[] UB_LABELS = {
            "32 - stabil",
            "64 - stabil, guter Kompromiss (empfohlen)",
            "128 - stabil, schnellerer Prefill",
            "256 - nicht getestet",
            "512 - llama.cpp-Standard, auf Adreno 840 + Gemma 4 + FA: GPU-Absturz"};
    static final String[] LOAD_MODES = {"auto", "mmap", "mmap+mlock", "mlock", "dio", "none"};
    static final String[] LOAD_LABELS = {
            "auto - llama.cpp-Standard",
            "mmap - Modelldatei zusaetzlich gemappt",
            "mmap+mlock - ohne Root wirkungslos",
            "mlock - ohne Root wirkungslos",
            "dio - Direct-IO beim Laden",
            "none - direkt in die GPU-Buffer (empfohlen)"};
    static final String[] TEMPLATE_LABELS = {
            "Jinja - Template aus dem Modell (Standard, noetig fuer Gemma 4, Tool-Calls, Thinking)",
            "--no-jinja - fest eingebaute llama.cpp-Templates (Gemma 4 geht damit nicht)"};
    static final String[] REASONING_VALUES = {"auto", "off", "on"};
    static final String[] REASONING_LABELS = {
            "auto - Standard, Thinking an, wenn das Template es kann",
            "aus (--reasoning off) - wenn die Web-UI es nicht abschaltet",
            "an (--reasoning on)"};
    static final String[] LORA_SCALES = {"1.00", "1.25", "1.50", "1.75", "2.00", "2.25", "2.50"};
    static final String[] VERBOSITY_LABELS = {"1", "2", "3 - llama.cpp-Standard", "4 - sehr ausfuehrlich"};

    // Vorgaben dieser App (wie in llama-start.sh)
    int ctx = 8192;
    String kv = "q8_0";
    int ub = 64;
    int cacheRam = 2048;
    int threads = 6;
    String loadMode = "none";
    int checkpoints = 4;
    int verbosity = 4;
    boolean noJinja = false;
    String reasoning = "auto";
    String mtpPath = "";     // leer = aus
    int draftN = LLAMA_DEF_DRAFT_N;
    boolean draftCpu = false;
    String loraPath = "";    // leer = keine
    String loraScale = "1.75";
    String extraArgs = "";

    /** Welche Optionen der llama.cpp-Build kennt (aus "llama-server --help"). */
    static final class Features {
        boolean known = false;
        boolean loadMode = true;
        boolean noJinja = true;
        boolean specType = true;
        boolean loraScaled = true;
        boolean reasoning = true;

        static Features fromCheckOutput(String stdout) {
            Features f = new Features();
            if (stdout == null || !stdout.contains("SERVER_OK")) return f;
            f.known = true;
            f.loadMode = stdout.contains("FLAG --load-mode");
            f.noJinja = stdout.contains("FLAG --no-jinja");
            f.specType = stdout.contains("FLAG --spec-type");
            f.loraScaled = stdout.contains("FLAG --lora-scaled");
            f.reasoning = stdout.contains("FLAG --reasoning");
            return f;
        }

        String encode() {
            return (known ? "1" : "0") + (loadMode ? "1" : "0") + (noJinja ? "1" : "0")
                    + (specType ? "1" : "0") + (loraScaled ? "1" : "0") + (reasoning ? "1" : "0");
        }

        static Features decode(String s) {
            Features f = new Features();
            if (s == null || s.length() != 6 || s.charAt(0) != '1') return f;
            f.known = true;
            f.loadMode = s.charAt(1) == '1';
            f.noJinja = s.charAt(2) == '1';
            f.specType = s.charAt(3) == '1';
            f.loraScaled = s.charAt(4) == '1';
            f.reasoning = s.charAt(5) == '1';
            return f;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Speichern / Laden
    // ---------------------------------------------------------------------------------------

    String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ctx", ctx);
            o.put("kv", kv);
            o.put("ub", ub);
            o.put("cacheRam", cacheRam);
            o.put("threads", threads);
            o.put("loadMode", loadMode);
            o.put("checkpoints", checkpoints);
            o.put("verbosity", verbosity);
            o.put("noJinja", noJinja);
            o.put("reasoning", reasoning);
            o.put("mtpPath", mtpPath);
            o.put("draftN", draftN);
            o.put("draftCpu", draftCpu);
            o.put("loraPath", loraPath);
            o.put("loraScale", loraScale);
            o.put("extraArgs", extraArgs);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    static ServerSettings fromJson(String json) {
        ServerSettings s = new ServerSettings();
        if (json == null || json.isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.ctx = o.optInt("ctx", s.ctx);
            s.kv = o.optString("kv", s.kv);
            s.ub = o.optInt("ub", s.ub);
            s.cacheRam = o.optInt("cacheRam", s.cacheRam);
            s.threads = o.optInt("threads", s.threads);
            s.loadMode = o.optString("loadMode", s.loadMode);
            s.checkpoints = o.optInt("checkpoints", s.checkpoints);
            s.verbosity = o.optInt("verbosity", s.verbosity);
            s.noJinja = o.optBoolean("noJinja", s.noJinja);
            s.reasoning = o.optString("reasoning", s.reasoning);
            s.mtpPath = o.optString("mtpPath", s.mtpPath);
            s.draftN = o.optInt("draftN", s.draftN);
            s.draftCpu = o.optBoolean("draftCpu", s.draftCpu);
            s.loraPath = o.optString("loraPath", s.loraPath);
            s.loraScale = o.optString("loraScale", s.loraScale);
            s.extraArgs = o.optString("extraArgs", s.extraArgs);
        } catch (JSONException ignored) {
        }
        return s;
    }

    /** Kurze Zusammenfassung wie in der Statuszeile von llama-start.sh. */
    String summary() {
        StringBuilder b = new StringBuilder();
        b.append("ctx ").append(ctx).append(", KV ").append(kv).append(", ub ").append(ub)
                .append(", cache ").append(cacheRam).append(" MiB, ").append(threads).append(" Threads, load ")
                .append(loadMode).append(", ").append(noJinja ? "no-jinja" : "jinja");
        if (!noJinja && !"auto".equals(reasoning)) b.append(", reasoning ").append(reasoning);
        if (!mtpPath.isEmpty()) {
            b.append(", MTP ").append(new java.io.File(mtpPath).getName()).append(" n=").append(draftN)
                    .append(draftCpu ? " CPU" : " GPU");
        }
        if (!loraPath.isEmpty()) {
            b.append(", LoRA ").append(new java.io.File(loraPath).getName()).append(" x").append(loraScale);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Kommandozeile
    // ---------------------------------------------------------------------------------------

    /** Ergebnis von {@link #buildArgs}: Argumentliste oder Fehlermeldung. */
    static final class Built {
        final List<String> args = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        String error;
    }

    /**
     * Baut die Kommandozeile in derselben Reihenfolge wie llama-start.sh:
     * -m MODELL [--mmproj] [MTP] [LoRA] -fa on -c CTX [KV] -t [-ub] [--cache-ram] [--ctx-checkpoints]
     * [Load-Mode] [Jinja] [Reasoning] --port P [-lv] [zusaetzliche Parameter]
     */
    Built buildArgs(String serverPath, String modelPath, String mmprojPath, int port, Features f) {
        Built r = new Built();
        List<String> a = r.args;
        a.add(serverPath);
        a.add("-m");
        a.add(modelPath);
        if (mmprojPath != null) {
            a.add("--mmproj");
            a.add(mmprojPath);
        }

        // MTP-Drafter
        if (!mtpPath.isEmpty()) {
            if (!f.specType) {
                r.notes.add("Build kennt kein --spec-type - MTP wird uebersprungen.");
            } else {
                a.add("--model-draft");
                a.add(mtpPath);
                a.add("--spec-type");
                a.add("draft-mtp");
                if (draftN != LLAMA_DEF_DRAFT_N) {
                    a.add("--spec-draft-n-max");
                    a.add(String.valueOf(draftN));
                }
                if (draftCpu) {
                    a.add("-ngld");
                    a.add("0");
                }
            }
        }

        // LoRA
        if (!loraPath.isEmpty()) {
            if (!f.loraScaled) {
                r.notes.add("Build kennt kein --lora-scaled - LoRA wird uebersprungen.");
            } else if (loraPath.contains(",")) {
                r.error = "Der LoRA-Pfad enthaelt ein Komma - das kann llama.cpp nicht verarbeiten.";
                return r;
            } else if ("1.00".equals(loraScale)) {
                a.add("--lora");
                a.add(loraPath);
            } else if (loraPath.contains(":")) {
                r.error = "Der LoRA-Pfad enthaelt einen Doppelpunkt - mit Staerke geht das nicht.";
                return r;
            } else {
                a.add("--lora-scaled");
                a.add(loraPath + ":" + loraScale);
            }
        }

        a.add("-fa");
        a.add("on");
        a.add("-c");
        a.add(String.valueOf(ctx));
        if (!LLAMA_DEF_KV.equals(kv)) {
            a.add("-ctk");
            a.add(kv);
            a.add("-ctv");
            a.add(kv);
        }
        a.add("-t");
        a.add(String.valueOf(threads));
        if (ub != LLAMA_DEF_UB) {
            a.add("-ub");
            a.add(String.valueOf(ub));
        }
        if (cacheRam != LLAMA_DEF_CACHE_RAM) {
            a.add("--cache-ram");
            a.add(String.valueOf(cacheRam));
        }
        if (checkpoints != LLAMA_DEF_CHECKPOINTS) {
            a.add("--ctx-checkpoints");
            a.add(String.valueOf(checkpoints));
        }

        // Load-Mode (aeltere Builds: alte Flags)
        if (f.loadMode) {
            if (!LLAMA_DEF_LOADMODE.equals(loadMode)) {
                a.add("--load-mode");
                a.add(loadMode);
            }
        } else {
            switch (loadMode) {
                case "mmap": a.add("--mmap"); break;
                case "mmap+mlock": a.add("--mlock"); break;
                case "mlock": a.add("--no-mmap"); a.add("--mlock"); break;
                case "dio": a.add("--direct-io"); break;
                case "none": a.add("--no-mmap"); break;
                default: break;
            }
        }

        // Chat-Template: aktuelle Builds haben Jinja als Standard, aeltere nicht
        if (f.noJinja) {
            if (noJinja) a.add("--no-jinja");
        } else {
            if (!noJinja) a.add("--jinja");
        }

        // Reasoning (wirkt nur mit Jinja)
        if (!noJinja && !"auto".equals(reasoning)) {
            if (f.reasoning) {
                a.add("--reasoning");
                a.add(reasoning);
            } else {
                r.notes.add("Build kennt kein --reasoning - bleibt beim Standard.");
            }
        }

        a.add("--port");
        a.add(String.valueOf(port));
        if (verbosity != LLAMA_DEF_VERBOSITY) {
            a.add("-lv");
            a.add(String.valueOf(verbosity));
        }

        a.addAll(splitArgs(extraArgs));
        return r;
    }

    /** Zerlegt zusaetzliche Parameter an Leerzeichen; "..." und '...' halten Woerter zusammen. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
            } else {
                cur.append(c);
                inWord = true;
            }
        }
        if (inWord) out.add(cur.toString());
        return out;
    }

    /** Fuer die Anzeige: Argumente wie in der Shell (mit Anfuehrungszeichen, wo noetig). */
    static String toShellLine(List<String> args) {
        StringBuilder b = new StringBuilder();
        for (String s : args) {
            if (b.length() > 0) b.append(' ');
            if (s.isEmpty() || !s.matches("[A-Za-z0-9_./:=+,@%-]+")) {
                b.append('\'').append(s.replace("'", "'\\''")).append('\'');
            } else {
                b.append(s);
            }
        }
        return b.toString();
    }

    /** "16k" -> 16384, "8192" -> 8192; ungueltig -> -1 */
    static int parseCtx(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            int v;
            if (s.endsWith("k")) v = Integer.parseInt(s.substring(0, s.length() - 1)) * 1024;
            else v = Integer.parseInt(s);
            return v >= 512 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

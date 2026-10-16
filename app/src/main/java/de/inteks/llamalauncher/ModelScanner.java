package de.inteks.llamalauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durchsucht den Modellordner (und eine Ebene Unterordner) wie llama-start.sh:
 *  - Modelle: *.gguf ohne mmproj*, ohne mtp-*, ohne Teil 2..n gesplitteter Modelle, ohne LoRA-Adapter
 *  - MTP-Drafter: mtp-*.gguf
 *  - LoRA-Adapter: erkannt am GGUF-Kopf (Eintrag "adapter.type"), nicht am Namen
 */
final class ModelScanner {

    private static final Pattern SPLIT_TAIL = Pattern.compile("-0*([2-9]|[1-9][0-9]+)-of-[0-9]+\\.gguf$");
    private static final Pattern SIZE_TAG = Pattern.compile("(?i)(^|[-_])e?[0-9]+b([-_.]|$)");
    private static final Pattern SIZE_TAG_CORE = Pattern.compile("(?i)e?[0-9]+b");
    private static final byte[] LORA_MARK = "adapter.type".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 262144;

    static final class Result {
        final List<File> models = new ArrayList<>();
        final List<File> mtps = new ArrayList<>();
        final List<File> loras = new ArrayList<>();
        String error;
    }

    private ModelScanner() {
    }

    static Result scan(File dir) {
        Result r = new Result();
        if (!dir.isDirectory()) {
            r.error = "Modellordner nicht gefunden: " + dir.getPath();
            return r;
        }
        List<File> files = new ArrayList<>();
        collect(dir, files, 0);
        if (files.isEmpty() && dir.listFiles() == null) {
            r.error = "Kein Zugriff auf " + dir.getPath() + " (Dateizugriff erlaubt?)";
            return r;
        }
        files.sort(Comparator.comparing(File::getPath));
        for (File f : files) {
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".gguf")) continue;
            if (lower.startsWith("mmproj")) continue;
            if (lower.startsWith("mtp-")) {
                r.mtps.add(f);
                continue;
            }
            if (SPLIT_TAIL.matcher(name).find()) continue;
            if (isLora(f)) r.loras.add(f);
            else r.models.add(f);
        }
        return r;
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isFile()) out.add(f);
            else if (f.isDirectory() && depth == 0) collect(f, out, depth + 1);
        }
    }

    /** LoRA-Adapter haben im GGUF-Kopf den Eintrag "adapter.type" (llama.cpp verlangt ihn). */
    static boolean isLora(File f) {
        byte[] buf = new byte[HEADER_BYTES];
        int n = 0;
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        } catch (IOException e) {
            return false;
        }
        outer:
        for (int i = 0; i + LORA_MARK.length <= n; i++) {
            for (int j = 0; j < LORA_MARK.length; j++) {
                if (buf[i + j] != LORA_MARK[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Passendes mmproj im selben Ordner: Name-Q8_K_P.gguf -> mmproj-Name*.gguf */
    static File findMmproj(File model) {
        File dir = model.getParentFile();
        if (dir == null) return null;
        String base = model.getName();
        if (base.endsWith(".gguf")) base = base.substring(0, base.length() - 5);
        int dash = base.lastIndexOf('-');
        String stem = dash >= 0 ? base.substring(0, dash) : base;
        String prefix = "mmproj-" + stem;
        File[] list = dir.listFiles();
        if (list == null) return null;
        Arrays.sort(list, Comparator.comparing(File::getName));
        for (File f : list) {
            String n = f.getName();
            if (f.isFile() && n.startsWith(prefix) && n.endsWith(".gguf")) return f;
        }
        return null;
    }

    /** Groessenangabe im Modellnamen, z. B. "12b" oder "e4b"; null, wenn keine. */
    static String sizeTag(String modelName) {
        Matcher m = SIZE_TAG.matcher(modelName);
        if (!m.find()) return null;
        Matcher c = SIZE_TAG_CORE.matcher(m.group());
        return c.find() ? c.group().toLowerCase(Locale.ROOT) : null;
    }

    /** MTP-Drafter mit derselben Groessenangabe wie das Modell? */
    static boolean mtpMatches(String mtpName, String sizeTag) {
        if (sizeTag == null) return false;
        Pattern p = Pattern.compile("(^|[-_])" + Pattern.quote(sizeTag) + "([-_.]|$)");
        return p.matcher(mtpName.toLowerCase(Locale.ROOT)).find();
    }

    /** LoRA passt vermutlich, wenn die ersten zwei Namensteile gleich sind (z. B. LFM2.5-24B). */
    static boolean loraMatches(String loraName, String modelName) {
        if (!loraName.contains("-")) return false;
        String[] parts = loraName.toLowerCase(Locale.ROOT).split("-", -1);
        String pre = parts[0] + "-" + parts[1];
        return modelName.toLowerCase(Locale.ROOT).startsWith(pre);
    }

    /** Wie "du -h": 7.6G, 945M, 24M */
    static String humanSize(long bytes) {
        double v = bytes;
        String[] units = {"B", "K", "M", "G", "T"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        if (u == 0) return bytes + "B";
        return v < 10 ? String.format(Locale.ROOT, "%.1f%s", v, units[u])
                : String.format(Locale.ROOT, "%.0f%s", v, units[u]);
    }

    /** Anzeigename relativ zum Modellordner (Unterordner/Name.gguf). */
    static String displayName(File f, File modelDir) {
        String p = f.getPath();
        String d = modelDir.getPath();
        if (p.startsWith(d + File.separator)) return p.substring(d.length() + 1);
        return f.getName();
    }
}

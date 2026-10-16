package de.inteks.llamalauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Gespeicherte Einstellungen der App: Pfade, letztes Modell, Einstellungen pro Modell. */
final class Prefs {

    static final String DEF_MODEL_DIR = "/storage/emulated/0/Models";
    static final String DEF_SERVER = TermuxBridge.TERMUX_HOME + "/llama.cpp/build-vk/bin/llama-server";
    static final String DEF_LOG = "/storage/emulated/0/Documents/LlamaLauncher/llama-server.log";
    static final int DEF_PORT = 8123;

    private static final String FILE = "llama_launcher";
    private static final String PRESET_PREFIX = "preset:";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String modelDir(Context c) {
        return sp(c).getString("modelDir", DEF_MODEL_DIR);
    }

    static String serverPath(Context c) {
        return sp(c).getString("serverPath", DEF_SERVER);
    }

    static String logPath(Context c) {
        return sp(c).getString("logPath", DEF_LOG);
    }

    static int port(Context c) {
        return sp(c).getInt("port", DEF_PORT);
    }

    static void savePaths(Context c, String modelDir, String serverPath, String logPath, int port) {
        sp(c).edit().putString("modelDir", modelDir).putString("serverPath", serverPath)
                .putString("logPath", logPath).putInt("port", port).apply();
    }

    static String lastModel(Context c) {
        return sp(c).getString("lastModel", "");
    }

    static void setLastModel(Context c, String path) {
        sp(c).edit().putString("lastModel", path).apply();
    }

    /** Sprache der Oberflaeche: "" = Systemsprache, sonst "de" oder "en". */
    static String language(Context c) {
        return sp(c).getString("language", "");
    }

    static void setLanguage(Context c, String lang) {
        sp(c).edit().putString("language", lang).commit(); // sofort, die Activity wird gleich neu aufgebaut
    }

    static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", false);
    }

    static void setAutostart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }

    /** Einstellungen fuer ein Modell (Schluessel: Dateiname) oder null, wenn noch keine gespeichert. */
    static ServerSettings preset(Context c, String modelName) {
        String json = sp(c).getString(PRESET_PREFIX + modelName, null);
        return json == null ? null : ServerSettings.fromJson(json);
    }

    static void savePreset(Context c, String modelName, ServerSettings s) {
        sp(c).edit().putString(PRESET_PREFIX + modelName, s.toJson()).apply();
    }

    static void deletePreset(Context c, String modelName) {
        sp(c).edit().remove(PRESET_PREFIX + modelName).apply();
    }

    static ServerSettings.Features features(Context c) {
        return ServerSettings.Features.decode(sp(c).getString("features", null));
    }

    static void saveFeatures(Context c, ServerSettings.Features f) {
        sp(c).edit().putString("features", f.encode()).apply();
    }

    /** Ergebnis eines Server-Endes, das ankam, als die App nicht offen war. */
    static void storePendingExit(Context c, Bundle result) {
        int code = result.getInt(TermuxBridge.RESULT_EXIT_CODE, Integer.MIN_VALUE);
        String errmsg = result.getString(TermuxBridge.RESULT_ERRMSG);
        sp(c).edit().putInt("pendingExitCode", code).putString("pendingExitErr", errmsg)
                .putBoolean("pendingExit", true).apply();
    }

    /** Liefert das gemerkte Server-Ende einmalig zurueck (oder null). */
    static Bundle takePendingExit(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("pendingExit", false)) return null;
        Bundle b = new Bundle();
        int code = p.getInt("pendingExitCode", Integer.MIN_VALUE);
        if (code != Integer.MIN_VALUE) b.putInt(TermuxBridge.RESULT_EXIT_CODE, code);
        b.putString(TermuxBridge.RESULT_ERRMSG, p.getString("pendingExitErr", null));
        p.edit().putBoolean("pendingExit", false).apply();
        return b;
    }
}

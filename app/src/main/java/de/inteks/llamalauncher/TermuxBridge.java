package de.inteks.llamalauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Startet Befehle in Termux ueber dessen offizielle RUN_COMMAND-Schnittstelle.
 * Doku: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 *
 * Voraussetzungen:
 *  - Termux installiert
 *  - in Termux: "allow-external-apps = true" in ~/.termux/termux.properties
 *  - diese App hat die Berechtigung com.termux.permission.RUN_COMMAND
 *
 * Alle Befehle laufen im Hintergrund (ohne Terminalfenster) als "bash -c SKRIPT".
 * Das Ergebnis (stdout, stderr, Exit-Code, Fehler) schickt Termux per PendingIntent
 * an den TermuxResultReceiver.
 */
final class TermuxBridge {

    static final String TERMUX_PACKAGE = "com.termux";
    static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";

    // Konstanten aus termux-shared/TermuxConstants.java (RUN_COMMAND_SERVICE)
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    // Ergebnis-Bundle (TermuxConstants.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_*)
    static final String RESULT_BUNDLE = "result";
    static final String RESULT_STDOUT = "stdout";
    static final String RESULT_STDERR = "stderr";
    static final String RESULT_EXIT_CODE = "exitCode";
    static final String RESULT_ERRMSG = "errmsg";

    // Eigene Extras im Ergebnis-Intent
    static final String EXTRA_CMD = "de.inteks.llamalauncher.CMD";

    static final String CMD_START = "start";
    static final String CMD_STOP = "stop";
    static final String CMD_CHECK = "check";

    private static final AtomicInteger REQUEST_CODE =
            new AtomicInteger((int) (System.currentTimeMillis() & 0xFFFFF));

    private TermuxBridge() {
    }

    static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean hasRunCommandPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Fuehrt "bash -c script name args..." in Termux im Hintergrund aus.
     * Im Skript sind die Argumente als $1, $2, ... verfuegbar (keine Probleme mit Leerzeichen/Quoting).
     *
     * @return null bei Erfolg, sonst eine Fehlermeldung
     */
    static String runScript(Context ctx, String cmd, String label, String script, List<String> args) {
        if (!isTermuxInstalled(ctx)) return ctx.getString(R.string.toast_no_termux);
        if (!hasRunCommandPermission(ctx)) return ctx.getString(R.string.err_no_perm);

        List<String> all = new ArrayList<>();
        all.add("-c");
        all.add(script);
        all.add("llama-launcher"); // wird zu $0
        if (args != null) all.addAll(args);

        Intent intent = new Intent(ACTION_RUN_COMMAND);
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH);
        intent.putExtra(EXTRA_ARGUMENTS, all.toArray(new String[0]));
        intent.putExtra(EXTRA_WORKDIR, TERMUX_HOME);
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_COMMAND_LABEL, label);

        Intent resultIntent = new Intent(ctx, TermuxResultReceiver.class);
        resultIntent.putExtra(EXTRA_CMD, cmd);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        // Termux traegt das Ergebnis per fillIn ein -> muss ab Android 12 ausdruecklich MUTABLE sein
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE.incrementAndGet(), resultIntent, flags);
        intent.putExtra(EXTRA_PENDING_INTENT, pi);

        try {
            ctx.startForegroundService(intent);
            return null;
        } catch (SecurityException e) {
            return ctx.getString(R.string.err_rejected, e.getMessage());
        } catch (RuntimeException e) {
            return ctx.getString(R.string.err_start_termux, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Die Skripte, die in Termux laufen
    // ---------------------------------------------------------------------------------------

    /**
     * Startet llama-server und laeuft so lange wie der Server. Danach kommt das Ergebnis mit dem
     * Exit-Code zurueck. Die Ausgabe des Servers landet in der Log-Datei.
     * $1 = Log-Datei, $2... = llama-server und seine Parameter
     */
    static final String SCRIPT_START =
            "log=\"$1\"; shift\n"
            + "mkdir -p \"$(dirname \"$log\")\" || exit 90\n"
            + "if [ ! -x \"$1\" ]; then echo \"llama-server not found: $1\" > \"$log\"; exit 91; fi\n"
            + "termux-wake-lock 2>/dev/null\n"
            + "echo \"Command line: $*\" > \"$log\"\n"
            + "echo >> \"$log\"\n"
            + "\"$@\" >> \"$log\" 2>&1\n"
            + "rc=$?\n"
            + "termux-wake-unlock 2>/dev/null\n"
            + "exit $rc\n";

    /**
     * Beendet llama-server auf dem Port sauber, nach 10 s notfalls hart.
     * Trifft auch einen Server, der mit llama-start.sh gestartet wurde.
     * $1 = Port
     */
    static final String SCRIPT_STOP =
            "pat=\"^[[:graph:]]*llama-server .*--port $1\"\n"
            + "pkill -f \"$pat\"\n"
            + "for _ in $(seq 1 10); do\n"
            + "  pgrep -f \"$pat\" >/dev/null || { termux-wake-unlock 2>/dev/null; exit 0; }\n"
            + "  sleep 1\n"
            + "done\n"
            + "pkill -9 -f \"$pat\"\n"
            + "sleep 1\n"
            + "termux-wake-unlock 2>/dev/null\n"
            + "pgrep -f \"$pat\" >/dev/null && exit 1\n"
            + "exit 0\n";

    /**
     * Prueft Termux und den llama.cpp-Build: gibt "SERVER_OK" und die bekannten Optionen aus.
     * $1 = Pfad zu llama-server
     */
    static final String SCRIPT_CHECK =
            "echo TERMUX_OK\n"
            + "if [ ! -x \"$1\" ]; then echo NO_SERVER; exit 3; fi\n"
            + "echo SERVER_OK\n"
            + "h=$(\"$1\" --help 2>&1)\n"
            + "for f in --load-mode --no-jinja --spec-type --lora-scaled; do\n"
            + "  echo \"$h\" | grep -q -- \"$f\" && echo \"FLAG $f\"\n"
            + "done\n"
            + "echo \"$h\" | grep -qE -- '--reasoning( |$)' && echo \"FLAG --reasoning\"\n"
            + "exit 0\n";
}

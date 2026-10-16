package de.inteks.llamalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.ref.WeakReference;

/**
 * Empfaengt das Ergebnis eines Termux-Befehls (PendingIntent aus TermuxBridge).
 * Laeuft die Oberflaeche gerade, wird das Ergebnis direkt weitergereicht,
 * sonst wird es fuer den naechsten Start der App gemerkt.
 */
public class TermuxResultReceiver extends BroadcastReceiver {

    interface Listener {
        void onTermuxResult(String cmd, Bundle result);
    }

    private static WeakReference<Listener> listener = new WeakReference<>(null);

    static void setListener(Listener l) {
        listener = new WeakReference<>(l);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra(TermuxBridge.EXTRA_CMD);
        Bundle result = intent.getBundleExtra(TermuxBridge.RESULT_BUNDLE);
        if (result == null) result = new Bundle();

        Listener l = listener.get();
        if (l != null) {
            l.onTermuxResult(cmd, result);
        } else if (cmd != null && cmd.startsWith(TermuxBridge.CMD_START)) {
            // Server wurde beendet, waehrend die App nicht offen war -> beim naechsten Oeffnen melden
            Prefs.storePendingExit(context, result);
        }
    }
}

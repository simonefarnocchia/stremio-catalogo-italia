package it.avviohdmi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Riserva: se il servizio di accessibilità non è attivo prova comunque ad aprire
 * l'HDMI all'accensione. Su Android 10 e successivi il sistema di solito lo
 * blocca, per questo il servizio è la strada principale.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent intent) {
        if (Switcher.serviceEnabled(c)) return;
        if (!Switcher.prefs(c).getBoolean(Switcher.K_ON_BOOT, true)) return;
        final PendingResult pr = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                Switcher.switchNow(c.getApplicationContext());
                pr.finish();
            }
        }, 5000);
    }
}

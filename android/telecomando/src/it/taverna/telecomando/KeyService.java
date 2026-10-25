package it.taverna.telecomando;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Intercetta Volume, Muto, Home e Assistente del telecomando del proiettore,
 * ma solo mentre è in primo piano l'ingresso HDMI: li manda a Home Assistant
 * e il proiettore non li vede. Nelle altre app i tasti funzionano come sempre.
 */
public class KeyService extends AccessibilityService {
    private static final long VOLUME_REPEAT_MS = 150;

    private final Set<String> hdmiApps = new HashSet<>();
    private final Set<String> ignored = new HashSet<>();
    private String foreground = "";
    private long lastVolumeSent;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            hdmiApps.clear();
            hdmiApps.add("com.mediatek.wwtv.tvcenter");      // app degli ingressi del JMGO
            hdmiApps.add("com.android.tv");
            hdmiApps.add("com.google.android.tv");
            // Qualsiasi app che apre gli ingressi TV
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput("x"));
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) {
                hdmiApps.add(r.activityInfo.packageName);
            }
            ignored.clear();
            ignored.add("com.android.systemui");
            ignored.add(getPackageName());
            Log.i(Prefs.TAG, "Servizio attivo, ingressi: " + hdmiApps);
        } catch (Throwable t) {
            error("Avvio servizio", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            if (e.getPackageName() == null) return;
            String pkg = e.getPackageName().toString();
            if (!ignored.contains(pkg)) foreground = pkg;
        } catch (Throwable t) {
            error("Evento finestra", t);
        }
    }

    private boolean onHdmi() {
        return hdmiApps.contains(foreground);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (!onHdmi()) return false;
            String key = map(event.getKeyCode());
            if (key == null) return false;

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                boolean volume = key.startsWith("volume_");
                if (volume) {
                    long now = SystemClock.uptimeMillis();
                    if (event.getRepeatCount() == 0 || now - lastVolumeSent >= VOLUME_REPEAT_MS) {
                        lastVolumeSent = now;
                        send(key);
                    }
                } else if (event.getRepeatCount() == 0) {
                    send(key);
                }
            }
            return true;   // il proiettore non vede il tasto
        } catch (Throwable t) {
            error("Tasto", t);
            return false;
        }
    }

    private String map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                return Prefs.on(this, Prefs.K_VOLUME, true) ? "volume_up" : null;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return Prefs.on(this, Prefs.K_VOLUME, true) ? "volume_down" : null;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return Prefs.on(this, Prefs.K_VOLUME, true) ? "mute" : null;
            case KeyEvent.KEYCODE_HOME:
                return Prefs.on(this, Prefs.K_HOME, true) ? "home" : null;
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                return Prefs.on(this, Prefs.K_ASSIST, false) ? "assistant" : null;
            default:
                return null;
        }
    }

    private void send(String key) {
        Prefs.get(this).edit().putString(Prefs.K_LAST_KEY, key).apply();
        Bridge.send(this, key, null);
    }

    private void error(String where, Throwable t) {
        Log.e(Prefs.TAG, where, t);
        Prefs.get(this).edit().putString(Prefs.K_LAST_ERROR, where + ": " + t).apply();
    }

    @Override public void onInterrupt() {}
}

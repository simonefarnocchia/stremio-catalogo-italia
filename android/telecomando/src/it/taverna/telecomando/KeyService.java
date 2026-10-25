package it.taverna.telecomando;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
 * All'accensione (e al risveglio dallo standby) porta il proiettore sull'HDMI.
 */
public class KeyService extends AccessibilityService {
    private static final long VOLUME_REPEAT_MS = 150;
    private static final long HOME_LONG_MS = 600;     // Home tenuto premuto = Home del proiettore

    private final Set<String> hdmiApps = new HashSet<>();
    private final Set<String> ignored = new HashSet<>();
    private String foreground = "";
    private long lastVolumeSent;
    private long homeDownAt;
    private boolean homeLongDone;
    private long passHomeUntil;

    // Avvio su HDMI
    private static final long BOOT_WINDOW_MS = 4 * 60 * 1000;   // accensione = primi 4 minuti
    private static final long WATCH_MS = 45 * 1000;              // per 45 s controlla la Home
    private static final long FIRST_DELAY_MS = 3000;
    private static final int MAX_ATTEMPTS = 4;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> homes = new HashSet<>();
    private long watchUntil;
    private int attempts;
    private boolean registered;

    private final Runnable doSwitch = new Runnable() {
        @Override public void run() {
            try {
                if (attempts >= MAX_ATTEMPTS) return;
                attempts++;
                Switcher.switchNow(KeyService.this);
            } catch (Throwable t) {
                error("Apertura HDMI", t);
            }
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Prefs.on(c, Switcher.K_ON_WAKE, true)) {
                Log.i(Prefs.TAG, "Risveglio dallo standby");
                startSwitch(FIRST_DELAY_MS);
            }
        }
    };

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
        try {
            loadHomes();
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOn, f);
            registered = true;
            if (isFreshBoot()) {
                Log.i(Prefs.TAG, "Accensione: passo all'HDMI");
                startSwitch(FIRST_DELAY_MS);
            }
        } catch (Throwable t) {
            error("Avvio su HDMI", t);
        }
    }

    private void startSwitch(long delay) {
        attempts = 0;
        watchUntil = SystemClock.elapsedRealtime() + WATCH_MS;
        handler.removeCallbacks(doSwitch);
        handler.postDelayed(doSwitch, delay);
    }

    /** True una sola volta per accensione, e solo nei primi minuti. */
    private boolean isFreshBoot() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean(Switcher.K_ON_BOOT, true)) return false;
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return false;
        long boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) boot = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000;
        if (p.getLong("lastBoot", Long.MIN_VALUE) == boot) return false;
        p.edit().putLong("lastBoot", boot).apply();
        return true;
    }

    private void loadHomes() {
        homes.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : getPackageManager().queryIntentActivities(home, 0)) {
            homes.add(r.activityInfo.packageName);
        }
        homes.add("com.google.android.apps.tv.launcherx");
        homes.add("com.google.android.tvlauncher");
        homes.add("com.google.android.leanbacklauncher");
        homes.add("com.spocky.projengmenu");              // Projectivy Launcher
        homes.remove(getPackageName());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            if (e.getPackageName() == null) return;
            String pkg = e.getPackageName().toString();
            if (!ignored.contains(pkg)) foreground = pkg;
            // Dopo l'accensione: se la Home copre l'HDMI, si riprova
            if (SystemClock.elapsedRealtime() < watchUntil) {
                if (homes.contains(pkg)) {
                    handler.removeCallbacks(doSwitch);
                    handler.postDelayed(doSwitch, 1500);
                } else if (hdmiApps.contains(pkg)) {
                    watchUntil = 0;                         // HDMI in primo piano: fatto
                }
            }
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
            if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return onHome(event);
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

    /** Home breve → Home del box; Home tenuto premuto → Home del proiettore. */
    private boolean onHome(KeyEvent event) {
        long now = SystemClock.uptimeMillis();
        if (now < passHomeUntil) return false;            // la Home che abbiamo rimandato noi
        if (!Prefs.on(this, Prefs.K_HOME, true)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                homeDownAt = now;
                homeLongDone = false;
            } else if (!homeLongDone && ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                    || now - homeDownAt >= HOME_LONG_MS)) {
                homeLongDone = true;
                projectorHome();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && !homeLongDone) {
            if (now - homeDownAt >= HOME_LONG_MS) projectorHome();
            else send("home");
        }
        return true;
    }

    private void projectorHome() {
        watchUntil = 0;
        handler.removeCallbacks(doSwitch);
        passHomeUntil = SystemClock.uptimeMillis() + 1500;
        Prefs.get(this).edit().putString(Prefs.K_LAST_KEY, "home_proiettore").apply();
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private String map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                return Prefs.on(this, Prefs.K_VOLUME, false) ? "volume_up" : null;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return Prefs.on(this, Prefs.K_VOLUME, false) ? "volume_down" : null;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return Prefs.on(this, Prefs.K_VOLUME, false) ? "mute" : null;
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

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            try { unregisterReceiver(screenOn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}

package it.avviohdmi;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Servizio di accessibilità: il sistema lo avvia all'accensione e gli permette
 * di aprire l'ingresso HDMI anche dallo sfondo. Guarda solo quale app è in
 * primo piano, per ripetere il passaggio se la schermata Home si sovrappone.
 */
public class HdmiService extends AccessibilityService {
    private static final long BOOT_WINDOW_MS = 4 * 60 * 1000;   // vale come accensione nei primi 4 minuti
    private static final long WATCH_MS = 45 * 1000;              // per 45 secondi controlla la Home
    private static final long FIRST_DELAY_MS = 3000;
    private static final int MAX_ATTEMPTS = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> homes = new HashSet<>();
    private long watchUntil;
    private int attempts;
    private boolean registered;

    private final Runnable doSwitch = new Runnable() {
        @Override public void run() {
            if (attempts >= MAX_ATTEMPTS) return;
            attempts++;
            Switcher.switchNow(HdmiService.this);
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Switcher.prefs(c).getBoolean(Switcher.K_ON_WAKE, true)) {
                Log.i(Switcher.TAG, "Risveglio dallo standby");
                start(FIRST_DELAY_MS);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        loadHomes();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOn, f);
        registered = true;
        if (isFreshBoot()) {
            Log.i(Switcher.TAG, "Accensione");
            start(FIRST_DELAY_MS);
        }
    }

    private void start(long delay) {
        attempts = 0;
        watchUntil = SystemClock.elapsedRealtime() + WATCH_MS;
        handler.removeCallbacks(doSwitch);
        handler.postDelayed(doSwitch, delay);
    }

    /** True una sola volta per accensione, e solo nei primi minuti. */
    private boolean isFreshBoot() {
        SharedPreferences p = Switcher.prefs(this);
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
        homes.add("com.spocky.projengmenu");
        homes.remove(getPackageName());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (SystemClock.elapsedRealtime() > watchUntil || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (homes.contains(pkg)) {
            // La Home è tornata davanti: si riprova tra poco
            handler.removeCallbacks(doSwitch);
            handler.postDelayed(doSwitch, 1500);
        } else if (attempts > 0 && !pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) {
            // Un'altra app (l'ingresso HDMI) è in primo piano: fatto
            watchUntil = 0;
        }
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

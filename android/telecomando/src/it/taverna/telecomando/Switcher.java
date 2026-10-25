package it.taverna.telecomando;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Trova gli ingressi del proiettore e apre quello scelto. */
final class Switcher {
    static final String TAG = Prefs.TAG;
    static final String K_TARGET = "target";      // "input:<id>" oppure "app:<pacchetto>"
    static final String K_ON_BOOT = "onBoot";
    static final String K_ON_WAKE = "onWake";

    private Switcher() {}

    static SharedPreferences prefs(Context c) {
        return Prefs.get(c);
    }

    /** Ingressi TV, prima gli HDMI e poi gli altri passthrough. */
    static List<TvInputInfo> inputs(Context c) {
        List<TvInputInfo> out = new ArrayList<>();
        try {
            TvInputManager m = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (m == null) return out;
            List<TvInputInfo> all = m.getTvInputList();
            if (all == null) return out;
            for (TvInputInfo i : all) {
                if (i.isHidden(c)) continue;
                if (i.isPassthroughInput() || i.getType() == TvInputInfo.TYPE_HDMI) out.add(i);
            }
            Collections.sort(out, new Comparator<TvInputInfo>() {
                @Override public int compare(TvInputInfo a, TvInputInfo b) {
                    int ra = a.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                    int rb = b.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                    if (ra != rb) return ra - rb;
                    return label(c, a).compareToIgnoreCase(label(c, b));
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Lettura ingressi non riuscita", e);
        }
        return out;
    }

    static String label(Context c, TvInputInfo i) {
        CharSequence s = null;
        try { s = i.loadCustomLabel(c); } catch (Exception ignored) {}
        if (TextUtils.isEmpty(s)) {
            try { s = i.loadLabel(c); } catch (Exception ignored) {}
        }
        return TextUtils.isEmpty(s) ? i.getId() : s.toString();
    }

    static String target(Context c) {
        String t = prefs(c).getString(K_TARGET, null);
        if (t != null) return t;
        List<TvInputInfo> in = inputs(c);
        return in.isEmpty() ? null : "input:" + in.get(0).getId();
    }

    static String targetLabel(Context c, String t) {
        if (t == null) return "nessuno";
        if (t.startsWith("app:")) {
            String pkg = t.substring(4);
            try {
                PackageManager pm = c.getPackageManager();
                return "App " + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (Exception e) {
                return "App " + pkg;
            }
        }
        String id = t.substring(6);
        for (TvInputInfo i : inputs(c)) if (i.getId().equals(id)) return label(c, i);
        return id;
    }

    /** Apre l'ingresso (o l'app) scelto. Restituisce false se non c'è niente da aprire. */
    static boolean switchNow(Context c) {
        String t = target(c);
        if (t == null) {
            Log.w(TAG, "Nessun ingresso da aprire");
            return false;
        }
        try {
            Intent i;
            PackageManager pm = c.getPackageManager();
            if (t.startsWith("app:")) {
                String pkg = t.substring(4);
                i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) return false;
            } else {
                i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.substring(6)));
                // Se più app gestiscono gli ingressi, si sceglie quella di sistema: niente finestra di scelta
                List<ResolveInfo> handlers = pm.queryIntentActivities(i, 0);
                if (handlers.isEmpty()) {
                    Log.w(TAG, "Nessuna app di sistema apre gli ingressi TV");
                    return false;
                }
                if (handlers.size() > 1) {
                    for (ResolveInfo r : handlers) {
                        if ((r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
                            break;
                        }
                    }
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            Log.i(TAG, "Aperto " + t);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Apertura non riuscita: " + t, e);
            return false;
        }
    }
}

package it.taverna.telecomando;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class Prefs {
    static final String TAG = "TelecomandoTaverna";
    static final String K_VOLUME = "volume";        // Volume/Muto → impianto
    static final String K_HOME = "home";            // Home → box
    static final String K_ASSIST = "assist";        // Assistente → box
    static final String K_LAST_ERROR = "lastError";
    static final String K_LAST_KEY = "lastKey";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("telecomando", Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    static boolean serviceEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(c, KeyService.class);
        for (String s : on.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) return true;
        }
        return false;
    }
}

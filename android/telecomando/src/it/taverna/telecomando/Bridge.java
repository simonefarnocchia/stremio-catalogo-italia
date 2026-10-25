package it.taverna.telecomando;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Collegamento con Home Assistant: trova HA in rete (zeroconf) e gli manda
 * i tasti del telecomando su un webhook locale.
 */
final class Bridge {
    static final String WEBHOOK_ID = "taverna_jmgo_b25131f8fdde2b5d9a027ea7";
    static final String K_HA_URL = "haUrl";
    static final String K_LAST_RESULT = "lastBridge";

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static boolean discovering;

    private Bridge() {}

    static String baseUrl(Context c) {
        return Prefs.get(c).getString(K_HA_URL, "");
    }

    static void setBaseUrl(Context c, String url) {
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("http")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Solo l'indirizzo, senza porta: si usa quella di Home Assistant
        if (u.matches("^http://[^/:]+$")) u = u + ":8123";
        Prefs.get(c).edit().putString(K_HA_URL, u).apply();
    }

    interface Done { void result(boolean ok, String message); }

    /** Invia un tasto a HA. Non blocca mai il chiamante. */
    static void send(final Context ctx, final String key, final Done done) {
        final Context c = ctx.getApplicationContext();
        final String base = baseUrl(c);
        if (base.isEmpty()) {
            record(c, "Home Assistant non configurato");
            if (done != null) post(done, false, "Home Assistant non configurato");
            return;
        }
        NET.execute(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String msg;
                HttpURLConnection h = null;
                try {
                    URL url = new URL(base + "/api/webhook/" + WEBHOOK_ID);
                    h = (HttpURLConnection) url.openConnection();
                    h.setConnectTimeout(2000);
                    h.setReadTimeout(3000);
                    h.setRequestMethod("POST");
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    byte[] body = ("{\"key\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
                    OutputStream os = h.getOutputStream();
                    os.write(body);
                    os.close();
                    int code = h.getResponseCode();
                    ok = code >= 200 && code < 300;
                    msg = (ok ? "OK " : "Errore HTTP ") + code + " · " + key;
                } catch (Exception e) {
                    msg = "Non raggiungo " + base + " (" + e.getClass().getSimpleName() + ")";
                } finally {
                    if (h != null) h.disconnect();
                }
                record(c, msg);
                if (done != null) post(done, ok, msg);
            }
        });
    }

    /** Cerca Home Assistant in rete (servizio zeroconf _home-assistant._tcp). */
    static void discover(final Context ctx, final Done done) {
        final Context c = ctx.getApplicationContext();
        final NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null || discovering) return;
        discovering = true;
        final Handler main = new Handler(Looper.getMainLooper());
        final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
        final boolean[] found = { false };

        final Runnable stop = new Runnable() {
            @Override public void run() {
                if (!discovering) return;
                discovering = false;
                try { nsd.stopServiceDiscovery(holder[0]); } catch (Exception ignored) {}
                if (!found[0] && done != null) done.result(false, "Home Assistant non trovato in rete");
            }
        };

        holder[0] = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) { main.post(stop); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                if (found[0]) return;
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) {}
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress a = i.getHost();
                            if (a == null || found[0]) return;
                            found[0] = true;
                            String host = a.getHostAddress();
                            if (host.contains(":")) host = "[" + host + "]";
                            final String url = "http://" + host + ":" + i.getPort();
                            Prefs.get(c).edit().putString(K_HA_URL, url).apply();
                            Log.i(Prefs.TAG, "Home Assistant trovato: " + url);
                            main.post(new Runnable() {
                                @Override public void run() {
                                    stop.run();
                                    if (done != null) done.result(true, url);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    Log.w(Prefs.TAG, "Risoluzione non riuscita", e);
                }
            }
        };
        try {
            nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, holder[0]);
            main.postDelayed(stop, 12000);
        } catch (Exception e) {
            discovering = false;
            if (done != null) done.result(false, "Ricerca non disponibile");
        }
    }

    static void record(Context c, String msg) {
        SharedPreferences p = Prefs.get(c);
        p.edit().putString(K_LAST_RESULT, msg).apply();
        Log.i(Prefs.TAG, msg);
    }

    private static void post(final Done d, final boolean ok, final String m) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { d.result(ok, m); }
        });
    }
}

package it.avviohdmi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Schermata di impostazione: cosa aprire, quando, e prova. */
public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private Button serviceBtn;
    private RadioGroup group;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(15, 17, 21));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(32), dp(64), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void build() {
        root.removeAllViews();
        text("Avvio HDMI", 30, Color.WHITE, 4);
        text("All'accensione porta il proiettore direttamente sull'ingresso HDMI.", 16, Color.rgb(154, 163, 178), 20);

        status = text("", 18, Color.WHITE, 8);
        serviceBtn = button("Attiva il servizio (Accessibilità)", new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessibility(); }
        });

        section("Cosa aprire");
        group = new RadioGroup(this);
        root.addView(group);
        String current = Switcher.target(this);
        List<TvInputInfo> inputs = Switcher.inputs(this);
        if (inputs.isEmpty()) {
            text("Nessun ingresso TV trovato dal sistema: scegli l'app che apre l'HDMI.", 16, Color.rgb(255, 196, 0), 8);
        }
        for (TvInputInfo i : inputs) {
            String t = "input:" + i.getId();
            String kind = i.getType() == TvInputInfo.TYPE_HDMI ? "HDMI" : "ingresso";
            addOption(t, Switcher.label(this, i) + "  ·  " + kind, t.equals(current));
        }
        if (current != null && current.startsWith("app:")) {
            addOption(current, Switcher.targetLabel(this, current), true);
        }
        button("Usa un'app al posto dell'ingresso…", new View.OnClickListener() {
            @Override public void onClick(View v) { pickApp(); }
        });

        section("Quando");
        final SharedPreferences p = Switcher.prefs(this);
        check("All'accensione", Switcher.K_ON_BOOT, p);
        check("Quando si riaccende dallo standby", Switcher.K_ON_WAKE, p);

        section("Prova");
        Button test = button("Apri l'HDMI adesso", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Switcher.switchNow(MainActivity.this)) {
                    Toast.makeText(MainActivity.this,
                        "Non riesco ad aprirlo: prova un'altra voce o scegli l'app che apre l'HDMI",
                        Toast.LENGTH_LONG).show();
                }
            }
        });

        text("\nSe l'HDMI si apre con \"Apri l'HDMI adesso\", attiva il servizio e spegni e riaccendi il proiettore per provare l'avvio automatico.",
            15, Color.rgb(154, 163, 178), 0);
        test.requestFocus();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean on = Switcher.serviceEnabled(this);
        status.setText(on ? "✅ Servizio di avvio attivo" : "⚠️ Servizio di avvio non attivo: senza, all'accensione l'app non può aprire l'HDMI");
        status.setTextColor(on ? Color.rgb(46, 204, 113) : Color.rgb(255, 196, 0));
        serviceBtn.setText(on ? "Impostazioni Accessibilità" : "Attiva il servizio (Accessibilità)");
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "Apri \"Avvio HDMI\" e attivalo", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Apri Impostazioni → Sistema → Accessibilità → Avvio HDMI", Toast.LENGTH_LONG).show();
        }
    }

    private void pickApp() {
        final PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        final List<ResolveInfo> apps = new ArrayList<>();
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && seen.add(pkg)) apps.add(r);
            }
        }
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this)
            .setTitle("App che apre l'HDMI")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    String t = "app:" + apps.get(which).activityInfo.packageName;
                    Switcher.prefs(MainActivity.this).edit().putString(Switcher.K_TARGET, t).apply();
                    build();
                }
            })
            .show();
    }

    private void addOption(final String target, String label, boolean checked) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        r.setTextColor(Color.WHITE);
        r.setPadding(dp(8), dp(10), dp(8), dp(10));
        r.setId(View.generateViewId());
        group.addView(r);
        if (checked) r.setChecked(true);
        r.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                if (on) Switcher.prefs(MainActivity.this).edit().putString(Switcher.K_TARGET, target).apply();
            }
        });
    }

    private void check(String label, final String key, final SharedPreferences p) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        c.setTextColor(Color.WHITE);
        c.setPadding(dp(8), dp(10), dp(8), dp(10));
        c.setChecked(p.getBoolean(key, true));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                p.edit().putBoolean(key, on).apply();
            }
        });
        root.addView(c);
    }

    private void section(String title) {
        text(title, 20, Color.rgb(123, 92, 255), 4).setPadding(0, dp(20), 0, dp(4));
    }

    private TextView text(String s, int sp, int color, int bottomDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, 0, 0, dp(bottomDp));
        root.addView(t);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        root.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

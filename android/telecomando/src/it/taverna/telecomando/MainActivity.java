package it.taverna.telecomando;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Impostazioni: servizio, indirizzo di Home Assistant, tasti, prove. */
public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status, haStatus, last;
    private Button serviceBtn;
    private EditText haField;

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
        if (Bridge.baseUrl(this).isEmpty()) search();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void build() {
        text("Telecomando Taverna", 30, Color.WHITE, 4);
        text("Sull'HDMI del box, i tasti del telecomando del proiettore vanno a Home Assistant: "
            + "Volume all'impianto, Home al box. Nelle altre app funzionano come sempre.",
            16, Color.rgb(154, 163, 178), 16);

        status = text("", 18, Color.WHITE, 4);
        serviceBtn = button("Attiva il servizio (Accessibilità)", new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessibility(); }
        });

        section("Home Assistant");
        haStatus = text("", 16, Color.WHITE, 4);
        haField = new EditText(this);
        haField.setHint("es. 192.168.178.20:8123");
        haField.setSingleLine(true);
        haField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        haField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        haField.setTextColor(Color.WHITE);
        haField.setHintTextColor(Color.GRAY);
        root.addView(haField);
        button("Salva indirizzo", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Bridge.setBaseUrl(MainActivity.this, haField.getText().toString());
                refresh();
                test("ping");
            }
        });
        button("Cerca Home Assistant in rete", new View.OnClickListener() {
            @Override public void onClick(View v) { search(); }
        });

        section("Tasti");
        SharedPreferences p = Prefs.get(this);
        check("Volume e Muto → impianto audio", Prefs.K_VOLUME, true, p);
        check("Home → Home del box", Prefs.K_HOME, true, p);
        check("Assistente → box (il box non riceve la voce: apre solo la ricerca)", Prefs.K_ASSIST, false, p);

        section("Prova");
        button("Prova Volume +", new View.OnClickListener() {
            @Override public void onClick(View v) { test("volume_up"); }
        });
        button("Prova Home del box", new View.OnClickListener() {
            @Override public void onClick(View v) { test("home"); }
        });
        last = text("", 15, Color.rgb(154, 163, 178), 0);
        refresh();
    }

    private void refresh() {
        if (status == null) return;
        boolean on = Prefs.serviceEnabled(this);
        status.setText(on ? "✅ Servizio attivo" : "⚠️ Servizio non attivo: attivalo per usare i tasti");
        status.setTextColor(on ? Color.rgb(46, 204, 113) : Color.rgb(255, 196, 0));
        serviceBtn.setText(on ? "Impostazioni Accessibilità" : "Attiva il servizio (Accessibilità)");

        String url = Bridge.baseUrl(this);
        haStatus.setText(url.isEmpty() ? "Indirizzo non impostato" : "Indirizzo: " + url);
        if (!url.isEmpty() && haField.getText().length() == 0) haField.setText(url.replace("http://", ""));

        SharedPreferences p = Prefs.get(this);
        String res = p.getString(Bridge.K_LAST_RESULT, "");
        String err = p.getString(Prefs.K_LAST_ERROR, "");
        last.setText((res.isEmpty() ? "" : "Ultimo invio: " + res)
            + (err.isEmpty() ? "" : "\nUltimo errore: " + err));
    }

    private void search() {
        haStatus.setText("Cerco Home Assistant in rete…");
        Bridge.discover(this, new Bridge.Done() {
            @Override public void result(boolean ok, String message) {
                if (!ok) Toast.makeText(MainActivity.this, message + ": scrivi l'indirizzo", Toast.LENGTH_LONG).show();
                haField.setText("");
                refresh();
            }
        });
    }

    private void test(String key) {
        Bridge.send(this, key, new Bridge.Done() {
            @Override public void result(boolean ok, String message) {
                Toast.makeText(MainActivity.this, ok ? "Home Assistant ha ricevuto il tasto" : message,
                    Toast.LENGTH_LONG).show();
                refresh();
            }
        });
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Apri \"Telecomando Taverna\" e attivalo", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Impostazioni → Sistema → Accessibilità → Telecomando Taverna", Toast.LENGTH_LONG).show();
        }
    }

    private void check(String label, final String key, boolean def, final SharedPreferences p) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        c.setTextColor(Color.WHITE);
        c.setPadding(dp(8), dp(10), dp(8), dp(10));
        c.setChecked(p.getBoolean(key, def));
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

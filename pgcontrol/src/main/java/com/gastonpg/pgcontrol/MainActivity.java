package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = 0xff090d14, PANEL = 0xff151c27, PANEL2 = 0xff1c2634;
    private static final int BLUE = 0xff258cff, MUTED = 0xff9ba9bb;
    private EditText address, token;
    private TextView status, sizeLabel, appsLabel, deviceLabel, percentLabel;
    private SeekBar sizeSeek;
    private Button applyButton;
    private LinearLayout home;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private JSONArray installedApps = new JSONArray();
    private int sizePercent = 100;
    private boolean connected = false;

    interface Done { void accept(JSONObject response); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        showHome();
    }

    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("PG", 30, true, BLUE));
        LinearLayout brand = column();
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("PG CONTROL", 19, true, Color.WHITE));
        brand.addView(text("Centro de control del TV Box", 12, false, MUTED));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(pill("PG 2.0", BLUE));
        root.addView(header);
        addGap(root, 22);

        root.addView(sectionTitle("Conexión"));
        LinearLayout connection = panel();
        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(getPreferences(0).getString("address", "http://192.168.110.35:8765"));
        address.setHint("http://192.168.x.x:8765");
        styleEdit(address);
        connection.addView(address);
        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(129);
        token.setText(getPreferences(0).getString("token", ""));
        token.setHint("Token del TV Box");
        styleEdit(token);
        connection.addView(token);
        Button connect = button("Conectar al TV Box", BLUE);
        connection.addView(connect);
        connect.setOnClickListener(v -> connect());
        status = text("Conectá el celular y el TV Box a la misma red Wi-Fi.", 12, false, MUTED);
        status.setPadding(0, dp(10), 0, 0);
        connection.addView(status);
        root.addView(connection);
        addGap(root, 18);

        root.addView(sectionTitle("Tamaño de tarjetas"));
        LinearLayout sizing = panel();
        LinearLayout sizeHead = new LinearLayout(this);
        sizeHead.setGravity(Gravity.CENTER_VERTICAL);
        sizeHead.addView(text("Tamaño global", 16, true, Color.WHITE), new LinearLayout.LayoutParams(0,-2,1));
        percentLabel = pill("100%", BLUE);
        sizeHead.addView(percentLabel);
        sizing.addView(sizeHead);
        sizeLabel = text("196 × 110 dp", 13, false, MUTED);
        sizeLabel.setPadding(0, dp(8), 0, 0);
        sizing.addView(sizeLabel);
        sizeSeek = new SeekBar(this);
        sizeSeek.setMax(80);
        sizeSeek.setProgress(40);
        sizeSeek.setEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            sizeSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            sizeSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(BLUE));
            sizeSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff354254));
        }
        sizing.addView(sizeSeek);
        LinearLayout range = new LinearLayout(this);
        range.addView(text("60%", 11, false, MUTED), new LinearLayout.LayoutParams(0,-2,1));
        TextView max = text("140%", 11, false, MUTED);
        max.setGravity(Gravity.RIGHT);
        range.addView(max, new LinearLayout.LayoutParams(0,-2,1));
        sizing.addView(range);
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                sizePercent = 60 + progress;
                updateSizeLabel();
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        addGap(sizing, 12);
        applyButton = button("Aplicar a todas las tarjetas", BLUE);
        applyButton.setEnabled(false);
        applyButton.setAlpha(.48f);
        applyButton.setOnClickListener(v -> applySizeToAll(0));
        sizing.addView(applyButton);
        root.addView(sizing);
        addGap(root, 18);

        root.addView(sectionTitle("Estado del dispositivo"));
        LinearLayout info = panel();
        deviceLabel = text("TV Box: sin conectar", 14, true, Color.WHITE);
        info.addView(deviceLabel);
        appsLabel = text("Aplicaciones detectadas: —", 12, false, MUTED);
        appsLabel.setPadding(0, dp(7), 0, 0);
        info.addView(appsLabel);
        root.addView(info);
        addGap(root, 18);

        root.addView(sectionTitle("Control remoto"));
        LinearLayout remote = panel();
        LinearLayout remoteRow = new LinearLayout(this);
        remoteRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView joystick = text("◉", 38, true, BLUE);
        remoteRow.addView(joystick);
        LinearLayout remoteText = column();
        remoteText.setPadding(dp(12),0,0,0);
        remoteText.addView(text("Control remoto y multitarea", 15, true, Color.WHITE));
        remoteText.addView(text("Pantalla en vivo · táctil · touchpad · apps", 11, false, MUTED));
        remoteRow.addView(remoteText, new LinearLayout.LayoutParams(0,-2,1));
        remote.addView(remoteRow);
        Button remoteButton = button("Abrir panel remoto", PANEL2);
        remoteButton.setOnClickListener(v -> showRemoteInfo());
        remote.addView(remoteButton);
        root.addView(remote);

        scroll.addView(root);
        setContentView(scroll);
        updateSizeLabel();
    }

    private void showRemoteInfo() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Panel remoto")
            .setMessage("El panel independiente está planificado para incluir transmisión de pantalla en vivo, control táctil y touchpad, multitarea y cierre de aplicaciones. Estas funciones requieren integrar y probar el servicio auxiliar del TV Box; todavía no están disponibles en esta compilación.")
            .setPositiveButton("Volver", (d,w) -> d.dismiss())
            .show();
    }

    private void connect() {
        String base = address.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty()) {
            status.setText("Ingresá la dirección y el token que muestra el Launcher.");
            return;
        }
        getPreferences(0).edit().putString("address", base).putString("token", key).apply();
        connected = false;
        applyButton.setEnabled(false); applyButton.setAlpha(.48f); sizeSeek.setEnabled(false);
        status.setText("Conectando…");
        request("GET", "/v1/device", null, device -> {
            deviceLabel.setText("TV Box: " + device.optString("model", "Dispositivo conectado") +
                " · Android " + device.optString("androidVersion", "—"));
            loadApps();
        });
    }

    private void loadApps() {
        request("GET", "/v1/packages", null, data -> {
            JSONArray apps = data.optJSONArray("apps");
            installedApps = apps == null ? new JSONArray() : apps;
            connected = true;
            appsLabel.setText("Aplicaciones detectadas: " + installedApps.length());
            sizeSeek.setEnabled(true); applyButton.setEnabled(true); applyButton.setAlpha(1f);
            status.setText("Conectado correctamente. Ya podés ajustar las tarjetas.");
        });
    }

    private void applySizeToAll(int index) {
        if (index == 0) {
            applyButton.setEnabled(false); sizeSeek.setEnabled(false);
            status.setText("Aplicando tamaño… 0/" + installedApps.length());
        }
        if (index >= installedApps.length()) {
            applyButton.setEnabled(true); sizeSeek.setEnabled(true);
            status.setText("Listo. Tamaño aplicado a " + installedApps.length() + " aplicaciones.");
            return;
        }
        JSONObject app = installedApps.optJSONObject(index);
        if (app == null) { applySizeToAll(index + 1); return; }
        String pkg = app.optString("packageName", "");
        if (pkg.isEmpty()) { applySizeToAll(index + 1); return; }
        int width = Math.max(100, Math.min(420, Math.round(196f * sizePercent / 100f)));
        int height = Math.max(60, Math.min(240, Math.round(110f * sizePercent / 100f)));
        try {
            JSONObject body = new JSONObject();
            body.put("packageName", pkg); body.put("cardWidthDp", width); body.put("cardHeightDp", height);
            request("POST", "/v1/launcher/app-config", body, response -> {
                status.setText("Aplicando tamaño… " + (index + 1) + "/" + installedApps.length());
                applySizeToAll(index + 1);
            });
        } catch (Exception e) {
            applyButton.setEnabled(true); sizeSeek.setEnabled(true);
            status.setText("No se pudo preparar el cambio para " + pkg + ".");
        }
    }

    private void updateSizeLabel() {
        if (sizeLabel == null) return;
        int width = Math.round(196f * sizePercent / 100f);
        int height = Math.round(110f * sizePercent / 100f);
        sizeLabel.setText(width + " × " + height + " dp");
        if (percentLabel != null) percentLabel.setText(sizePercent + "%");
    }

    private void request(String method, String path, JSONObject body, Done done) {
        String base = address.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + path).openConnection();
                connection.setRequestMethod(method); connection.setConnectTimeout(7000); connection.setReadTimeout(12000);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Accept", "application/json");
                if (body != null) {
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = connection.getResponseCode();
                InputStream input = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                JSONObject response = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (code >= 200 && code < 300) done.accept(response);
                    else {
                        connected = false; applyButton.setEnabled(false); applyButton.setAlpha(.48f); sizeSeek.setEnabled(false);
                        status.setText("Error " + code + ": " + response.optString("error", "Error de conexión"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connected = false; applyButton.setEnabled(false); applyButton.setAlpha(.48f); sizeSeek.setEnabled(false);
                    status.setText("Sin conexión: " + e.getMessage());
                    deviceLabel.setText("TV Box: sin conectar");
                });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout panel() {
        LinearLayout l = column(); l.setPadding(dp(15),dp(14),dp(15),dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(PANEL); bg.setCornerRadius(dp(17)); l.setBackground(bg); return l;
    }
    private TextView text(String value,int size,boolean bold,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v;
    }
    private TextView sectionTitle(String value) {
        TextView v=text(value,16,true,Color.WHITE); v.setPadding(dp(2),0,0,dp(9)); return v;
    }
    private TextView pill(String value,int color) {
        TextView v=text(value,12,true,color); v.setPadding(dp(10),dp(6),dp(10),dp(6));
        GradientDrawable b=new GradientDrawable(); b.setColor(0xff102b49); b.setCornerRadius(dp(30)); v.setBackground(b); return v;
    }
    private Button button(String value,int color) {
        Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(14); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color)); return b;
    }
    private void styleEdit(EditText e) {
        e.setTextColor(Color.WHITE); e.setHintTextColor(MUTED); e.setTextSize(14);
        e.setSingleLine(true); e.setPadding(dp(12),dp(10),dp(12),dp(10));
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
    }
    private void addGap(LinearLayout l,int h) { View v=new View(this); l.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
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
    private EditText address, token;
    private TextView status, sizeLabel, appsLabel;
    private SeekBar sizeSeek;
    private Button applyButton;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private JSONArray installedApps = new JSONArray();
    private int sizePercent = 100;
    private boolean connected = false;

    interface Done { void accept(JSONObject response); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 24, 22, 22);
        root.setBackgroundColor(0xff0b1120);

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(text("PG CONTROL", 25, true));
        page.addView(text("Control de tamaño de tarjetas", 16, true));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(getPreferences(0).getString("address", ""));
        address.setHint("http://192.168.x.x:8765");
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(0xff9aa9bd);
        page.addView(address);

        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(129);
        token.setText(getPreferences(0).getString("token", ""));
        token.setHint("Token del TV Box");
        token.setTextColor(Color.WHITE);
        token.setHintTextColor(0xff9aa9bd);
        page.addView(token);

        Button connect = button("Conectar con Google TV PG");
        page.addView(connect);
        connect.setOnClickListener(v -> connect());

        status = text("El celular y el TV Box deben estar en la misma red Wi-Fi.", 13, false);
        status.setPadding(0, 14, 0, 18);
        page.addView(status);

        View separator = new View(this);
        separator.setBackgroundColor(0xff30445f);
        page.addView(separator, new LinearLayout.LayoutParams(-1, 1));

        page.addView(text("Tamaño de las tarjetas", 19, true));
        sizeLabel = text("100% · 196 × 110 dp", 16, true);
        sizeLabel.setPadding(0, 14, 0, 4);
        page.addView(sizeLabel);

        sizeSeek = new SeekBar(this);
        sizeSeek.setMax(80);
        sizeSeek.setProgress(40);
        sizeSeek.setEnabled(false);
        page.addView(sizeSeek);
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizePercent = 60 + progress;
                updateSizeLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout range = new LinearLayout(this);
        TextView min = text("60%", 12, false);
        TextView max = text("140%", 12, false);
        range.addView(min, new LinearLayout.LayoutParams(0, -2, 1));
        max.setGravity(android.view.Gravity.RIGHT);
        range.addView(max, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(range);

        applyButton = button("Aplicar tamaño a todas las tarjetas");
        applyButton.setEnabled(false);
        page.addView(applyButton);
        applyButton.setOnClickListener(v -> applySizeToAll(0));

        appsLabel = text("Aplicaciones detectadas: —", 12, false);
        appsLabel.setPadding(0, 12, 0, 0);
        page.addView(appsLabel);

        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateSizeLabel();
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
        applyButton.setEnabled(false);
        sizeSeek.setEnabled(false);
        status.setText("Conectando…");

        request("GET", "/v1/device", null, device -> {
            status.setText("Conectado a " + device.optString("launcher", "Google TV PG")
                + " · " + device.optString("model", "TV Box"));
            loadApps();
        });
    }

    private void loadApps() {
        request("GET", "/v1/packages", null, data -> {
            JSONArray apps = data.optJSONArray("apps");
            installedApps = apps == null ? new JSONArray() : apps;
            connected = installedApps.length() > 0;
            appsLabel.setText("Aplicaciones detectadas: " + installedApps.length());
            sizeSeek.setEnabled(connected);
            applyButton.setEnabled(connected);
            if (connected) status.setText("Conectado correctamente. Elegí el tamaño y aplicalo.");
            else status.setText("Conectado, pero no se detectaron aplicaciones.");
        });
    }

    private void applySizeToAll(int index) {
        if (index == 0) {
            applyButton.setEnabled(false);
            sizeSeek.setEnabled(false);
            status.setText("Aplicando tamaño… 0/" + installedApps.length());
        }
        if (index >= installedApps.length()) {
            applyButton.setEnabled(true);
            sizeSeek.setEnabled(true);
            status.setText("Listo. Tamaño aplicado a " + installedApps.length() + " aplicaciones.");
            return;
        }

        JSONObject app = installedApps.optJSONObject(index);
        if (app == null) {
            applySizeToAll(index + 1);
            return;
        }
        String pkg = app.optString("packageName", "");
        if (pkg.isEmpty()) {
            applySizeToAll(index + 1);
            return;
        }

        int width = Math.max(100, Math.min(420, Math.round(196f * sizePercent / 100f)));
        int height = Math.max(60, Math.min(240, Math.round(110f * sizePercent / 100f)));
        try {
            JSONObject body = new JSONObject();
            body.put("packageName", pkg);
            body.put("cardWidthDp", width);
            body.put("cardHeightDp", height);
            request("POST", "/v1/launcher/app-config", body, response -> {
                status.setText("Aplicando tamaño… " + (index + 1) + "/" + installedApps.length());
                applySizeToAll(index + 1);
            });
        } catch (Exception e) {
            applyButton.setEnabled(true);
            sizeSeek.setEnabled(true);
            status.setText("No se pudo preparar el cambio para " + pkg + ".");
        }
    }

    private void updateSizeLabel() {
        int width = Math.round(196f * sizePercent / 100f);
        int height = Math.round(110f * sizePercent / 100f);
        sizeLabel.setText(sizePercent + "% · " + width + " × " + height + " dp");
    }

    private void request(String method, String path, JSONObject body, Done done) {
        String base = address.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + path).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Accept", "application/json");
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = connection.getResponseCode();
                InputStream input = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                JSONObject response = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (code >= 200 && code < 300) {
                        done.accept(response);
                    } else {
                        connected = false;
                        applyButton.setEnabled(false);
                        sizeSeek.setEnabled(false);
                        status.setText("Error " + code + ": " + response.optString("error", "Error de conexión"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connected = false;
                    applyButton.setEnabled(false);
                    sizeSeek.setEnabled(false);
                    status.setText("Sin conexión: " + e.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        if (bold) view.setTypeface(null, 1);
        return view;
    }

    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff315b91));
        return view;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}

package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private EditText hostField, portField;
    private TextView status, appsSummary, sizeValue;
    private ImageView preview;
    private SeekBar sizeSlider;
    private LinearLayout appNames;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService adbWorker = Executors.newSingleThreadExecutor();
    private String authToken = "";
    private int screenWidth = 1920, screenHeight = 1080;
    private float downX, downY;
    private boolean moved;
    private static final String PREFS = "pg_control_v2";
    private static final String LAUNCHER_PREF = "pg_launcher_preferences";
    private static final String TOKEN_PATH = "/data/data/com.gastonpg.replicagoogletv.bannereditor/shared_prefs/pg_control_connection.xml";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 19));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 19));
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(7, 11, 19));

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = label("PG CONTROL", 25, true);
        title.setLetterSpacing(0.08f);
        page.addView(title);
        page.addView(label("Réplica Google TV · Control remoto", 13, false));

        LinearLayout connection = panel();
        connection.addView(label("CONEXIÓN ADB", 13, true));
        hostField = edit("IP del TV Box", getPreferences(0).getString("host", "192.168.110.35"));
        portField = edit("Puerto ADB", getPreferences(0).getString("port", "3222"));
        connection.addView(hostField);
        connection.addView(portField);
        Button detect = action("DETECTAR TOKEN Y CONECTAR", true);
        connection.addView(detect);
        detect.setOnClickListener(v -> detectToken());
        status = label("Conectá el POCO y el TV Box a la misma red Wi-Fi.", 12, false);
        connection.addView(status);
        page.addView(connection);

        LinearLayout remote = panel();
        remote.addView(label("CONTROL REMOTO", 16, true));
        preview = new ImageView(this);
        preview.setBackground(round(0xff111c2c, 14, 0xff315b91));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setMinimumHeight(dp(170));
        remote.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
        preview.setOnTouchListener((v, event) -> handlePreviewTouch(event));
        Button refresh = action("ACTUALIZAR PANTALLA", false);
        remote.addView(refresh);
        refresh.setOnClickListener(v -> refreshScreen());

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        addKey(row1, "↑", "input keyevent 19");
        remote.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        addKey(row2, "←", "input keyevent 21");
        addKey(row2, "OK", "input keyevent 23");
        addKey(row2, "→", "input keyevent 22");
        remote.addView(row2);
        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER);
        addKey(row3, "↓", "input keyevent 20");
        remote.addView(row3);
        LinearLayout row4 = new LinearLayout(this);
        row4.setGravity(Gravity.CENTER);
        addKey(row4, "Atrás", "input keyevent 4");
        addKey(row4, "Inicio", "input keyevent 3");
        addKey(row4, "Recientes", "input keyevent 187");
        remote.addView(row4);
        page.addView(remote);

        LinearLayout size = panel();
        size.addView(label("TAMAÑO DE TARJETAS", 16, true));
        size.addView(label("Ajuste gradual conservando las filas horizontales de Google TV.", 12, false));
        sizeValue = label("196 dp", 14, true);
        size.addView(sizeValue);
        sizeSlider = new SeekBar(this);
        sizeSlider.setMax(100);
        sizeSlider.setProgress(40);
        sizeSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff3185e8));
        sizeSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff55a2ff));
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int width = 140 + Math.round(progress * 1.4f);
                sizeValue.setText(width + " dp");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        size.addView(sizeSlider);
        Button applySize = action("APLICAR TAMAÑO EN EL LAUNCHER", true);
        size.addView(applySize);
        applySize.setOnClickListener(v -> applyCardSize());
        appsSummary = label("Las aplicaciones se cargarán al conectar.", 12, false);
        size.addView(appsSummary);
        appNames = new LinearLayout(this);
        appNames.setOrientation(LinearLayout.VERTICAL);
        size.addView(appNames);
        page.addView(size);

        setContentView(root);
    }

    private boolean handlePreviewTouch(MotionEvent e) {
        if (preview.getDrawable() == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) return true;
        float x = Math.max(0, Math.min(1, e.getX() / preview.getWidth()));
        float y = Math.max(0, Math.min(1, e.getY() / preview.getHeight()));
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; moved = false; return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(x - downX) + Math.abs(y - downY) > 0.025f) moved = true;
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            final float sx = downX, sy = downY, ex = x, ey = y;
            if (moved) {
                adbCommand("input swipe " + px(sx) + " " + py(sy) + " " + px(ex) + " " + py(ey) + " 280", false, null);
            } else {
                adbCommand("input tap " + px(x) + " " + py(y), false, null);
            }
            return true;
        }
        return true;
    }

    private int px(float x) { return Math.max(0, Math.min(screenWidth - 1, Math.round(x * screenWidth))); }
    private int py(float y) { return Math.max(0, Math.min(screenHeight - 1, Math.round(y * screenHeight))); }

    private void detectToken() {
        final String host = hostField.getText().toString().trim();
        final int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (Exception e) { status.setText("Revisá el puerto ADB."); return; }
        if (host.isEmpty() || port < 1 || port > 65535) { status.setText("Revisá la IP y el puerto."); return; }
        getPreferences(0).edit().putString("host", host).putString("port", String.valueOf(port)).apply();
        status.setText("Conectando por ADB… Si aparece una autorización RSA en el TV Box, aceptala.");
        adbWorker.execute(() -> {
            try {
                String xml = runAdb(host, port, "su 0 cat " + TOKEN_PATH, false);
                Matcher m = Pattern.compile("<string name=\"token\">([^<]+)</string>").matcher(xml);
                if (!m.find()) throw new Exception("No se encontró el token en las preferencias del Launcher.");
                authToken = m.group(1).trim();
                getPreferences(0).edit().putString("token", authToken).apply();
                runOnUiThread(() -> {
                    status.setText("Token detectado. Verificando conexión con el Launcher…");
                    connectHttp();
                });
                String size = runAdb(host, port, "wm size", false);
                Matcher sm = Pattern.compile("(\\d+)x(\\d+)").matcher(size);
                if (sm.find()) { screenWidth = Integer.parseInt(sm.group(1)); screenHeight = Integer.parseInt(sm.group(2)); }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "No se pudo conectar por ADB." : e.getMessage();
                runOnUiThread(() -> status.setText("ADB: " + message));
            }
        });
    }

    private void connectHttp() {
        String base = "http://" + hostField.getText().toString().trim() + ":8765";
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/v1/device").openConnection();
                c.setConnectTimeout(7000); c.setReadTimeout(10000);
                c.setRequestProperty("Authorization", "Bearer " + authToken);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("El Launcher respondió HTTP " + code);
                String body = readText(c.getInputStream());
                JSONObject device = new JSONObject(body);
                runOnUiThread(() -> status.setText("Conectado a " + device.optString("launcher", "Google TV PG") + " · " + device.optString("model")));
                loadApps();
                refreshScreen();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "No se pudo verificar la conexión." : e.getMessage();
                runOnUiThread(() -> status.setText("Token detectado, pero HTTP: " + msg));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void loadApps() {
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                String base = "http://" + hostField.getText().toString().trim() + ":8765/v1/packages";
                c = (HttpURLConnection) new URL(base).openConnection();
                c.setConnectTimeout(7000); c.setReadTimeout(10000);
                c.setRequestProperty("Authorization", "Bearer " + authToken);
                JSONObject data = new JSONObject(readText(c.getInputStream()));
                JSONArray apps = data.optJSONArray("apps");
                List<String> names = new ArrayList<>();
                if (apps != null) for (int i=0;i<apps.length();i++) {
                    JSONObject a = apps.optJSONObject(i);
                    if (a != null) names.add(a.optString("label", a.optString("packageName")));
                }
                runOnUiThread(() -> {
                    appsSummary.setText(names.size() + " aplicaciones detectadas · distribución original conservada");
                    appNames.removeAllViews();
                    for (String name : names) appNames.addView(label("• " + name, 11, false));
                });
            } catch (Exception e) {
                runOnUiThread(() -> appsSummary.setText("No se pudieron cargar las aplicaciones."));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void applyCardSize() {
        final int width = 140 + Math.round(sizeSlider.getProgress() * 1.4f);
        final int height = Math.round(width * 9f / 16f);
        status.setText("Aplicando " + width + " × " + height + " dp…");
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                String base = "http://" + hostField.getText().toString().trim() + ":8765";
                c = (HttpURLConnection) new URL(base + "/v1/packages").openConnection();
                c.setRequestProperty("Authorization", "Bearer " + authToken);
                JSONObject data = new JSONObject(readText(c.getInputStream()));
                JSONArray apps = data.optJSONArray("apps");
                if (apps == null) throw new Exception("El Launcher no devolvió aplicaciones.");
                for (int i=0;i<apps.length();i++) {
                    JSONObject app = apps.optJSONObject(i);
                    if (app == null) continue;
                    JSONObject body = new JSONObject();
                    body.put("packageName", app.optString("packageName"));
                    body.put("cardWidthDp", width);
                    body.put("cardHeightDp", height);
                    HttpURLConnection post = (HttpURLConnection) new URL(base + "/v1/launcher/app-config").openConnection();
                    post.setRequestMethod("POST"); post.setDoOutput(true);
                    post.setConnectTimeout(7000); post.setReadTimeout(10000);
                    post.setRequestProperty("Authorization", "Bearer " + authToken);
                    post.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = post.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                    int code = post.getResponseCode();
                    if (code < 200 || code >= 300) throw new Exception("Error " + code + " al actualizar " + app.optString("packageName"));
                    post.disconnect();
                }
                runOnUiThread(() -> status.setText("Tamaño aplicado a " + apps.length() + " aplicaciones."));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "No se pudo aplicar el tamaño." : e.getMessage();
                runOnUiThread(() -> status.setText(msg));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void refreshScreen() {
        final String host = hostField.getText().toString().trim();
        final int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (Exception e) { return; }
        adbCommand("screencap -p", false, bytes -> {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                screenWidth = bmp.getWidth(); screenHeight = bmp.getHeight();
                runOnUiThread(() -> preview.setImageBitmap(bmp));
            }
        });
    }

    private interface BytesDone { void accept(byte[] bytes); }

    private void addKey(LinearLayout row, String text, String command) {
        Button b = action(text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(b, lp);
        b.setOnClickListener(v -> adbCommand(command, false, null));
    }

    private void adbCommand(String command, boolean root, BytesDone done) {
        final String host = hostField.getText().toString().trim();
        final int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (Exception e) { return; }
        adbWorker.execute(() -> {
            try {
                byte[] bytes = runAdbBytes(host, port, (root ? "su 0 " : "") + command);
                if (done != null) done.accept(bytes);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "ADB falló." : e.getMessage();
                runOnUiThread(() -> status.setText("ADB: " + msg));
            }
        });
    }

    private String runAdb(String host, int port, String command, boolean ignored) throws Exception {
        return new String(runAdbBytes(host, port, command), StandardCharsets.UTF_8);
    }

    private byte[] runAdbBytes(String host, int port, String command) throws Exception {
        AdbBase64 encoder = data -> Base64.encodeToString(data, Base64.NO_WRAP);
        File priv = new File(getFilesDir(), "pg_adbkey");
        File pub = new File(getFilesDir(), "pg_adbkey.pub");
        AdbCrypto crypto;
        if (priv.exists() && pub.exists()) crypto = AdbCrypto.loadAdbKeyPair(encoder, priv, pub);
        else {
            crypto = AdbCrypto.generateAdbKeyPair(encoder);
            crypto.saveAdbKeyPair(priv, pub);
        }
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(15000);
        AdbConnection connection = null;
        AdbStream stream = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            connection = AdbConnection.create(socket, crypto);
            if (!connection.connect(45, TimeUnit.SECONDS, false)) throw new Exception("Tiempo de espera agotado. Aceptá la clave RSA en el TV Box.");
            stream = connection.open("shell:" + command);
            try {
                while (true) {
                    byte[] part = stream.read();
                    if (part == null || part.length == 0) break;
                    output.write(part);
                    if (output.size() > 12 * 1024 * 1024) throw new Exception("La respuesta ADB es demasiado grande.");
                }
            } catch (java.io.IOException closed) {
                // The shell closes its stream when the command finishes.
            }
            return output.toByteArray();
        } finally {
            try { if (stream != null) stream.close(); } catch (Exception ignored) {}
            try { if (connection != null) connection.close(); else socket.close(); } catch (Exception ignored) {}
        }
    }

    private String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private LinearLayout panel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        box.setBackground(round(0xff111c2c, 16, 0xff263b59));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private EditText edit(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true); e.setHint(hint); e.setText(value);
        e.setTextColor(Color.WHITE); e.setHintTextColor(0xff8292a8);
        e.setBackground(round(0xff1b2940, 10, 0xff315b91));
        e.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(7); e.setLayoutParams(lp);
        return e;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(null, 1);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private Button action(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackground(round(primary ? 0xff1769c2 : 0xff24446d, 12, 0xff397fc8));
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        adbWorker.shutdownNow();
    }
}

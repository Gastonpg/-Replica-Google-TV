package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int BG = 0xff090d14, PANEL = 0xff151c27, PANEL2 = 0xff1c2634;
    private static final int BLUE = 0xff258cff, MUTED = 0xff9ba9bb;
    private EditText address, token, adbPort;
    private TextView status, sizeLabel, appsLabel, deviceLabel, percentLabel, remoteStatus;
    private SeekBar sizeSeek;
    private Button applyButton;
    private JSONArray installedApps = new JSONArray();
    private int sizePercent = 100;
    private boolean connected = false;
    private boolean remoteVisible = false;
    private boolean touchpadMode = false;
    private ImageView screenPreview;
    private Bitmap currentScreen;
    private float touchStartX, touchStartY;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbBridge adb;

    interface Done { void accept(JSONObject response); }
    interface AdbWork { void run(String host, int port) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        adb = new AdbBridge(this);
        showHome();
    }

    private void showHome() {
        remoteVisible = false;
        main.removeCallbacks(captureLoop);
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

        LinearLayout adbLine = new LinearLayout(this);
        adbLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView adbLabel = text("Puerto ADB", 13, true, MUTED);
        adbLine.addView(adbLabel, new LinearLayout.LayoutParams(0, -2, 1));
        adbPort = new EditText(this);
        adbPort.setSingleLine(true);
        adbPort.setInputType(2);
        adbPort.setText(getPreferences(0).getString("adbPort", "3222"));
        adbPort.setEms(6);
        styleEdit(adbPort);
        adbLine.addView(adbPort, new LinearLayout.LayoutParams(dp(92), -2));
        connection.addView(adbLine);

        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(129);
        token.setText(getPreferences(0).getString("token", ""));
        token.setHint("Token del TV Box (se detecta automáticamente)");
        styleEdit(token);
        connection.addView(token);

        Button detect = button("Detectar token y conectar automáticamente", BLUE);
        connection.addView(detect);
        detect.setOnClickListener(v -> detectTokenAndConnect());

        Button manualConnect = button("Conectar con token guardado", PANEL2);
        connection.addView(manualConnect);
        manualConnect.setOnClickListener(v -> connect());

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
        remoteText.addView(text("Vista en vivo · táctil · touchpad · apps abiertas", 11, false, MUTED));
        remoteRow.addView(remoteText, new LinearLayout.LayoutParams(0,-2,1));
        remote.addView(remoteRow);
        Button remoteButton = button("Abrir panel remoto", PANEL2);
        remoteButton.setOnClickListener(v -> openRemote());
        remote.addView(remoteButton);
        root.addView(remote);

        scroll.addView(root);
        setContentView(scroll);
        updateSizeLabel();
    }

    private void detectTokenAndConnect() {
        final String host = hostFromAddress();
        final int port = readAdbPort();
        if (host == null || port < 1) {
            status.setText("Revisá la dirección del TV Box y el puerto ADB.");
            return;
        }
        getPreferences(0).edit().putString("address", address.getText().toString().trim())
            .putString("adbPort", String.valueOf(port)).apply();
        status.setText("Buscando el token actual del Launcher por ADB…");
        io.execute(() -> {
            try {
                String found = adb.readToken(host, port);
                runOnUiThread(() -> {
                    if (isFinishing() || token == null) return;
                    token.setText(found);
                    getPreferences(0).edit().putString("token", found).apply();
                    status.setText("Token vigente detectado. Verificando conexión…");
                    connect();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("No se pudo leer el token por ADB: " + shortError(e));
                });
            }
        });
    }

    private String hostFromAddress() {
        try { return new URL(address.getText().toString().trim()).getHost(); }
        catch (Exception e) { return null; }
    }

    private int readAdbPort() {
        try { return Integer.parseInt(adbPort.getText().toString().trim()); }
        catch (Exception e) { return 0; }
    }

    private void connect() {
        String base = address.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty()) {
            status.setText("Usá «Detectar token y conectar automáticamente» para obtener el token vigente.");
            return;
        }
        getPreferences(0).edit().putString("address", base).putString("token", key).apply();
        connected = false;
        applyButton.setEnabled(false); applyButton.setAlpha(.48f); sizeSeek.setEnabled(false);
        status.setText("Conectando con el Launcher…");
        request("GET", "/v1/device", null, device -> {
            deviceLabel.setText("TV Box: " + device.optString("model", "Dispositivo conectado") +
                " · Android " + device.optString("androidRelease", device.optString("androidVersion", "—")));
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
            status.setText("Conectado correctamente. Ya podés ajustar las tarjetas y abrir el control remoto.");
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
                connection.setRequestMethod(method);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Accept", "application/json");
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body.toString().getBytes("UTF-8"));
                    }
                }
                int code = connection.getResponseCode();
                InputStream input = code < 400 ? connection.getInputStream() : connection.getErrorStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (input != null) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                    input.close();
                }
                JSONObject response = new JSONObject(new String(output.toByteArray(), "UTF-8"));
                runOnUiThread(() -> {
                    if (code >= 200 && code < 300) done.accept(response);
                    else {
                        connected = false;
                        if (applyButton != null) { applyButton.setEnabled(false); applyButton.setAlpha(.48f); }
                        if (sizeSeek != null) sizeSeek.setEnabled(false);
                        if (status != null) status.setText("Error " + code + ": " + response.optString("error", "Error de conexión"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connected = false;
                    if (applyButton != null) { applyButton.setEnabled(false); applyButton.setAlpha(.48f); }
                    if (sizeSeek != null) sizeSeek.setEnabled(false);
                    if (status != null) status.setText("Sin conexión: " + shortError(e));
                    if (deviceLabel != null) deviceLabel.setText("TV Box: sin conectar");
                });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void openRemote() {
        if (token == null || token.getText().toString().trim().isEmpty()) {
            status.setText("Primero detectá el token y conectá el TV Box.");
            return;
        }
        showRemote();
    }

    private void showRemote() {
        remoteVisible = true;
        touchpadMode = false;
        LinearLayout root = column();
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = smallButton("‹  Inicio", PANEL2);
        back.setOnClickListener(v -> showHome());
        header.addView(back);
        TextView title = text("CONTROL REMOTO", 16, true, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button refresh = smallButton("↻", PANEL2);
        refresh.setOnClickListener(v -> captureNow());
        header.addView(refresh);
        root.addView(header);

        remoteStatus = text("Conectando con la pantalla del TV Box…", 11, false, MUTED);
        remoteStatus.setPadding(dp(4), dp(4), dp(4), dp(7));
        root.addView(remoteStatus);

        FrameLayout screenFrame = new FrameLayout(this);
        screenFrame.setBackground(round(PANEL, 0xff34465c, 12));
        screenPreview = new ImageView(this);
        screenPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenPreview.setAdjustViewBounds(false);
        screenFrame.addView(screenPreview, new FrameLayout.LayoutParams(-1, -1));
        screenPreview.setOnTouchListener((view, event) -> handleScreenTouch(event));
        LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        screenLp.bottomMargin = dp(9);
        root.addView(screenFrame, screenLp);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        Button touch = smallButton("Modo táctil: pantalla", PANEL2);
        touch.setOnClickListener(v -> {
            touchpadMode = !touchpadMode;
            touch.setText(touchpadMode ? "Modo touchpad: direcciones" : "Modo táctil: pantalla");
            remoteStatus.setText(touchpadMode
                ? "Touchpad activo: deslizá para navegar y tocá para aceptar."
                : "Tocá la imagen para pulsar en el TV Box; arrastrá para deslizar.");
        });
        modeRow.addView(touch, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button tasks = smallButton("Multitarea", BLUE);
        tasks.setOnClickListener(v -> showTasksDialog());
        LinearLayout.LayoutParams taskLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        taskLp.leftMargin = dp(8);
        modeRow.addView(tasks, taskLp);
        root.addView(modeRow);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        addKeyButton(nav, "Atrás", 4);
        addKeyButton(nav, "Inicio", 3);
        addKeyButton(nav, "Recientes", 187);
        addKeyButton(nav, "OK", 23);
        root.addView(nav);

        LinearLayout dpad = new LinearLayout(this);
        dpad.setGravity(Gravity.CENTER);
        LinearLayout padCol = column();
        padCol.setGravity(Gravity.CENTER);
        addKeyButton(padCol, "▲", 19);
        LinearLayout middle = new LinearLayout(this);
        middle.setGravity(Gravity.CENTER);
        addKeyButton(middle, "◀", 21);
        addKeyButton(middle, "●", 23);
        addKeyButton(middle, "▶", 22);
        padCol.addView(middle);
        addKeyButton(padCol, "▼", 20);
        dpad.addView(padCol);
        root.addView(dpad);

        setContentView(root);
        captureNow();
    }

    private boolean handleScreenTouch(MotionEvent event) {
        if (currentScreen == null || screenPreview == null) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getX();
            touchStartY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - touchStartX;
            float dy = event.getY() - touchStartY;
            if (touchpadMode) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(18)) sendKey(23);
                else if (Math.abs(dx) > Math.abs(dy)) sendKey(dx > 0 ? 22 : 21);
                else sendKey(dy > 0 ? 20 : 19);
                return true;
            }
            int[] start = mapToScreen(touchStartX, touchStartY);
            int[] end = mapToScreen(event.getX(), event.getY());
            final int duration = Math.max(120, Math.min(900, (int)(event.getEventTime()-event.getDownTime())));
            if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(12)) {
                sendAdb((h,p) -> adb.tap(h,p,end[0],end[1]));
            } else {
                sendAdb((h,p) -> adb.swipe(h,p,start[0],start[1],end[0],end[1],duration));
            }
            return true;
        }
        return true;
    }

    private int[] mapToScreen(float x, float y) {
        int vw = screenPreview.getWidth(), vh = screenPreview.getHeight();
        int bw = currentScreen.getWidth(), bh = currentScreen.getHeight();
        float scale = Math.min((float)vw / bw, (float)vh / bh);
        float left = (vw - bw * scale) / 2f;
        float top = (vh - bh * scale) / 2f;
        int sx = Math.round((x - left) / scale);
        int sy = Math.round((y - top) / scale);
        return new int[]{Math.max(0, Math.min(bw - 1, sx)), Math.max(0, Math.min(bh - 1, sy))};
    }

    private void addKeyButton(LinearLayout parent, String label, int keyCode) {
        Button b = smallButton(label, PANEL2);
        b.setOnClickListener(v -> sendKey(keyCode));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        parent.addView(b, lp);
    }

    private void sendKey(int code) {
        sendAdb((h,p) -> adb.key(h,p,code));
    }

    private void sendAdb(AdbWork work) {
        String host = hostFromAddress();
        int port = readAdbPort();
        if (host == null || port < 1) return;
        io.execute(() -> {
            try { work.run(host, port); }
            catch (Exception e) {
                runOnUiThread(() -> { if (remoteStatus != null) remoteStatus.setText("ADB: " + shortError(e)); });
            }
        });
    }

    private final Runnable captureLoop = new Runnable() {
        @Override public void run() {
            if (!remoteVisible) return;
            captureNow();
        }
    };

    private void captureNow() {
        if (!remoteVisible) return;
        final String host = hostFromAddress();
        final int port = readAdbPort();
        if (host == null || port < 1) return;
        io.execute(() -> {
            try {
                byte[] bytes = adb.screenshot(host, port);
                BitmapFactory.Options previewOptions = new BitmapFactory.Options();
                previewOptions.inSampleSize = 3;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, previewOptions);
                if (bitmap == null) throw new Exception("No se pudo decodificar la captura.");
                runOnUiThread(() -> {
                    if (!remoteVisible || screenPreview == null) { bitmap.recycle(); return; }
                    Bitmap old = currentScreen;
                    currentScreen = bitmap;
                    screenPreview.setImageBitmap(bitmap);
                    if (remoteStatus != null) remoteStatus.setText("Pantalla en vivo · " + bitmap.getWidth() + " × " + bitmap.getHeight());
                    if (old != null && old != bitmap) old.recycle();
                    main.removeCallbacks(captureLoop);
                    main.postDelayed(captureLoop, 800);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (remoteStatus != null) remoteStatus.setText("No se pudo obtener la pantalla: " + shortError(e));
                    main.removeCallbacks(captureLoop);
                    main.postDelayed(captureLoop, 2500);
                });
            }
        });
    }

    private static final class TaskEntry {
        int id;
        String pkg;
    }

    private List<TaskEntry> parseTasks(String dump) {
        List<TaskEntry> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Pattern pattern = Pattern.compile("(?m)^\\s*\\* Recent #\\d+: (?:TaskRecord|Task)\\{[^\\n]*?#(\\d+)[^\\n]*?\\bA=([^\\s}]+)");
        Matcher matcher = pattern.matcher(dump);
        while (matcher.find() && tasks.size() < 40) {
            try {
                int id = Integer.parseInt(matcher.group(1));
                String name = matcher.group(2);
                String pkg = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (pkg.equals("com.gastonpg.replicagoogletv.bannereditor") ||
                    pkg.equals("com.android.systemui") || pkg.equals("android") ||
                    !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || !seen.add(pkg)) continue;
                TaskEntry entry = new TaskEntry();
                entry.id = id; entry.pkg = pkg; tasks.add(entry);
            } catch (Exception ignored) { }
        }
        // Fallback for Android builds that print task components instead of A=package.
        if (tasks.isEmpty()) {
            Pattern componentPattern = Pattern.compile("(?m)^\\s*\\* Recent #\\d+: (?:TaskRecord|Task)\\{[^\\n]*?#(\\d+)[^\\n]*?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[^\\s}]+");
            Matcher componentMatcher = componentPattern.matcher(dump);
            while (componentMatcher.find() && tasks.size() < 40) {
                try {
                    int id = Integer.parseInt(componentMatcher.group(1));
                    String pkg = componentMatcher.group(2);
                    if (pkg.equals("com.gastonpg.replicagoogletv.bannereditor") ||
                        pkg.equals("com.android.systemui") || pkg.equals("android") || !seen.add(pkg)) continue;
                    TaskEntry entry = new TaskEntry();
                    entry.id = id; entry.pkg = pkg; tasks.add(entry);
                } catch (Exception ignored) { }
            }
        }
        return tasks;
    }

    private void showTasksDialog() {
        if (remoteStatus != null) remoteStatus.setText("Consultando aplicaciones abiertas…");
        String host = hostFromAddress();
        int port = readAdbPort();
        io.execute(() -> {
            try {
                List<TaskEntry> tasks = parseTasks(adb.recentTasks(host, port));
                runOnUiThread(() -> buildTasksDialog(tasks, host, port));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (remoteStatus != null) remoteStatus.setText("No se pudieron consultar las aplicaciones: " + shortError(e));
                });
            }
        });
    }

    private void buildTasksDialog(List<TaskEntry> tasks, String host, int port) {
        LinearLayout content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(8));
        content.setBackgroundColor(BG);
        TextView heading = text("APLICACIONES ABIERTAS", 17, true, Color.WHITE);
        content.addView(heading);
        TextView hint = text(tasks.isEmpty() ? "No se detectaron tareas recientes." : "Elegí una app para volver a ella o cerrarla.", 12, false, MUTED);
        hint.setPadding(0, dp(6), 0, dp(12));
        content.addView(hint);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        for (TaskEntry task : tasks) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setBackground(round(PANEL, 0xff29384b, 12));
            TextView name = text(task.pkg, 12, true, Color.WHITE);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button open = smallButton("Abrir", BLUE);
            open.setOnClickListener(v -> {
                runTaskAction(() -> adb.launchApp(host, port, task.pkg), "Abriendo " + task.pkg);
            });
            row.addView(open);
            Button close = smallButton("×", 0xff71313b);
            close.setOnClickListener(v -> {
                runTaskAction(() -> adb.closeTask(host, port, task.pkg), "Cerrando " + task.pkg);
                list.removeView(row);
            });
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(46), dp(40));
            closeLp.leftMargin = dp(5);
            row.addView(close, closeLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(56));
            rowLp.bottomMargin = dp(6);
            list.addView(row, rowLp);
        }
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        Button closeAll = smallButton("Cerrar todas las aplicaciones", 0xff71313b);
        closeAll.setOnClickListener(v -> {
            dialog.dismiss();
            io.execute(() -> {
                for (TaskEntry task : tasks) {
                    try { adb.closeTask(host, port, task.pkg); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> { if (remoteStatus != null) remoteStatus.setText("Se enviaron las órdenes para cerrar las aplicaciones."); });
            });
        });
        content.addView(closeAll);
        dialog.setView(content);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Listo", (d,w) -> d.dismiss());
        dialog.show();
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(520)), dp(520));
    }

    private interface TaskAction { void run() throws Exception; }
    private void runTaskAction(TaskAction action, String message) {
        if (remoteStatus != null) remoteStatus.setText(message + "…");
        io.execute(() -> {
            try {
                action.run();
                runOnUiThread(() -> { if (remoteStatus != null) remoteStatus.setText(message + " correctamente."); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (remoteStatus != null) remoteStatus.setText("Error: " + shortError(e)); });
            }
        });
    }

    private String shortError(Exception e) {
        String m = e == null ? "" : e.getMessage();
        if (m == null || m.trim().isEmpty()) return "error desconocido";
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout panel() {
        LinearLayout l = column();
        l.setPadding(dp(15),dp(14),dp(15),dp(14));
        l.setBackground(round(PANEL, 0xff263548, 17));
        return l;
    }
    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), stroke);
        return bg;
    }
    private TextView text(String value,int size,boolean bold,int color) {
        TextView v=new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private TextView sectionTitle(String value) {
        TextView v=text(value,16,true,Color.WHITE);
        v.setPadding(dp(2),0,0,dp(9));
        return v;
    }
    private TextView pill(String value,int color) {
        TextView v=text(value,12,true,color);
        v.setPadding(dp(10),dp(6),dp(10),dp(6));
        v.setBackground(round(0xff102b49, 0xff173a5f, 30));
        return v;
    }
    private Button button(String value,int color) {
        Button b=new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(14); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return b;
    }
    private Button smallButton(String value,int color) {
        Button b=button(value,color);
        b.setTextSize(12);
        return b;
    }
    private void styleEdit(EditText e) {
        e.setTextColor(Color.WHITE); e.setHintTextColor(MUTED); e.setTextSize(14);
        e.setSingleLine(true); e.setPadding(dp(12),dp(10),dp(12),dp(10));
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
    }
    private void addGap(LinearLayout l,int h) {
        View v=new View(this);
        l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        remoteVisible = false;
        main.removeCallbacks(captureLoop);
        if (currentScreen != null) { currentScreen.recycle(); currentScreen = null; }
        if (adb != null) adb.close();
        io.shutdownNow();
    }
}

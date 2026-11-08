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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private boolean touchpadMode = true;
    private ImageView screenPreview;
    private Bitmap currentScreen;
    private float touchStartX, touchStartY;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Keep live preview traffic isolated so screenshots never block remote-control commands.
    private final ExecutorService previewIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean captureInFlight = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbBridge adb;
    private AdbBridge previewAdb;
    // Task/volume commands use their own ADB connection so they cannot interrupt uinput.
    private AdbBridge taskAdb;
    private SeekBar volumeSeek;
    private TextView volumeValue;
    private int volumeMin = 0, volumeMax = 25, volumeCurrent = 0;
    private final ExecutorService volumeIo = Executors.newSingleThreadExecutor();

    interface Done { void accept(JSONObject response); }
    interface AdbWork { void run(String host, int port) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        adb = new AdbBridge(this);
        previewAdb = new AdbBridge(this);
        taskAdb = new AdbBridge(this);
        showHome();
    }

    private void showHome() {
        remoteVisible = false;
        nativeMouseReady = false;
        mouseIo.execute(() -> adb.stopNativeMouse());
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
        touchpadMode = true;
        main.removeCallbacks(captureLoop);
        LinearLayout root = column();
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        root.setBackgroundColor(BG);

        // Header preserved from the original remote layout.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = smallButton("‹  Inicio", PANEL2);
        back.setOnClickListener(v -> showHome());
        header.addView(back);
        TextView title = text("CONTROL REMOTO", 16, true, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button refresh = smallButton("↻", PANEL2);
        refresh.setOnClickListener(v -> {
            if (remoteStatus != null) remoteStatus.setText("Control directo activo.");
        });
        header.addView(refresh);
        root.addView(header);

        remoteStatus = text("Control directo · elegí el modo de uso", 11, false, MUTED);
        remoteStatus.setPadding(dp(4), dp(4), dp(4), dp(7));
        root.addView(remoteStatus);

        // Centro: touchpad arriba; controles físicos separados debajo.
        FrameLayout controlFrame = new FrameLayout(this);
        controlFrame.setBackground(round(PANEL, 0xff34465c, 12));
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        controlLp.bottomMargin = dp(9);
        root.addView(controlFrame, controlLp);

        LinearLayout touchModePanel = column();
        touchModePanel.setPadding(dp(7), dp(7), dp(7), dp(7));
        controlFrame.addView(touchModePanel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout touchSurface = column();
        touchSurface.setGravity(Gravity.CENTER);
        touchSurface.setBackground(round(0xff101b2a, 0xff258cff, 12));
        TextView touchIcon = text("✥", 48, true, 0xff8fc5ff);
        touchIcon.setGravity(Gravity.CENTER);
        touchSurface.addView(touchIcon);
        TextView touchLabel = text("DESLIZÁ PARA MOVER EL PUNTERO", 12, true, MUTED);
        touchLabel.setGravity(Gravity.CENTER);
        touchLabel.setPadding(0, dp(12), 0, 0);
        touchSurface.addView(touchLabel);
        LinearLayout.LayoutParams touchLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        touchLp.bottomMargin = dp(8);
        touchModePanel.addView(touchSurface, touchLp);
        touchSurface.setOnTouchListener((view, event) -> handleMousePad(event, view));

        // Una sola fila inferior: Atrás a la izquierda y cruceta completa a la derecha.
        LinearLayout mouseButtons = new LinearLayout(this);
        mouseButtons.setGravity(Gravity.CENTER_VERTICAL);
        Button backKey = smallButton("‹  ATRÁS", PANEL2);
        backKey.setOnClickListener(v -> sendKey(4));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dp(46), 4f);
        backLp.rightMargin = dp(8);
        mouseButtons.addView(backKey, backLp);

        LinearLayout arrows = column();
        arrows.setGravity(Gravity.CENTER);
        LinearLayout arrowTop = new LinearLayout(this);
        arrowTop.setGravity(Gravity.CENTER);
        addDirectionalButton(arrowTop, "▲", 19);
        arrows.addView(arrowTop);
        LinearLayout arrowMiddle = new LinearLayout(this);
        arrowMiddle.setGravity(Gravity.CENTER);
        addDirectionalButton(arrowMiddle, "◀", 21);
        addDirectionalButton(arrowMiddle, "▶", 22);
        arrows.addView(arrowMiddle);
        LinearLayout arrowBottom = new LinearLayout(this);
        arrowBottom.setGravity(Gravity.CENTER);
        addDirectionalButton(arrowBottom, "▼", 20);
        arrows.addView(arrowBottom);
        mouseButtons.addView(arrows, new LinearLayout.LayoutParams(0, dp(112), 6f));
        touchModePanel.addView(mouseButtons);

        // El modo Flechas/OK se elimina: el touchpad queda siempre activo y tocarlo hace clic.
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        Button tasks = smallButton("Multitarea", BLUE);
        tasks.setOnClickListener(v -> showTasksDialog());
        modeRow.addView(tasks, new LinearLayout.LayoutParams(-1, dp(42)));
        root.addView(modeRow);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        addKeyButton(nav, "Inicio", 3);
        addKeyButton(nav, "Recientes", 187);
        root.addView(nav);

        // Volumen del TV Box, independiente del control físico.
        LinearLayout volumePanel = panel();
        LinearLayout volumeHead = new LinearLayout(this);
        volumeHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView volumeTitle = text("VOLUMEN", 12, true, MUTED);
        volumeHead.addView(volumeTitle, new LinearLayout.LayoutParams(0, -2, 1));
        volumeValue = text("Leyendo…", 12, true, Color.WHITE);
        volumeHead.addView(volumeValue);
        volumePanel.addView(volumeHead);
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(0);
        volumeSeek.setEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            volumeSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
            volumeSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(BLUE));
            volumeSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff354254));
        }
        volumePanel.addView(volumeSeek);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && volumeValue != null) volumeValue.setText(progress + "%");
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) {
                if (volumeMax > volumeMin) applyVolume(bar.getProgress());
            }
        });
        root.addView(volumePanel);

        setContentView(root);
        startNativeMouse();
        loadVolume();
    }

    private final AtomicBoolean mouseMoveRunning = new AtomicBoolean(false);
    private final ExecutorService mouseIo = Executors.newSingleThreadExecutor();
    private final ExecutorService keyIo = Executors.newSingleThreadExecutor();
    private float mouseDownX, mouseDownY, mouseLastX, mouseLastY;
    private boolean mouseMoved = false;
    private volatile boolean nativeMouseReady = false;
    private final AtomicInteger pendingMouseDx = new AtomicInteger(0);
    private final AtomicInteger pendingMouseDy = new AtomicInteger(0);
    private final AtomicBoolean mouseRestartPending = new AtomicBoolean(false);

    private void recoverNativeMouse(Exception cause) {
        nativeMouseReady = false;
        if (!remoteVisible || !mouseRestartPending.compareAndSet(false, true)) return;
        runOnUiThread(() -> {
            if (remoteVisible && remoteStatus != null)
                remoteStatus.setText("Reconectando el mouse virtual…");
        });
        // Force a fresh ADB transport before recreating the persistent uinput process.
        mouseIo.execute(() -> adb.close());
        main.postDelayed(() -> {
            mouseRestartPending.set(false);
            if (remoteVisible) startNativeMouse();
        }, 500);
    }

    private void startNativeMouse() {
        nativeMouseReady = false;
        mouseIo.execute(() -> {
            try {
                String host = hostFromAddress();
                int port = readAdbPort();
                if (host == null || port < 1) throw new Exception("Dirección ADB no válida.");
                InputStream input = getAssets().open("pgmouse");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                input.close();
                adb.startNativeMouse(host, port, output.toByteArray());
                nativeMouseReady = true;
                runOnUiThread(() -> {
                    if (remoteVisible && remoteStatus != null)
                        remoteStatus.setText("Mouse virtual activo · movimiento directo");
                });
            } catch (Exception e) {
                nativeMouseReady = false;
                runOnUiThread(() -> {
                    if (remoteVisible && remoteStatus != null)
                        remoteStatus.setText("No se pudo iniciar el mouse virtual: " + shortError(e));
                });
            }
        });
    }

    private void queueMouseDelta(int dx, int dy) {
        if (!nativeMouseReady) return;
        pendingMouseDx.addAndGet(dx);
        pendingMouseDy.addAndGet(dy);
        if (!mouseMoveRunning.compareAndSet(false, true)) return;
        drainMouseMoves();
    }

    private void drainMouseMoves() {
        mouseIo.execute(() -> {
            try {
                while (nativeMouseReady) {
                    int dx = pendingMouseDx.getAndSet(0);
                    int dy = pendingMouseDy.getAndSet(0);
                    if (dx == 0 && dy == 0) break;
                    adb.nativeMouseMove(dx, dy);
                }
            } catch (Exception e) {
                nativeMouseReady = false;
                runOnUiThread(() -> {
                    if (remoteVisible && remoteStatus != null)
                        remoteStatus.setText("Mouse virtual: " + shortError(e));
                });
                recoverNativeMouse(e);
            } finally {
                mouseMoveRunning.set(false);
                if (nativeMouseReady && (pendingMouseDx.get() != 0 || pendingMouseDy.get() != 0) &&
                    mouseMoveRunning.compareAndSet(false, true)) drainMouseMoves();
            }
        });
    }

    private boolean handleMousePad(MotionEvent event, View surface) {
        if (!touchpadMode) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mouseDownX = mouseLastX = event.getX();
            mouseDownY = mouseLastY = event.getY();
            mouseMoved = false;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - mouseLastX;
            float dy = event.getY() - mouseLastY;
            if (Math.abs(event.getX() - mouseDownX) + Math.abs(event.getY() - mouseDownY) > dp(7)) mouseMoved = true;
            if (mouseMoved) {
                queueMouseDelta(Math.round(dx * 2.0f), Math.round(dy * 2.0f));
            }
            mouseLastX = event.getX();
            mouseLastY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (!mouseMoved) sendMouseClick();
            return true;
        }
        return true;
    }

    private void sendMouseClick() {
        if (!nativeMouseReady) {
            if (remoteStatus != null) remoteStatus.setText("Esperá a que se active el mouse virtual.");
            return;
        }
        mouseIo.execute(() -> {
            try { adb.nativeMouseClick(); }
            catch (Exception e) {
                nativeMouseReady = false;
                runOnUiThread(() -> {
                    if (remoteVisible && remoteStatus != null)
                        remoteStatus.setText("Clic virtual: " + shortError(e));
                });
                recoverNativeMouse(e);
            }
        });
    }

    private boolean handleScreenTouch(MotionEvent event) {
        if (touchpadMode) return true;
        if (currentScreen == null || screenPreview == null) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getX();
            touchStartY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - touchStartX;
            float dy = event.getY() - touchStartY;
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

    private void addDirectionalButton(LinearLayout parent, String label, int keyCode) {
        Button b = smallButton(label, PANEL2);
        b.setTextSize(17);
        b.setOnClickListener(v -> sendKey(keyCode));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(34));
        lp.setMargins(dp(3), dp(1), dp(3), dp(1));
        parent.addView(b, lp);
    }

    private void addKeyButton(LinearLayout parent, String label, int keyCode) {
        Button b = smallButton(label, PANEL2);
        b.setOnClickListener(v -> sendKey(keyCode));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        parent.addView(b, lp);
    }

    private void sendKey(int code) {
        if (code >= 19 && code <= 22) {
            if (!nativeMouseReady) {
                if (remoteVisible) startNativeMouse();
                return;
            }
            keyIo.execute(() -> {
                try { adb.nativeMouseKey(code); }
                catch (Exception e) {
                    runOnUiThread(() -> { if (remoteStatus != null) remoteStatus.setText("Tecla direccional: " + shortError(e)); });
                    recoverNativeMouse(e);
                }
            });
            return;
        }
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
        if (!captureInFlight.compareAndSet(false, true)) return;
        previewIo.execute(() -> {
            try {
                byte[] bytes = previewAdb.screenshot(host, port);
                BitmapFactory.Options previewOptions = new BitmapFactory.Options();
                previewOptions.inSampleSize = 3;
                previewOptions.inPreferredConfig = Bitmap.Config.RGB_565;
                previewOptions.inDither = false;
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, previewOptions);
                if (decoded == null) throw new Exception("No se pudo decodificar la captura.");
                // Android decoders may round inSampleSize=3 down to 2; normalize the preview to 640x360.
                Bitmap bitmap = (decoded.getWidth() == 640 && decoded.getHeight() == 360)
                    ? decoded : Bitmap.createScaledBitmap(decoded, 640, 360, false);
                if (bitmap != decoded) decoded.recycle();
                runOnUiThread(() -> {
                    if (!remoteVisible || screenPreview == null) { bitmap.recycle(); return; }
                    Bitmap old = currentScreen;
                    currentScreen = bitmap;
                    screenPreview.setImageBitmap(bitmap);
                    if (remoteStatus != null) remoteStatus.setText("Pantalla en vivo · " + bitmap.getWidth() + " × " + bitmap.getHeight());
                    if (old != null && old != bitmap) old.recycle();
                    main.removeCallbacks(captureLoop);
                    main.postDelayed(captureLoop, 250);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (remoteStatus != null) remoteStatus.setText("No se pudo obtener la pantalla: " + shortError(e));
                    main.removeCallbacks(captureLoop);
                    main.postDelayed(captureLoop, 1200);
                });
            } finally {
                captureInFlight.set(false);
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
                String dump;
                try {
                    dump = taskAdb.recentTasks(host, port);
                } catch (Exception firstFailure) {
                    // The task channel may have gone stale; reconnect once without touching uinput.
                    taskAdb.close();
                    dump = taskAdb.recentTasks(host, port);
                }
                List<TaskEntry> tasks = parseTasks(dump);
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
                runTaskAction(() -> taskAdb.launchApp(host, port, task.pkg), "Abriendo " + task.pkg);
            });
            row.addView(open);
            Button close = smallButton("×", 0xff71313b);
            close.setOnClickListener(v -> {
                runTaskAction(() -> taskAdb.closeTask(host, port, task.pkg), "Cerrando " + task.pkg);
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
                    try { taskAdb.closeTask(host, port, task.pkg); } catch (Exception ignored) { }
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

    private int[] parseMusicVolume(String output) throws Exception {
        Matcher blockMatcher = Pattern.compile("(?s)- STREAM_MUSIC:(.*?)(?=\\n\\s*- STREAM_|\\z)").matcher(output);
        if (!blockMatcher.find()) throw new Exception("No se encontró STREAM_MUSIC.");
        String block = blockMatcher.group(1);
        Matcher minMatcher = Pattern.compile("(?m)Min:\\s*(\\d+)").matcher(block);
        Matcher maxMatcher = Pattern.compile("(?m)Max:\\s*(\\d+)").matcher(block);
        Matcher devicesMatcher = Pattern.compile("(?m)Devices:\\s*([^\\r\\n]+)").matcher(block);
        Matcher currentLine = Pattern.compile("(?m)Current:\\s*([^\\r\\n]+)").matcher(block);
        if (!minMatcher.find() || !maxMatcher.find() || !devicesMatcher.find() || !currentLine.find())
            throw new Exception("No se pudo interpretar el volumen HDMI.");
        int min = Integer.parseInt(minMatcher.group(1));
        int max = Integer.parseInt(maxMatcher.group(1));
        String activeDevice = devicesMatcher.group(1).trim();
        Matcher values = Pattern.compile("(?:\\d+\\s*)?\\(([^)]+)\\):\\s*(\\d+)").matcher(currentLine.group(1));
        Integer current = null;
        while (values.find()) {
            if (values.group(1).trim().equalsIgnoreCase(activeDevice)) {
                current = Integer.parseInt(values.group(2));
                break;
            }
        }
        if (current == null) throw new Exception("No se encontró el nivel del dispositivo activo: " + activeDevice);
        if (max <= min || current < min || current > max) throw new Exception("Rango de volumen no válido.");
        return new int[]{current, min, max};
    }

    private void loadVolume() {
        if (volumeValue == null || volumeSeek == null) return;
        final String host = hostFromAddress();
        final int port = readAdbPort();
        if (host == null || port < 1) {
            volumeValue.setText("Sin conexión");
            return;
        }
        volumeIo.execute(() -> {
            try {
                String output = taskAdb.shell(host, port, "su 0 dumpsys audio");
                int[] parsed = parseMusicVolume(output);
                int current = parsed[0], min = parsed[1], max = parsed[2];
                final int progress = Math.max(0, Math.min(100, Math.round((current - min) * 100f / (max - min))));
                runOnUiThread(() -> {
                    if (volumeSeek == null || volumeValue == null) return;
                    volumeMin = min; volumeMax = max; volumeCurrent = current;
                    volumeSeek.setProgress(progress);
                    volumeSeek.setEnabled(true);
                    volumeValue.setText(progress + "%");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (volumeValue != null) volumeValue.setText("No disponible");
                    if (volumeSeek != null) volumeSeek.setEnabled(false);
                });
            }
        });
    }

    private void applyVolume(int progress) {
        final String host = hostFromAddress();
        final int port = readAdbPort();
        if (host == null || port < 1) return;
        final int target = volumeMin + Math.round((volumeMax - volumeMin) * progress / 100f);
        volumeIo.execute(() -> {
            try {
                int difference = target - volumeCurrent;
                int keyCode = difference > 0 ? 24 : 25;
                for (int i = 0; i < Math.abs(difference); i++) {
                    taskAdb.shell(host, port, "su 0 input keyevent " + keyCode);
                }
                volumeCurrent = target;
                runOnUiThread(() -> {
                    if (volumeValue != null) volumeValue.setText(progress + "%");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (volumeValue != null) volumeValue.setText("Error al cambiar");
                });
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
        if (previewAdb != null) previewAdb.close();
        if (taskAdb != null) taskAdb.close();
        io.shutdownNow();
        previewIo.shutdownNow();
        volumeIo.shutdownNow();
        keyIo.shutdownNow();
    }
}

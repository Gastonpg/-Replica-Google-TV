package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import android.content.Context;
import android.content.DialogInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText address, token;
    private TextView status, scaleLabel, selectedLabel;
    private ProgressBar progress;
    private SeekBar scaleSeek, widthSeek, heightSeek;
    private Spinner styleSpinner;
    private WidgetPreview preview;
    private JSONArray widgets = new JSONArray();
    private int selectedIndex = -1;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean loadingControls = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildInterface();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff080e19);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));

        TextView title = text("PG CONTROL", 27, true);
        page.addView(title);
        page.addView(text("Personalizá tu Google TV desde el celular", 13, false));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(getPreferences(0).getString("address", ""));
        address.setHint("http://192.168.x.x:8765");
        page.addView(address);

        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(129);
        token.setText(getPreferences(0).getString("token", ""));
        token.setHint("Token del TV Box");
        page.addView(token);

        Button connect = button("Conectar con Google TV PG");
        page.addView(connect);
        connect.setOnClickListener(v -> connect());
        status = text("El celular y el TV Box deben estar en la misma red Wi-Fi.", 12, false);
        page.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress);

        divider(page);
        page.addView(text("TAMAÑO DE TARJETAS", 17, true));
        page.addView(text("Ajustá todas las tarjetas gradualmente. Sus banners originales no se modifican.", 12, false));
        scaleLabel = text("100%", 23, true);
        page.addView(scaleLabel);
        scaleSeek = new SeekBar(this);
        scaleSeek.setMax(75);
        scaleSeek.setProgress(25);
        page.addView(scaleSeek);
        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                float scale = 0.75f + value / 100f;
                scaleLabel.setText(Math.round(scale * 100) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveCardScale(); }
        });

        divider(page);
        page.addView(text("TIENDA DE WIDGETS", 17, true));
        page.addView(text("Agregá widgets y acomodalos directamente sobre la vista previa del televisor.", 12, false));
        preview = new WidgetPreview(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(205));
        previewParams.topMargin = dp(10);
        previewParams.bottomMargin = dp(8);
        page.addView(preview, previewParams);
        page.addView(text("Tocá un widget y arrastralo para cambiarlo de lugar.", 11, false));

        LinearLayout catalog1 = new LinearLayout(this);
        LinearLayout catalog2 = new LinearLayout(this);
        String[][] catalog = {
            {"Reloj", "clock"}, {"Fecha", "date"}, {"Hora + fecha", "clock_date"},
            {"Info. TV Box", "device"}, {"Red local", "wifi"}, {"Bienvenida", "greeting"}
        };
        for (int i = 0; i < catalog.length; i++) {
            final String type = catalog[i][1];
            Button b = button(catalog[i][0]);
            b.setTextSize(11);
            (i < 3 ? catalog1 : catalog2).addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
            b.setOnClickListener(v -> addWidget(type));
        }
        page.addView(catalog1);
        page.addView(catalog2);

        divider(page);
        page.addView(text("WIDGET SELECCIONADO", 16, true));
        selectedLabel = text("Seleccioná un widget en la vista previa", 13, false);
        page.addView(selectedLabel);

        page.addView(text("Ancho", 12, true));
        widthSeek = new SeekBar(this);
        widthSeek.setMax(760);
        widthSeek.setProgress(180);
        page.addView(widthSeek);
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!loadingControls && fromUser) updateSelectedSize(140 + value, -1);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { preview.invalidate(); }
        });

        page.addView(text("Alto", 12, true));
        heightSeek = new SeekBar(this);
        heightSeek.setMax(290);
        heightSeek.setProgress(70);
        page.addView(heightSeek);
        heightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!loadingControls && fromUser) updateSelectedSize(-1, 70 + value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { preview.invalidate(); }
        });

        styleSpinner = new Spinner(this);
        String[] styles = {"Cristal", "Cinemático", "Sólido", "Contorno"};
        styleSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, styles));
        page.addView(styleSpinner);
        styleSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (loadingControls) return;
                JSONObject item = selectedWidget();
                if (item == null) return;
                try {
                    String[] values = {"glass", "cinematic", "solid", "outline"};
                    item.put("style", values[position]);
                    preview.invalidate();
                } catch (Exception ignored) { }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        LinearLayout actions = new LinearLayout(this);
        Button remove = button("Quitar widget");
        Button save = button("Guardar en TV");
        actions.addView(remove, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions);
        remove.setOnClickListener(v -> removeSelected());
        save.setOnClickListener(v -> saveWidgets());

        TextView note = text("Los banners de las aplicaciones se conservan. Los widgets se dibujan en una capa independiente y se pueden mover sin reemplazar las imágenes.", 11, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(12);
        page.addView(note, noteParams);

        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateSelectedControls();
    }

    private void connect() {
        String a = address.getText().toString().trim().replaceAll("/+$", "");
        String t = token.getText().toString().trim();
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        address.setText(a);
        getPreferences(0).edit().putString("address", a).putString("token", t).apply();
        status.setText("Conectando…");
        request("GET", "/v1/device", null, device -> {
            status.setText("Conectado a " + device.optString("launcher", "Google TV PG") + " · " + device.optString("model"));
            loadWidgets();
            loadCardScale();
        });
    }

    private void loadWidgets() {
        request("GET", "/v1/launcher/widgets", null, result -> {
            JSONArray received = result.optJSONArray("widgets");
            widgets = received == null ? new JSONArray() : received;
            selectedIndex = widgets.length() == 0 ? -1 : 0;
            preview.invalidate();
            updateSelectedControls();
            status.setText("Conectado · " + widgets.length() + " widgets configurados");
        });
    }

    private void loadCardScale() {
        request("GET", "/v1/launcher/card-scale", null, result -> {
            float scale = (float) result.optDouble("scale", 1.0);
            scaleSeek.setProgress(Math.max(0, Math.min(75, Math.round((scale - 0.75f) * 100f))));
            scaleLabel.setText(Math.round(scale * 100) + "%");
        });
    }

    private void saveCardScale() {
        float scale = 0.75f + scaleSeek.getProgress() / 100f;
        try {
            JSONObject body = new JSONObject();
            body.put("scale", scale);
            request("POST", "/v1/launcher/card-scale", body, result ->
                status.setText("Tamaño de tarjetas aplicado: " + Math.round(scale * 100) + "%"));
        } catch (Exception e) {
            status.setText("No se pudo preparar el tamaño.");
        }
    }

    private void addWidget(String type) {
        if (widgets.length() >= 12) {
            status.setText("Podés colocar hasta 12 widgets.");
            return;
        }
        try {
            JSONObject item = new JSONObject();
            item.put("id", "pgw" + System.currentTimeMillis());
            item.put("type", type);
            item.put("style", "glass");
            int offset = (widgets.length() % 5) * 36;
            item.put("x", Math.max(0, 1420 - offset));
            item.put("y", 145 + offset);
            item.put("w", type.equals("clock") ? 300 : 360);
            item.put("h", type.equals("date") ? 105 : 145);
            widgets.put(item);
            selectedIndex = widgets.length() - 1;
            preview.invalidate();
            updateSelectedControls();
            status.setText("Widget agregado. Arrastralo para ubicarlo y guardá los cambios.");
        } catch (Exception e) {
            status.setText("No se pudo agregar el widget.");
        }
    }

    private JSONObject selectedWidget() {
        if (selectedIndex < 0 || selectedIndex >= widgets.length()) return null;
        return widgets.optJSONObject(selectedIndex);
    }

    private void updateSelectedSize(int width, int height) {
        JSONObject item = selectedWidget();
        if (item == null) return;
        try {
            if (width > 0) item.put("w", Math.min(900, width));
            if (height > 0) item.put("h", Math.min(360, height));
            int w = item.optInt("w", 320), h = item.optInt("h", 140);
            item.put("x", Math.max(0, Math.min(1920 - w, item.optInt("x", 0))));
            item.put("y", Math.max(0, Math.min(1080 - h, item.optInt("y", 0))));
            preview.invalidate();
        } catch (Exception ignored) { }
    }

    private void updateSelectedControls() {
        JSONObject item = selectedWidget();
        loadingControls = true;
        boolean enabled = item != null;
        widthSeek.setEnabled(enabled);
        heightSeek.setEnabled(enabled);
        styleSpinner.setEnabled(enabled);
        if (item == null) {
            selectedLabel.setText("Seleccioná un widget en la vista previa");
        } else {
            String type = item.optString("type", "clock");
            selectedLabel.setText(widgetName(type) + " · " + item.optInt("w", 320) + " × " + item.optInt("h", 140));
            widthSeek.setProgress(Math.max(0, Math.min(760, item.optInt("w", 320) - 140)));
            heightSeek.setProgress(Math.max(0, Math.min(290, item.optInt("h", 140) - 70)));
            String style = item.optString("style", "glass");
            styleSpinner.setSelection(style.equals("cinematic") ? 1 : style.equals("solid") ? 2 : style.equals("outline") ? 3 : 0);
        }
        loadingControls = false;
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= widgets.length()) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < widgets.length(); i++) if (i != selectedIndex) next.put(widgets.optJSONObject(i));
        widgets = next;
        selectedIndex = widgets.length() == 0 ? -1 : Math.min(selectedIndex, widgets.length() - 1);
        preview.invalidate();
        updateSelectedControls();
    }

    private void saveWidgets() {
        try {
            JSONObject body = new JSONObject();
            body.put("widgets", widgets);
            status.setText("Guardando distribución de widgets…");
            request("POST", "/v1/launcher/widgets", body, result -> {
                widgets = result.optJSONArray("widgets") == null ? widgets : result.optJSONArray("widgets");
                preview.invalidate();
                status.setText("Guardado en el TV Box · " + widgets.length() + " widgets");
            });
        } catch (Exception e) {
            status.setText("No se pudo preparar la configuración.");
        }
    }

    private String widgetName(String type) {
        if ("clock".equals(type)) return "Reloj";
        if ("date".equals(type)) return "Fecha";
        if ("clock_date".equals(type)) return "Hora + fecha";
        if ("device".equals(type)) return "Información del TV Box";
        if ("wifi".equals(type)) return "Red local";
        return "Bienvenida";
    }

    private void request(String method, String path, JSONObject body, Done done) {
        String base = address.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty()) {
            status.setText("Ingresá la dirección y el token del TV Box.");
            return;
        }
        progress.setVisibility(View.VISIBLE);
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
                    progress.setVisibility(View.GONE);
                    if (code >= 200 && code < 300) done.accept(response);
                    else status.setText("Error " + code + ": " + response.optString("error", "sin detalle"));
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "sin detalle" : e.getMessage();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Sin conexión: " + message);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    interface Done { void accept(JSONObject result); }

    private class WidgetPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private int dragStartX, dragStartY;
        private boolean dragging = false;

        WidgetPreview(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sx = getWidth() / 1920f, sy = getHeight() / 1080f;
            canvas.drawColor(0xff101a2a);
            paint.setColor(0xff24344b);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            for (int x = 0; x <= 1920; x += 240) canvas.drawLine(x * sx, 0, x * sx, getHeight(), paint);
            for (int y = 0; y <= 1080; y += 180) canvas.drawLine(0, y * sy, getWidth(), y * sy, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x447EA8D8);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            for (int i = 0; i < widgets.length(); i++) {
                JSONObject item = widgets.optJSONObject(i);
                if (item == null) continue;
                float x = item.optInt("x", 0) * sx, y = item.optInt("y", 0) * sy;
                float w = item.optInt("w", 320) * sx, h = item.optInt("h", 140) * sy;
                RectF rect = new RectF(x, y, x + w, y + h);
                String style = item.optString("style", "glass");
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(style.equals("solid") ? 0xff24364d : style.equals("cinematic") ? 0xdd304d70 : 0x994b6b91);
                canvas.drawRoundRect(rect, 9, 9, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(i == selectedIndex ? 3 : 1);
                paint.setColor(i == selectedIndex ? 0xffa8d9ff : 0xff6685a8);
                canvas.drawRoundRect(rect, 9, 9, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.max(8, Math.min(14, h * 0.30f)));
                canvas.drawText(widgetName(item.optString("type", "clock")), rect.centerX(), rect.centerY() + paint.getTextSize() / 3f, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float sx = getWidth() / 1920f, sy = getHeight() / 1080f;
            float x = event.getX() / sx, y = event.getY() / sy;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                selectedIndex = -1;
                for (int i = widgets.length() - 1; i >= 0; i--) {
                    JSONObject item = widgets.optJSONObject(i);
                    if (item == null) continue;
                    int wx = item.optInt("x", 0), wy = item.optInt("y", 0);
                    int ww = item.optInt("w", 320), wh = item.optInt("h", 140);
                    if (x >= wx && x <= wx + ww && y >= wy && y <= wy + wh) {
                        selectedIndex = i;
                        dragStartX = wx;
                        dragStartY = wy;
                        downX = x;
                        downY = y;
                        dragging = true;
                        break;
                    }
                }
                updateSelectedControls();
                invalidate();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE && dragging) {
                JSONObject item = selectedWidget();
                if (item != null) {
                    int w = item.optInt("w", 320), h = item.optInt("h", 140);
                    try {
                        item.put("x", Math.max(0, Math.min(1920 - w, Math.round(dragStartX + x - downX))));
                        item.put("y", Math.max(0, Math.min(1080 - h, Math.round(dragStartY + y - downY))));
                    } catch (Exception ignored) { }
                    invalidate();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                updateSelectedControls();
                invalidate();
                return true;
            }
            return true;
        }
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(0xffedf4ff);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(5));
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

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0xff26364d);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(15);
        params.bottomMargin = dp(13);
        parent.addView(line, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}

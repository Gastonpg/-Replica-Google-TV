package com.gastonpg.replicagoogletv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_PROFILE_IMAGE = 731;
    private static final String PREFS = "pg_launcher_preferences";
    private static final String PROFILE_URI = "profile_image_uri";
    private TVHome home;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        home = new TVHome(this);
        setContentView(home);
    }

    private void chooseProfileImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PROFILE_IMAGE);
    }

    private void showSettings() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Clave API de TMDB (opcional)");
        input.setText(getSharedPreferences("pg_launcher_preferences", MODE_PRIVATE).getString("tmdb_api_key", ""));
        new AlertDialog.Builder(this).setTitle("Configuración de TV Launcher PG")
            .setMessage("Guardá la clave de TMDB para conectar el catálogo.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Actualizar apps", (d,w) -> { if(home!=null) home.refreshInstalledApps(); })
            .setPositiveButton("Guardar", (d,w) -> { String key = input.getText().toString().trim(); getSharedPreferences("pg_launcher_preferences", MODE_PRIVATE).edit().putString("tmdb_api_key", key).apply(); if (home != null) home.loadTmdb(key); })
            .show();
    }

    private void showSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Buscar aplicación");
        new AlertDialog.Builder(this).setTitle("Buscar")
            .setView(input).setNegativeButton("Cancelar", null)
            .setPositiveButton("Buscar", (d,w) -> {
                String term = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (home != null) home.findApp(term);
            }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROFILE_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PROFILE_URI, uri.toString()).apply();
            if (home != null) home.setProfileImage(uri);
        }
    }

    static class TVHome extends View {
        private final Activity a;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Canvas c;
        private Bitmap profileBitmap;
        private Bitmap logoBitmap;
        private Bitmap heroBackdrop;
        private final ArrayList<Bitmap> featuredBackdrops = new ArrayList<>();
        private final ArrayList<String> featuredTitles = new ArrayList<>();
        private volatile boolean tmdbLoading = false;
        private final Handler catalogHandler = new Handler(Looper.getMainLooper());
        private final Runnable catalogRotation = new Runnable() {
            @Override public void run() {
                if (featuredBackdrops.size() > 1) {
                    Bitmap firstBitmap = featuredBackdrops.remove(0);
                    featuredBackdrops.add(firstBitmap);
                    String firstTitle = featuredTitles.remove(0);
                    featuredTitles.add(firstTitle);
                    if (featuredBackdrops.get(0) != null) heroBackdrop = featuredBackdrops.get(0);
                    invalidate();
                }
                catalogHandler.postDelayed(this, 18000L);
            }
        };
        private final Runnable catalogRefresh = new Runnable() {
            @Override public void run() {
                String key = a.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("tmdb_api_key", "").trim();
                if (!key.isEmpty()) loadTmdb(key);
                catalogHandler.postDelayed(this, 30L * 60L * 1000L);
            }
        };
        private final ArrayList<Bitmap> appIconBitmaps = new ArrayList<>();
        private float sx, sy;
        // Focus index 0-4: Seguir viendo; 5-13: apps.
        private int focused = 0;
        private int selectedCategory = 0;
        private int appScroll = 0;
        private final ArrayList<ResolveInfo> installedApps = new ArrayList<>();
        private final HashSet<String> excludedPackages = new HashSet<>(Arrays.asList("com.gastonpg.replicagoogletv"));
        private final String[] nav = {"Para ti", "Películas", "Series", "Apps", "Biblioteca"};
        private String[] apps = new String[0];
        private final String[] continueTitles = {"", "", "", "", ""};

        TVHome(Activity activity) {
            super(activity);
            a = activity;
            setFocusable(true);
            setFocusableInTouchMode(true);
            // Keep hardware acceleration enabled; software rendering made every glass effect slow.
            try { logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.pg_logo); } catch (Exception ignored) { }
            String saved = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PROFILE_URI, null);
            if (saved != null) setProfileImage(Uri.parse(saved));
            refreshInstalledApps();
            String tmdbKey = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("tmdb_api_key", "").trim();
            if (!tmdbKey.isEmpty()) loadTmdb(tmdbKey);
            catalogHandler.postDelayed(catalogRotation, 18000L);
            catalogHandler.postDelayed(catalogRefresh, 30L * 60L * 1000L);
        }

        void setProfileImage(Uri uri) {
            try (InputStream stream = a.getContentResolver().openInputStream(uri)) {
                Bitmap loaded = BitmapFactory.decodeStream(stream);
                if (loaded != null) {
                    if (profileBitmap != null && !profileBitmap.isRecycled()) profileBitmap.recycle();
                    profileBitmap = loaded;
                    invalidate();
                }
            } catch (Exception ignored) { }
        }

        private void drawProfileAvatar() {
            c.save();
            Path clip = new Path();
            clip.addCircle(1776, 71, 24, Path.Direction.CW);
            c.clipPath(clip);
            if (profileBitmap != null && !profileBitmap.isRecycled()) {
                c.drawBitmap(profileBitmap, null, new RectF(1752, 47, 1800, 95), p);
            } else {
                fill(0xFFE3EAF4);
                c.drawCircle(1776, 63, 7, p);
                fill(0xFFE3EAF4);
                c.drawRoundRect(1764, 72, 1788, 87, 8, 8, p);
            }
            c.restore();
            reset();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0x99DDEBFF);
            c.drawCircle(1776, 71, 24, p);
            reset();
        }

        private void reset() {
            p.setShader(null);
            p.setMaskFilter(null);
            p.clearShadowLayer();
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(1f);
            p.setAlpha(255);
        }

        private void fill(int color) { reset(); p.setColor(color); }
        private void rect(float l, float t, float r, float b, float radius, int color) {
            fill(color);
            c.drawRoundRect(l, t, r, b, radius, radius, p);
        }
        private void label(String s, float x, float baseline, float size, int color, boolean bold) {
            fill(color);
            p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            p.setTextSize(size);
            c.drawText(s, x, baseline, p);
        }
        private void centerLabel(String s, float cx, float baseline, float size, int color, boolean bold) {
            fill(color);
            p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            p.setTextSize(size);
            c.drawText(s, cx - p.measureText(s) / 2f, baseline, p);
        }
        private void glass(float l, float t, float r, float b, float radius, boolean active) {
            reset();
            p.setShader(new LinearGradient(l, t, l, b,
                new int[]{active ? 0x55FFFFFF : 0x28FFFFFF, 0x18B9D8FF, 0x15111C2D},
                null, Shader.TileMode.CLAMP));
            c.drawRoundRect(l, t, r, b, radius, radius, p);
            p.setShader(null);

            // Diffuse halo first, then the fine white/blue glass outline.
            if (active) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(8f);
                p.setColor(0xAA8CCBFF);
                p.setShadowLayer(16f, 0f, 0f, 0xDD8BCBFF);
                c.drawRoundRect(l, t, r, b, radius, radius, p);
                p.clearShadowLayer();
                p.setStrokeWidth(3f);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(l, t, r, b, radius, radius, p);
                p.setStrokeWidth(1f);
                p.setColor(0xFFB8E2FF);
                c.drawRoundRect(l + 2, t + 2, r - 2, b - 2, Math.max(2, radius - 2), Math.max(2, radius - 2), p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(0x55DDEBFF);
                c.drawRoundRect(l, t, r, b, radius, radius, p);
            }
            reset();
            p.setShader(new LinearGradient(l, t, l, t + Math.max(8, radius),
                new int[]{0x26FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(l + 2, t + 2, r - 2, Math.min(b - 2, t + radius), radius, radius, p);
            reset();
        }

        @Override protected void onDetachedFromWindow() {
            catalogHandler.removeCallbacks(catalogRotation);
            catalogHandler.removeCallbacks(catalogRefresh);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            c = canvas;
            sx = getWidth() / 1920f;
            sy = getHeight() / 1080f;
            c.save();
            c.scale(sx, sy);

            // Deep blue-black background with subtle cinematic lighting.
            reset();
            p.setShader(new LinearGradient(0, 0, 1920, 1080,
                new int[]{0xFF141B28, 0xFF080C14, 0xFF05070C},
                null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, p);
            reset();
            p.setShader(new RadialGradient(990, 310, 1000,
                new int[]{0x554A6C91, 0x1D344761, 0x00000000},
                null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, p);
            reset();

            // Cinematic artwork fills the whole screen, not a narrow rectangle.
            if (heroBackdrop != null && !heroBackdrop.isRecycled()) {
                drawCenterCrop(heroBackdrop, new RectF(0, 0, 1920, 1080));
                reset();
                p.setShader(new LinearGradient(0, 0, 0, 1080,
                    new int[]{0xB805070C, 0x66101828, 0xD905070C},
                    new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, 1920, 1080, p);
                reset();
            }

            // Smoky vignette around the outside edge.
            p.setShader(new LinearGradient(0, 0, 0, 100,
                new int[]{0x507F8FA3, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 100, p);
            reset();
            p.setShader(new LinearGradient(0, 980, 0, 1080,
                new int[]{0x00000000, 0x557F8FA3}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 980, 1920, 1080, p);
            reset();

            // Header: original PG logo artwork and plain Gastón Puig lettering.
            // No tile or capsule behind either element.
            try {
                Bitmap decodedLogo = logoBitmap;
                if (decodedLogo != null) {
                    // Draw the supplied transparent PNG unchanged: no color keying or pixel edits.
                    p.setAlpha(255);
                    p.setFilterBitmap(true);
                    c.drawBitmap(decodedLogo, null, new RectF(70, 39, 124, 97), p);
                    reset();
                } else {
                    centerLabel("PG", 97, 80, 28, 0xFFFFFFFF, true);
                }
            } catch (Exception ignored) {
                centerLabel("PG", 97, 80, 28, 0xFFFFFFFF, true);
            }
            label("Gastón Puig", 140, 75, 18, 0xFFFFFFFF, true);
            glass(502, 42, 672, 100, 29, focused == 21);
            label("⌕", 522, 79, 29, 0xFFDDEAFF, true);
            label("Buscar", 555, 77, 19, 0xFFE1E7EF, false);

            float nx = 728;
            String[] navLabels = {"Para ti", "Películas", "Series", "Apps", "Biblioteca"};
            float[] widths = {65, 102, 70, 62, 108};
            for (int i = 0; i < navLabels.length; i++) {
                boolean activeNav = focused == i;
                int color = (i == selectedCategory) ? 0xFFFFFFFF : 0xFFB0BAC9;
                label(navLabels[i], nx, 75, 19, color, i == selectedCategory);
                if (activeNav) glass(nx - 10, 43, nx + widths[i] + 10, 101, 18, true);
                nx += widths[i] + 80;
            }
            glass(1685, 46, 1735, 96, 25, focused == 20);
            centerLabel("⚙", 1710, 77, 23, 0xFFE8F0FA, true);
            glass(1751, 46, 1801, 96, 25, focused == 22);
            drawProfileAvatar();

            // The cinematic backdrop stays unobstructed; no separate translucent hero rectangle.
            for (int i = 0; i < 5; i++) {
                fill(i == 0 ? 0xFFF3F7FC : 0x668D9EB2);
                c.drawCircle(126 + i * 25, 447, i == 0 ? 5 : 4, p);
            }

            // Continue watching row.
            label(selectedCategory == 1 ? "Películas" : selectedCategory == 2 ? "Series" : selectedCategory == 3 ? "Aplicaciones instaladas" : selectedCategory == 4 ? "Biblioteca" : "Seguir viendo", 80, 541, 24, 0xFFE2E8F0, true);
            float[] cardX = {72, 447, 802, 1145, 1508};
            float[] cardW = {355, 332, 332, 332, 332};
            for (int i = 0; i < cardX.length; i++) {
                float x = cardX[i], w = cardW[i];
                boolean active = focused == 30 + i;
                rect(x, 560, x + w, 560 + (active ? 188 : 180), active ? 20 : 18,
                    i == 0 ? 0xB51E293B : 0x7D1E293B);
                if (i < featuredBackdrops.size() && featuredBackdrops.get(i) != null) {
                    c.save();
                    Path cardClip = new Path();
                    cardClip.addRoundRect(x, 560, x + w, 740, 18, 18, Path.Direction.CW);
                    c.clipPath(cardClip);
                    p.setAlpha(255); p.setFilterBitmap(true);
                    drawCenterCrop(featuredBackdrops.get(i), new RectF(x, 560, x + w, 740));
                    reset();
                    p.setShader(new LinearGradient(x, 620, x, 740, new int[]{0x00000000, 0xDD05070C}, null, Shader.TileMode.CLAMP));
                    c.drawRect(x, 560, x + w, 740, p);
                    reset();
                    c.restore();
                    if (i < featuredTitles.size()) label(featuredTitles.get(i), x + 14, 718, 18, 0xFFFFFFFF, true);
                } else {
                    reset();
                    p.setShader(new LinearGradient(x, 560, x + w, 740, new int[]{0x2AFFFFFF, 0x101A2638, 0x20101828}, null, Shader.TileMode.CLAMP));
                    c.drawRoundRect(x, 560, x + w, 560 + (active ? 188 : 180), active ? 20 : 18, active ? 20 : 18, p);
                    reset();
                }
                if (active) glass(x, 560, x + w, 748, 20, true);
            }

            // Installed applications are discovered from Android, not hard-coded.
            label("Tus apps", 80, 823, 24, 0xFFE2E8F0, true);
            float appCardW = 196, gap = 22, appY = 852, appH = 112;
            int visibleCount = 8;
            int maxScroll = Math.max(0, installedApps.size() - visibleCount);
            appScroll = Math.max(0, Math.min(appScroll, maxScroll));
            for (int i = appScroll; i < installedApps.size() && i < appScroll + visibleCount; i++) {
                float x = 72 + (i - appScroll) * (appCardW + gap);
                boolean active = focused == 100 + i;
                // Frosted-glass background behind each app icon.
                reset();
                p.setShader(new LinearGradient(x, appY, x, appY + appH,
                    new int[]{0x55FFFFFF, 0x2AB9D8FF, 0x42101828}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(x, appY, x + appCardW, appY + appH, 16, 16, p);
                reset();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(0x668DDCFF);
                c.drawRoundRect(x, appY, x + appCardW, appY + appH, 16, 16, p);
                reset();
                try {
                    Bitmap iconBitmap = appIconBitmaps.get(i);
                    if (iconBitmap != null) c.drawBitmap(iconBitmap, null, new RectF(x + appCardW/2 - 27, appY + 12, x + appCardW/2 + 27, appY + 66), p);
                } catch (Exception ignored) { }
                String appName = apps[i];
                p.setTextSize(16);
                while (p.measureText(appName) > appCardW - 18 && appName.length() > 4) appName = appName.substring(0, appName.length()-1);
                centerLabel(appName, x + appCardW/2, appY + 94, 16, 0xFFF4F7FC, true);
                if (active) glass(x - 2, appY - 2, x + appCardW + 2, appY + appH + 2, 17, true);
            }
            if (installedApps.isEmpty()) {
                label("No se encontraron aplicaciones iniciables", 82, 900, 20, 0xFFE1E7EF, false);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX() / sx, y = event.getY() / sy;
                if (y >= 40 && y <= 110 && x >= 1680 && x <= 1740) { focused = 20; ((MainActivity)a).showSettings(); invalidate(); return true; }
                if (y >= 40 && y <= 110 && x >= 500 && x <= 680) { focused = 21; ((MainActivity)a).showSearch(); invalidate(); return true; }
                if (y >= 40 && y <= 110 && x >= 1745 && x <= 1810) { focused = 22; ((MainActivity) a).chooseProfileImage(); invalidate(); return true; }
                if (y >= 35 && y <= 115 && x >= 720 && x <= 1600) {
                    int[] starts = {728,873,1055,1205,1347}; int[] ends = {793,975,1125,1267,1455};
                    for (int i=0;i<5;i++) if (x>=starts[i] && x<=ends[i]) { selectedCategory=i; focused=i; invalidate(); return true; }
                }
                if (y >= 555 && y <= 755 && x >= 72) {
                    float[] starts = {72, 447, 802, 1145, 1508};
                    float[] widths = {355, 332, 332, 332, 332};
                    for (int i = 0; i < starts.length; i++) {
                        if (x >= starts[i] && x <= starts[i] + widths[i]) {
                            focused = 30 + i;
                            invalidate();
                            return true;
                        }
                    }
                }
                if (y >= 845 && y <= 980 && x >= 72) {
                    int idx = appScroll + Math.max(0, Math.min(7, (int)((x-72)/(218))));
                    if (idx < installedApps.size()) { focused=100+idx; launchApp(idx); return true; }
                }
            }
            return true;
        }

        @Override public boolean onKeyDown(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused >= 100) {
                    int idx = focused - 100;
                    if (idx > 0) { focused--; if (idx - 1 < appScroll) appScroll = Math.max(0, idx - 1); }
                    else focused = 3;
                } else if (focused >= 30 && focused <= 34) focused = focused == 30 ? 34 : focused - 1;
                else if (focused == 20) focused = 4;
                else if (focused == 21) focused = 22;
                else if (focused == 22) focused = 20;
                else if (focused >= 0 && focused < 5) focused = focused == 0 ? 21 : focused - 1;
                else focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (focused >= 100) {
                    int idx = focused - 100;
                    if (idx < installedApps.size() - 1) { focused++; if (idx + 1 >= appScroll + 8) appScroll++; }
                } else if (focused >= 30 && focused <= 34) focused = focused == 34 ? 30 : focused + 1;
                else if (focused == 20) focused = 22;
                else if (focused == 21) focused = 0;
                else if (focused == 22) focused = 21;
                else if (focused >= 0 && focused < 5) focused = focused == 4 ? 20 : focused + 1;
                else focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (focused >= 30 && focused <= 34) focused = installedApps.isEmpty() ? 20 : 100 + Math.min(appScroll, installedApps.size()-1);
                else if (focused >= 100) return true;
                else if (focused >= 0 && focused < 5) focused = 30 + focused;
                else if (focused == 20 || focused == 21 || focused == 22) focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (focused >= 100) focused = 30;
                else if (focused >= 30 && focused <= 34) focused = selectedCategory;
                else if (focused == 0) focused = 21;
                else if (focused == 1 || focused == 2 || focused == 3 || focused == 4) focused = 0;
                else focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (focused >= 100) launchApp(focused - 100);
                else if (focused >= 30 && focused <= 34) {
                    int index = focused - 30;
                    if (index < featuredTitles.size()) Toast.makeText(a, featuredTitles.get(index), Toast.LENGTH_SHORT).show();
                }
                else if (focused == 20) ((MainActivity)a).showSettings();
                else if (focused == 21) ((MainActivity)a).showSearch();
                else if (focused == 22) ((MainActivity)a).chooseProfileImage();
                else { selectedCategory = focused; if (selectedCategory == 3) refreshInstalledApps(); invalidate(); }
                return true;
            }
            return super.onKeyDown(key, event);
        }


        void loadTmdb(String apiKey) {
            if (apiKey == null || apiKey.trim().isEmpty() || tmdbLoading) return;
            tmdbLoading = true;
            new Thread(() -> {
                try {
                    String endpoint = "https://api.themoviedb.org/3/trending/all/week?api_key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8") + "&language=es-ES";
                    JSONObject root = new JSONObject(new String(httpGet(endpoint), "UTF-8"));
                    JSONArray results = root.optJSONArray("results");
                    if (results == null || results.length() == 0) throw new Exception("TMDB no devolvió títulos");
                    ArrayList<String> titles = new ArrayList<>();
                    ArrayList<String> paths = new ArrayList<>();
                    String heroPath = null;
                    for (int i = 0; i < results.length() && titles.size() < 5; i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("title", item.optString("name", "Título"));
                        String backdrop = item.optString("backdrop_path", "");
                        if (backdrop.isEmpty() || backdrop.equals("null")) continue;
                        if (heroPath == null) heroPath = backdrop;
                        titles.add(name); paths.add(backdrop);
                    }
                    if (heroPath == null) throw new Exception("TMDB no devolvió imágenes");
                    Bitmap hero = downloadBitmap("https://image.tmdb.org/t/p/original" + heroPath);
                    ArrayList<Bitmap> cards = new ArrayList<>();
                    for (String path : paths) cards.add(downloadBitmap("https://image.tmdb.org/t/p/w780" + path));
                    a.runOnUiThread(() -> {
                        if (heroBackdrop != null && !heroBackdrop.isRecycled()) heroBackdrop.recycle();
                        heroBackdrop = hero;
                        featuredBackdrops.clear(); featuredBackdrops.addAll(cards);
                        featuredTitles.clear(); featuredTitles.addAll(titles);
                        tmdbLoading = false; invalidate();
                        Toast.makeText(a, "Catálogo TMDB actualizado", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> {
                        tmdbLoading = false;
                        Toast.makeText(a, "TMDB: revisá la clave y la conexión a internet", Toast.LENGTH_LONG).show();
                    });
                }
            }, "PG-TMDB").start();
        }

        private void drawCenterCrop(Bitmap bitmap, RectF destination) {
            if (bitmap == null || bitmap.isRecycled()) return;
            float sourceRatio = (float) bitmap.getWidth() / bitmap.getHeight();
            float destRatio = destination.width() / destination.height();
            Rect source;
            if (sourceRatio > destRatio) {
                int cropWidth = Math.max(1, Math.round(bitmap.getHeight() * destRatio));
                int left = (bitmap.getWidth() - cropWidth) / 2;
                source = new Rect(left, 0, left + cropWidth, bitmap.getHeight());
            } else {
                int cropHeight = Math.max(1, Math.round(bitmap.getWidth() / destRatio));
                int top = (bitmap.getHeight() - cropHeight) / 2;
                source = new Rect(0, top, bitmap.getWidth(), top + cropHeight);
            }
            p.setFilterBitmap(true);
            c.drawBitmap(bitmap, source, destination, p);
        }

        private byte[] httpGet(String address) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            } finally { connection.disconnect(); }
        }

        private Bitmap downloadBitmap(String address) throws Exception {
            byte[] bytes = httpGet(address);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new Exception("Imagen no válida");
            return bitmap;
        }

        void findApp(String term) {
            if (term.isEmpty()) return;
            for (int i=0;i<apps.length;i++) if (apps[i].toLowerCase(Locale.ROOT).contains(term)) { focused=100+i; appScroll=Math.max(0, Math.min(i, Math.max(0, apps.length-8))); invalidate(); return; }
            Toast.makeText(a, "No se encontró la aplicación", Toast.LENGTH_SHORT).show();
        }

        private void refreshInstalledApps() {
            installedApps.clear();
            appIconBitmaps.clear();
            Intent query = new Intent(Intent.ACTION_MAIN, null);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm = a.getPackageManager();
            List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
            Collections.sort(found, (left, right) -> String.valueOf(left.loadLabel(pm)).compareToIgnoreCase(String.valueOf(right.loadLabel(pm))));
            for (ResolveInfo info : found) {
                if (info.activityInfo == null || excludedPackages.contains(info.activityInfo.packageName)) continue;
                installedApps.add(info);
            }
            apps = new String[installedApps.size()];
            for (int i=0;i<installedApps.size();i++) {
                apps[i] = String.valueOf(installedApps.get(i).loadLabel(pm));
                try { appIconBitmaps.add(drawableToBitmap(installedApps.get(i).loadIcon(pm), 54, 54)); }
                catch (Exception ignored) { appIconBitmaps.add(null); }
            }
            invalidate();
        }

        private Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas target = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(target);
            return bitmap;
        }

        private void launchApp(int index) {
            if (index < 0 || index >= installedApps.size()) return;
            ResolveInfo info = installedApps.get(index);
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { a.startActivity(intent); }
            catch (Exception e) { Toast.makeText(a, "No se pudo abrir " + apps[index], Toast.LENGTH_SHORT).show(); }
        }
    }
}

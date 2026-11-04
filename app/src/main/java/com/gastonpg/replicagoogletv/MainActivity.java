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
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Toast;
import android.util.LruCache;

public class MainActivity extends Activity {
    private static final int REQUEST_PROFILE_IMAGE = 731;
    private static final String PREFS = "pg_launcher_preferences";
    private static final String PROFILE_URI = "profile_image_uri";
    private TVHome home;
    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (home != null) home.refreshInstalledApps();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        home = new TVHome(this);
        setContentView(home);
        PgControlServer.ensureStarted(this);
        // Keep the app row synchronized when third-party apps are installed, removed or updated.
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addDataScheme("package");
        registerReceiver(packageChangeReceiver, packageFilter);
        IntentFilter controlFilter = new IntentFilter("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED");
        registerReceiver(packageChangeReceiver, controlFilter);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void chooseProfileImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PROFILE_IMAGE);
    }

    private void showPGMenu() { showSettings(); }

    private void openTvBoxSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        } catch (Exception first) {
            try {
                startActivity(new Intent("android.settings.SETTINGS"));
            } catch (Exception second) {
                Toast.makeText(this, "El sistema no permite abrir Ajustes", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad / 2, pad, 0);

        final EditText tmdbInput = new EditText(this);
        tmdbInput.setSingleLine(true);
        tmdbInput.setHint("TMDB API key (películas y series)");
        tmdbInput.setText(prefs.getString("tmdb_api_key", ""));
        panel.addView(tmdbInput, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final EditText youtubeInput = new EditText(this);
        youtubeInput.setSingleLine(true);
        youtubeInput.setHint("YouTube Data API key (videos)");
        youtubeInput.setText(prefs.getString("youtube_api_key", ""));
        panel.addView(youtubeInput, new android.widget.LinearLayout.LayoutParams(-1, -2));

        Button connectControl = new Button(this);
        connectControl.setText("Conectar PG Control (celular)");
        connectControl.setAllCaps(false);
        panel.addView(connectControl, new android.widget.LinearLayout.LayoutParams(-1, -2));
        connectControl.setOnClickListener(v -> showPgControlConnection());

        new AlertDialog.Builder(this).setTitle("Configuración de TV Launcher PG")
            .setMessage("Conectá varias fuentes para darle más variedad al catálogo. Las claves se guardan en este dispositivo.")
            .setView(panel)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Color del launcher", (d,w) -> showLauncherColors())
            .setPositiveButton("Guardar y cargar", (d,w) -> {
                String tmdbKey = tmdbInput.getText().toString().trim();
                String youtubeKey = youtubeInput.getText().toString().trim();
                prefs.edit().putString("tmdb_api_key", tmdbKey).putString("youtube_api_key", youtubeKey).apply();
                if (home != null) {
                    if (!tmdbKey.isEmpty()) home.loadTmdb(tmdbKey);
                    if (!youtubeKey.isEmpty()) home.loadYoutube(youtubeKey);
                    if (tmdbKey.isEmpty() && youtubeKey.isEmpty())
                        Toast.makeText(this, "Agregá al menos una clave API para cargar contenido", Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void showPgControlConnection() {
        PgControlServer.ensureStarted(this);
        String ip = "No disponible";
        try {
            int address = ((android.net.wifi.WifiManager)getApplicationContext()
                .getSystemService(WIFI_SERVICE)).getConnectionInfo().getIpAddress();
            ip = String.format(Locale.US, "%d.%d.%d.%d", address & 255, (address >> 8) & 255,
                (address >> 16) & 255, (address >> 24) & 255);
        } catch (Exception ignored) { }
        if ("No disponible".equals(ip) || "0.0.0.0".equals(ip)) {
            try {
                java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    java.util.Enumeration<java.net.InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress candidate = addresses.nextElement();
                        if (!candidate.isLoopbackAddress() && candidate instanceof java.net.Inet4Address) {
                            ip = candidate.getHostAddress();
                            break;
                        }
                    }
                    if (!"No disponible".equals(ip) && !"0.0.0.0".equals(ip)) break;
                }
            } catch (Exception ignored) { }
        }
        final String hostIp = ip;
        final String details = "Conectá el celular y el TV Box a la misma red Wi-Fi.\n\n"
            + "Dirección: http://" + hostIp + ":8765\n\n"
            + "Token: " + PgControlServer.token(this)
            + "\n\nIngresá estos datos en PG Control.";
        new AlertDialog.Builder(this).setTitle("Conectar PG Control")
            .setMessage(details)
            .setNeutralButton("Copiar datos", (d,w) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PG Control", "http://" + hostIp + ":8765\\n" + PgControlServer.token(this)));
                Toast.makeText(this, "Datos copiados", Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("Listo", null).show();
    }

    private void showLauncherColors() {
        final String[] names = {"Azul noche (original)", "Grafito", "Violeta", "Verde petróleo", "Borgoña", "Negro AMOLED"};
        final int[] colors = {0xFF263B59, 0xFF343B46, 0xFF49345F, 0xFF174B4B, 0xFF542D3D, 0xFF000000};
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int selected = prefs.getInt("launcher_theme_color", colors[0]);
        int checked = 0;
        for (int i = 0; i < colors.length; i++) if (colors[i] == selected) checked = i;
        new AlertDialog.Builder(this).setTitle("Color general del launcher")
            .setSingleChoiceItems(names, checked, (dialog, which) -> {
                prefs.edit().putInt("launcher_theme_color", colors[which]).apply();
                if (home != null) home.setThemeColor(colors[which]);
                dialog.dismiss();
            }).setNegativeButton("Cancelar", null).show();
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
        private int themeColor;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Canvas c;
        private Bitmap profileBitmap;
        private Bitmap logoBitmap;
        private Bitmap heroBackdrop;
        private Bitmap blurredHeroBackdrop;
        private final ArrayList<Bitmap> heroRotationBackdrops = new ArrayList<>();
        private int heroRotationIndex = 0;
        private final ArrayList<Bitmap> featuredBackdrops = new ArrayList<>();
        private final ArrayList<String> featuredTitles = new ArrayList<>();
        private final ArrayList<Bitmap> tmdbFeaturedBackdrops = new ArrayList<>();
        private final ArrayList<String> tmdbFeaturedTitles = new ArrayList<>();
        private final ArrayList<Bitmap> youtubeFeaturedBackdrops = new ArrayList<>();
        private final ArrayList<String> youtubeFeaturedTitles = new ArrayList<>();
        private boolean showingYoutubeCatalog = false;
        private int catalogPosterIndex = 0;
        private volatile boolean tmdbLoading = false;
        private volatile boolean youtubeLoading = false;
        private final Handler catalogHandler = new Handler(Looper.getMainLooper());
        private final Runnable catalogSourceRotation = new Runnable() {
            @Override public void run() {
                boolean hasTmdb = !tmdbFeaturedBackdrops.isEmpty();
                boolean hasYoutube = !youtubeFeaturedBackdrops.isEmpty();
                if (hasTmdb && hasYoutube) {
                    showingYoutubeCatalog = !showingYoutubeCatalog;
                    applyActiveFeaturedCatalog();
                    invalidate();
                } else if (hasYoutube) {
                    showingYoutubeCatalog = true;
                    applyActiveFeaturedCatalog();
                    invalidate();
                } else if (hasTmdb) {
                    showingYoutubeCatalog = false;
                    applyActiveFeaturedCatalog();
                    invalidate();
                }
                catalogHandler.postDelayed(this, 20000L);
            }
        };
        private final Runnable catalogRotation = new Runnable() {
            @Override public void run() {
                ArrayList<Bitmap> activeHeroes = showingYoutubeCatalog && !youtubeFeaturedBackdrops.isEmpty()
                    ? youtubeFeaturedBackdrops : heroRotationBackdrops;
                if (activeHeroes.size() > 1) {
                    heroRotationIndex = (heroRotationIndex + 1) % activeHeroes.size();
                    heroBackdrop = activeHeroes.get(heroRotationIndex);
                    refreshBlurredHero();
                } else if (!activeHeroes.isEmpty() && heroBackdrop != activeHeroes.get(0)) {
                    heroRotationIndex = 0;
                    heroBackdrop = activeHeroes.get(0);
                    refreshBlurredHero();
                }
                if (featuredBackdrops.size() > 1) {
                    catalogPosterIndex = (catalogPosterIndex + 1) % featuredBackdrops.size();
                }
                invalidate();
                catalogHandler.postDelayed(this, 6000L);
            }
        };
        private final Runnable catalogRefresh = new Runnable() {
            @Override public void run() {
                android.content.SharedPreferences prefs = a.getSharedPreferences(PREFS, MODE_PRIVATE);
                String key = prefs.getString("tmdb_api_key", "").trim();
                String youtubeKey = prefs.getString("youtube_api_key", "").trim();
                if (!key.isEmpty()) loadTmdb(key);
                if (!youtubeKey.isEmpty()) loadYoutube(youtubeKey);
                catalogHandler.postDelayed(this, 50L * 1000L);
            }
        };
        private final ArrayList<Bitmap> appIconBitmaps = new ArrayList<>();
        // Keep only a small number of decoded banners in memory on low-RAM TV boxes.
        private final LruCache<String, Bitmap> appBannerCache = new LruCache<String, Bitmap>(4 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        private final HashSet<String> appsWithoutBanner = new HashSet<>();

        // Tiny procedural particles: drawn by the launcher itself to avoid video/texture overhead.
        private static final class SnowParticle {
            float x, y, radius, speed, drift, phase, alpha;
        }
        private final ArrayList<SnowParticle> snowParticles = new ArrayList<>();
        private final Random particleRandom = new Random(7312026L);
        private long lastParticleFrame = 0L;
        private final Runnable particleAnimation = new Runnable() {
            @Override public void run() {
                advanceSnowParticles();
                invalidate();
                catalogHandler.postDelayed(this, 33L);
            }
        };
        private float sx, sy;
        // Focus index 0-4: Seguir viendo; 5-13: apps.
        private int focused = 0;
        private long lastHomePressTime = 0L;
        private int selectedCategory = 0;
        private int appScroll = 0;
        private int categoryScroll = 0;
        private int categoryAppScroll = 0;
        private final ArrayList<ResolveInfo> installedApps = new ArrayList<>();
        private final HashMap<String, JSONObject> appConfigs = new HashMap<>();
        private final HashSet<String> excludedPackages = new HashSet<>(Arrays.asList(
            "com.gastonpg.replicagoogletv",
            "com.tvlaunch.pg"
        ));
        private final String[] nav = {"Para ti", "Películas", "Series", "Apps", "Biblioteca"};
        private String[] apps = new String[0];
        private final String[] continueTitles = {"", "", "", "", ""};

        TVHome(Activity activity) {
            super(activity);
            a = activity;
            themeColor = a.getSharedPreferences(PREFS, MODE_PRIVATE).getInt("launcher_theme_color", 0xFF263B59);
            setFocusable(true);
            setFocusableInTouchMode(true);
            initSnowParticles();
            // Keep hardware acceleration enabled; software rendering made every glass effect slow.
            try { logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.pg_logo); } catch (Exception ignored) { }
            String saved = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PROFILE_URI, null);
            if (saved != null) setProfileImage(Uri.parse(saved));
            refreshInstalledApps();
            android.content.SharedPreferences catalogPrefs = a.getSharedPreferences(PREFS, MODE_PRIVATE);
            String tmdbKey = catalogPrefs.getString("tmdb_api_key", "").trim();
            String youtubeKey = catalogPrefs.getString("youtube_api_key", "").trim();
            if (!tmdbKey.isEmpty()) loadTmdb(tmdbKey);
            if (!youtubeKey.isEmpty()) loadYoutube(youtubeKey);
            catalogHandler.postDelayed(catalogRotation, 6000L);
            catalogHandler.postDelayed(catalogSourceRotation, 20000L);
            catalogHandler.postDelayed(catalogRefresh, 50L * 1000L);
            catalogHandler.removeCallbacks(particleAnimation);
            catalogHandler.postDelayed(particleAnimation, 33L);
        }

        void setThemeColor(int color) { themeColor = color; invalidate(); }

        void requestProfileImage() { ((MainActivity)a).chooseProfileImage(); }

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
                // A restrained top-edge highlight adds a polished glass reflection at negligible cost.
                p.setStrokeWidth(1.5f);
                p.setColor(0xBFFFFFFF);
                c.drawLine(l + radius, t + 2, r - radius, t + 2, p);
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

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            catalogHandler.removeCallbacks(particleAnimation);
            lastParticleFrame = 0L;
            catalogHandler.postDelayed(particleAnimation, 33L);
        }

        @Override protected void onDetachedFromWindow() {
            catalogHandler.removeCallbacks(catalogRotation);
            catalogHandler.removeCallbacks(catalogSourceRotation);
            catalogHandler.removeCallbacks(catalogRefresh);
            catalogHandler.removeCallbacks(particleAnimation);
            lastParticleFrame = 0L;
            super.onDetachedFromWindow();
        }

        private void initSnowParticles() {
            // Sparse, soft white dust/snow; normalized to the launcher's 1920x1080 canvas.
            for (int i = 0; i < 46; i++) {
                SnowParticle particle = new SnowParticle();
                particle.x = particleRandom.nextFloat() * 1920f;
                particle.y = particleRandom.nextFloat() * 1080f;
                particle.radius = 0.8f + particleRandom.nextFloat() * 1.7f;
                if (i % 9 == 0) particle.radius += 0.8f;
                particle.speed = 9f + particleRandom.nextFloat() * 20f;
                particle.drift = -3.5f + particleRandom.nextFloat() * 7f;
                particle.phase = particleRandom.nextFloat() * 6.28318f;
                particle.alpha = 65f + particleRandom.nextFloat() * 125f;
                snowParticles.add(particle);
            }
        }

        private void advanceSnowParticles() {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = lastParticleFrame == 0L ? 0.033f : Math.min(0.06f, (now - lastParticleFrame) / 1000f);
            lastParticleFrame = now;
            for (SnowParticle particle : snowParticles) {
                particle.phase += dt * 0.45f;
                particle.y += particle.speed * dt;
                particle.x += (particle.drift + (float)Math.sin(particle.phase) * 2.2f) * dt;
                if (particle.y > 1090f) {
                    particle.y = -particleRandom.nextFloat() * 70f;
                    particle.x = particleRandom.nextFloat() * 1920f;
                }
                if (particle.x < -8f) particle.x = 1928f;
                else if (particle.x > 1928f) particle.x = -8f;
            }
        }

        private void drawSnowParticles() {
            // A few particles get a faint halo; most stay tiny to preserve legibility and speed.
            for (int i = 0; i < snowParticles.size(); i++) {
                SnowParticle particle = snowParticles.get(i);
                int alpha = Math.max(35, Math.min(190,
                    (int)(particle.alpha * (0.78f + 0.22f * (float)Math.sin(particle.phase * 1.7f)))));
                reset();
                p.setColor(Color.WHITE);
                p.setAlpha(alpha / 3);
                if (i % 9 == 0) c.drawCircle(particle.x, particle.y, particle.radius * 2.8f, p);
                p.setAlpha(alpha);
                c.drawCircle(particle.x, particle.y, particle.radius, p);
            }
            reset();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            c = canvas;
            sx = getWidth() / 1920f;
            sy = getHeight() / 1080f;
            c.save();
            c.scale(sx, sy);

            // Theme color controls the launcher-wide cinematic background.
            reset();
            int themeR = Color.red(themeColor), themeG = Color.green(themeColor), themeB = Color.blue(themeColor);
            int themeLight = Color.rgb(Math.min(255, themeR * 3 / 4), Math.min(255, themeG * 3 / 4), Math.min(255, themeB * 3 / 4));
            int themeMid = Color.rgb(themeR / 3, themeG / 3, themeB / 3);
            int themeDark = Color.rgb(themeR / 8, themeG / 8, themeB / 8);
            p.setShader(new LinearGradient(0, 0, 1920, 1080,
                new int[]{themeLight, themeMid, themeDark},
                null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, p);
            reset();
            int br = Color.red(themeColor), bg = Color.green(themeColor), bb = Color.blue(themeColor);
            int glow = Color.argb(92, Math.min(255, br + 38), Math.min(255, bg + 42), Math.min(255, bb + 48));
            int shade = Color.argb(38, br, bg, bb);
            p.setShader(new RadialGradient(990, 310, 1000,
                new int[]{glow, shade, 0x00000000},
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

            // PG Liquid Cinema: light snowfall/dust floats over the artwork, behind all UI.
            drawSnowParticles();

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
            if (focused == 23) glass(62, 34, 132, 103, 22, true);
            glass(1618, 46, 1668, 96, 25, focused == 24);
            centerLabel("◉", 1643, 78, 22, 0xFFE8F0FA, true);
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
                // Shift the visible titles with the rotating backdrop so every connected API gets a turn.
                int featuredIndex = featuredBackdrops.isEmpty() ? -1 : (catalogPosterIndex + i) % featuredBackdrops.size();
                rect(x, 560, x + w, 560 + (active ? 188 : 180), active ? 20 : 18,
                    i == 0 ? 0xB51E293B : 0x7D1E293B);
                if (featuredIndex >= 0 && featuredBackdrops.get(featuredIndex) != null) {
                    c.save();
                    Path cardClip = new Path();
                    cardClip.addRoundRect(x, 560, x + w, 740, 18, 18, Path.Direction.CW);
                    c.clipPath(cardClip);
                    p.setAlpha(255); p.setFilterBitmap(true);
                    drawCenterCrop(featuredBackdrops.get(featuredIndex), new RectF(x, 560, x + w, 740));
                    reset();
                    p.setShader(new LinearGradient(x, 620, x, 740, new int[]{0x00000000, 0xDD05070C}, null, Shader.TileMode.CLAMP));
                    c.drawRect(x, 560, x + w, 740, p);
                    reset();
                    c.restore();
                    if (featuredIndex < featuredTitles.size()) label(featuredTitles.get(featuredIndex), x + 14, 718, 18, 0xFFFFFFFF, true);
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
            String appLayout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
            if ("horizontal".equals(appLayout)) {
                float gap = 22, appY = 852;
                int visibleCount = 8;
                int maxScroll = Math.max(0, installedApps.size() - 1);
                appScroll = Math.max(0, Math.min(appScroll, maxScroll));
                float shelfX = 72;
                for (int i = appScroll; i < installedApps.size(); i++) {
                    JSONObject cfg = appConfigs.get(installedApps.get(i).activityInfo.packageName);
                    float appCardW = cfg == null ? 196f : Math.max(100, Math.min(320, cfg.optInt("cardWidthDp", 196)));
                    float appH = cfg == null ? 110.25f : Math.max(60, Math.min(180, cfg.optInt("cardHeightDp", 110)));
                    if (i == appScroll) shelfX = 72;
                    else {
                        JSONObject prev = appConfigs.get(installedApps.get(i - 1).activityInfo.packageName);
                        float prevW = prev == null ? 196f : Math.max(100, Math.min(320, prev.optInt("cardWidthDp", 196)));
                        shelfX += prevW + gap;
                    }
                    float x = shelfX;
                    if (x > 1840 || x + appCardW > 1900) break;
                    boolean active = focused == 100 + i;
                    String style = cfg == null ? "glass" : cfg.optString("cardStyle", "glass");
                    float radius = cfg == null ? 16f : cfg.optInt("cardRadiusDp", 16);
                    Bitmap bannerBitmap = loadAppBanner(installedApps.get(i));
                    c.save();
                    Path appClip = new Path();
                    appClip.addRoundRect(x, appY, x + appCardW, appY + appH, radius, radius, Path.Direction.CW);
                    c.clipPath(appClip);
                    if (bannerBitmap != null && !bannerBitmap.isRecycled()) {
                        drawCenterCrop(bannerBitmap, new RectF(x, appY, x + appCardW, appY + appH));
                        reset();
                        if ("solid".equals(style)) {
                            p.setColor(0x66070B12);
                            c.drawRect(x, appY, x + appCardW, appY + appH, p);
                        } else {
                            p.setShader(new LinearGradient(x, appY + appH * 0.68f, x, appY + appH,
                                new int[]{0x00000000, 0x33000000}, null, Shader.TileMode.CLAMP));
                            c.drawRect(x, appY, x + appCardW, appY + appH, p);
                        }
                        reset();
                    } else {
                        reset();
                        int tr = Color.red(themeColor), tg = Color.green(themeColor), tb = Color.blue(themeColor);
                        int cardTop = "solid".equals(style) ? 0xFF202A39
                            : themeColor == Color.BLACK ? 0xFF1A1A1A
                            : Color.argb("minimal".equals(style) ? 70 : 181, Math.min(255,tr+28), Math.min(255,tg+28), Math.min(255,tb+32));
                        int cardMiddle = "solid".equals(style) ? 0xFF182333
                            : themeColor == Color.BLACK ? 0xFF121212 : Color.argb(213, tr/2, tg/2, tb/2);
                        int cardBottom = themeColor == Color.BLACK ? 0xFF080808 : 0xE0080D16;
                        p.setShader(new LinearGradient(x, appY, x + appCardW, appY + appH,
                            new int[]{cardTop, cardMiddle, cardBottom}, null, Shader.TileMode.CLAMP));
                        c.drawRect(x, appY, x + appCardW, appY + appH, p);
                        reset();
                        Bitmap iconBitmap = null;
                        try { iconBitmap = appIconBitmaps.get(i); } catch (Exception ignored) { }
                        float iconSize = Math.min(54f, Math.max(32f, appH * 0.52f));
                        float iconLeft = x + 14;
                        float iconTop = appY + (appH - iconSize) / 2f;
                        if (iconBitmap != null && !iconBitmap.isRecycled()) {
                            c.drawBitmap(iconBitmap, null, new RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize), p);
                        }
                        String appName = apps[i];
                        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                        p.setTextSize(Math.min(17f, Math.max(12f, appH * 0.14f)));
                        float textX = iconLeft + iconSize + 12;
                        while (p.measureText(appName) > appCardW - (textX - x) - 10 && appName.length() > 4)
                            appName = appName.substring(0, appName.length() - 1);
                        p.setColor(0xFFF4F7FC);
                        c.drawText(appName, textX, appY + appH * 0.58f, p);
                        reset();
                    }
                    c.restore();
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth("minimal".equals(style) ? 0.7f : 1.2f);
                    p.setColor("outline".equals(style) ? 0xFFE4F2FF : 0x668DDCFF);
                    c.drawRoundRect(x, appY, x + appCardW, appY + appH, radius, radius, p);
                    reset();
                    if (active) glass(x - 2, appY - 2, x + appCardW + 2, appY + appH + 2, radius + 1, true);
                }
    
            } else if ("grid".equals(appLayout)) {
                int pageStart = (appScroll / 8) * 8;
                for (int i = pageStart; i < installedApps.size() && i < pageStart + 8; i++) {
                    int slot = i - pageStart;
                    float x = 72 + (slot % 4) * 455;
                    float y = 850 + (slot / 4) * 105;
                    drawControlAppCard(i, x, y, 420, 88);
                }
            } else {
                ArrayList<String> categories = availableAppCategories();
                if ("category_rows".equals(appLayout)) {
                    categoryScroll = Math.max(0, Math.min(categoryScroll, Math.max(0, categories.size() - 2)));
                    for (int row = 0; row < 2 && categoryScroll + row < categories.size(); row++) {
                        String category = categories.get(categoryScroll + row);
                        float y = 850 + row * 108;
                        label(category, 80, y + 43, 19, 0xFFE7EDF6, true);
                        ArrayList<Integer> indices = appsInCategory(category);
                        for (int j = categoryAppScroll; j < indices.size() && j < categoryAppScroll + 6; j++) {
                            int slot = j - categoryAppScroll;
                            drawControlAppCard(indices.get(j), 285 + slot * 260, y + 5, 244, 78);
                        }
                    }
                } else {
                    if (!categories.isEmpty()) {
                        categoryScroll = Math.max(0, Math.min(categoryScroll, categories.size() - 1));
                        String category = categories.get(categoryScroll);
                        label(category + "  ·  " + (categoryScroll + 1) + "/" + categories.size(), 80, 844, 22, 0xFFE7EDF6, true);
                        ArrayList<Integer> indices = appsInCategory(category);
                        for (int j = categoryAppScroll; j < indices.size() && j < categoryAppScroll + 8; j++) {
                            int slot = j - categoryAppScroll;
                            float x = 72 + (slot % 4) * 455;
                            float y = 858 + (slot / 4) * 100;
                            drawControlAppCard(indices.get(j), x, y, 420, 84);
                        }
                    }
                }
            }
            if (installedApps.isEmpty()) {
                label("No se encontraron aplicaciones iniciables", 82, 900, 20, 0xFFE1E7EF, false);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX() / sx, y = event.getY() / sy;
                if (y >= 30 && y <= 115 && x >= 55 && x <= 450) { focused = 23; ((MainActivity)a).showPGMenu(); invalidate(); return true; }
                if (y >= 40 && y <= 110 && x >= 1610 && x <= 1675) { focused = 24; ((MainActivity)a).showLauncherColors(); invalidate(); return true; }
                if (y >= 40 && y <= 110 && x >= 1680 && x <= 1740) { focused = 20; ((MainActivity)a).openTvBoxSettings(); invalidate(); return true; }
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
                if (y >= 845 && y <= 1080 && x >= 72) {
                    String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
                    int hit = -1;
                    if ("grid".equals(layout)) {
                        int pageStart = (appScroll / 8) * 8;
                        int col = Math.max(0, Math.min(3, (int)((x - 72) / 455)));
                        int row = y >= 950 ? 1 : 0;
                        int slot = row * 4 + col;
                        int candidate = pageStart + slot;
                        if (candidate < installedApps.size() && x <= 72 + col * 455 + 420 && y <= 850 + row * 105 + 88) hit = candidate;
                    } else if ("category_rows".equals(layout)) {
                        ArrayList<String> cats = availableAppCategories();
                        for (int row = 0; row < 2 && categoryScroll + row < cats.size(); row++) {
                            if (y < 855 + row * 108 || y > 855 + row * 108 + 78) continue;
                            ArrayList<Integer> indices = appsInCategory(cats.get(categoryScroll + row));
                            int slot = (int)((x - 285) / 260);
                            int actual = categoryAppScroll + slot;
                            if (slot >= 0 && actual < indices.size() && slot < 6 && x <= 285 + slot * 260 + 244) hit = indices.get(actual);
                        }
                    } else if ("category_grid".equals(layout)) {
                        ArrayList<String> cats = availableAppCategories();
                        if (categoryScroll < cats.size()) {
                            ArrayList<Integer> indices = appsInCategory(cats.get(categoryScroll));
                            int col = Math.max(0, Math.min(3, (int)((x - 72) / 455)));
                            int row = y >= 958 ? 1 : 0;
                            int slot = row * 4 + col;
                            int actual = categoryAppScroll + slot;
                            if (actual < indices.size() && x <= 72 + col * 455 + 420 && y <= 858 + row * 100 + 84) hit = indices.get(actual);
                        }
                    } else {
                        float cardX = 72;
                        for (int idx = appScroll; idx < installedApps.size(); idx++) {
                            JSONObject cfg = appConfigs.get(installedApps.get(idx).activityInfo.packageName);
                            float w = cfg == null ? 196f : Math.max(100, Math.min(320, cfg.optInt("cardWidthDp", 196)));
                            float h = cfg == null ? 110.25f : Math.max(60, Math.min(180, cfg.optInt("cardHeightDp", 110)));
                            if (x >= cardX && x <= cardX + w && y >= 852 && y <= 852 + h) { hit = idx; break; }
                            cardX += w + 22; if (cardX > 1840) break;
                        }
                    }
                    if (hit >= 0) { focused = 100 + hit; launchApp(hit); return true; }
                }
            }
            return true;
        }

        @Override public boolean onKeyDown(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_HOME) {
                long now = event.getEventTime();
                if (lastHomePressTime > 0L && now - lastHomePressTime <= 500L) {
                    lastHomePressTime = 0L;
                    RecentAppsDialog.show(a);
                } else {
                    lastHomePressTime = now;
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_APP_SWITCH) { RecentAppsDialog.show(a); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused >= 100) {
                    String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
                    if ("category_rows".equals(layout) || "category_grid".equals(layout)) {
                        ArrayList<Integer> visible = visibleAppIndices();
                        int pos = visible.indexOf(focused - 100);
                        if (pos > 0) focused = 100 + visible.get(pos - 1);
                        else if (categoryAppScroll > 0) {
                            categoryAppScroll--;
                            ArrayList<Integer> page = visibleAppIndices();
                            if (!page.isEmpty()) focused = 100 + page.get(page.size() - 1);
                        }
                    } else {
                        int idx = focused - 100;
                        if (idx > 0) {
                            focused--;
                            if ("grid".equals(layout)) appScroll = ((focused - 100) / 8) * 8;
                            else if (idx - 1 < appScroll) appScroll = Math.max(0, idx - 1);
                        } else focused = 3;
                    }
                } else if (focused >= 30 && focused <= 34) focused = focused == 30 ? 34 : focused - 1;
                else if (focused == 20) focused = 24;
                else if (focused == 24) focused = 4;
                else if (focused == 21) focused = 23;
                else if (focused == 23) focused = 22;
                else if (focused == 22) focused = 20;
                else if (focused >= 0 && focused < 5) focused = focused == 0 ? 21 : focused - 1;
                else focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (focused >= 100) {
                    String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
                    if ("category_rows".equals(layout) || "category_grid".equals(layout)) {
                        ArrayList<Integer> visible = visibleAppIndices();
                        int pos = visible.indexOf(focused - 100);
                        if (pos >= 0 && pos < visible.size() - 1) focused = 100 + visible.get(pos + 1);
                        else {
                            String category = appCategory(focused - 100);
                            ArrayList<Integer> same = appsInCategory(category);
                            int local = same.indexOf(focused - 100);
                            int limit = "category_rows".equals(layout) ? 6 : 8;
                            if (local >= categoryAppScroll + limit && categoryAppScroll + limit < same.size()) {
                                categoryAppScroll++;
                                focused = 100 + same.get(categoryAppScroll + limit - 1);
                            }
                        }
                    } else {
                        int idx = focused - 100;
                        if (idx < installedApps.size() - 1) {
                            focused++;
                            if ("grid".equals(layout)) appScroll = ((focused - 100) / 8) * 8;
                            else if (idx + 1 >= appScroll + 8) appScroll++;
                        }
                    }
                } else if (focused >= 30 && focused <= 34) focused = focused == 34 ? 30 : focused + 1;
                else if (focused == 20) focused = 22;
                else if (focused == 22) focused = 23;
                else if (focused == 23) focused = 21;
                else if (focused == 21) focused = 0;
                else if (focused == 24) focused = 20;
                else if (focused >= 0 && focused < 5) focused = focused == 4 ? 20 : focused + 1;
                else focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (focused >= 100) {
                    String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
                    if ("category_rows".equals(layout) || "category_grid".equals(layout)) {
                        ArrayList<String> cats = availableAppCategories();
                        int max = Math.max(0, cats.size() - ("category_rows".equals(layout) ? 2 : 1));
                        categoryScroll = Math.min(max, categoryScroll + 1); categoryAppScroll = 0;
                        if (!cats.isEmpty()) { ArrayList<Integer> ids = appsInCategory(cats.get(categoryScroll)); if (!ids.isEmpty()) focused = 100 + ids.get(0); }
                        invalidate(); return true;
                    }
                }
                if (focused >= 30 && focused <= 34) focused = installedApps.isEmpty() ? 20 : 100 + Math.min(appScroll, installedApps.size()-1);
                else if (focused >= 100) return true;
                else if (focused >= 0 && focused < 5) focused = 30 + focused;
                else if (focused == 20 || focused == 21 || focused == 22 || focused == 23 || focused == 24) focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (focused >= 100) {
                    String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
                    if ("category_rows".equals(layout) || "category_grid".equals(layout)) {
                        categoryScroll = Math.max(0, categoryScroll - 1); categoryAppScroll = 0;
                        ArrayList<String> cats = availableAppCategories();
                        if (!cats.isEmpty()) { ArrayList<Integer> ids = appsInCategory(cats.get(categoryScroll)); if (!ids.isEmpty()) focused = 100 + ids.get(0); }
                        invalidate(); return true;
                    }
                    focused = 30;
                }
                else if (focused >= 30 && focused <= 34) focused = selectedCategory;
                else if (focused == 0) focused = 21;
                else if (focused == 21) focused = 23;
                else if (focused == 23) focused = 0;
                else if (focused == 24 || focused == 20 || focused == 22) focused = 0;
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
                else if (focused == 20) ((MainActivity)a).openTvBoxSettings();
                else if (focused == 21) ((MainActivity)a).showSearch();
                else if (focused == 22) ((MainActivity)a).chooseProfileImage();
                else if (focused == 23) ((MainActivity)a).showPGMenu();
                else if (focused == 24) ((MainActivity)a).showLauncherColors();
                else { selectedCategory = focused; if (selectedCategory == 3) refreshInstalledApps(); invalidate(); }
                return true;
            }
            return super.onKeyDown(key, event);
        }

        @Override public boolean onKeyLongPress(int key, KeyEvent event) {
            return super.onKeyLongPress(key, event);
        }

        @Override public boolean onKeyUp(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_HOME) return true;
            return super.onKeyUp(key, event);
        }

        private void applyActiveFeaturedCatalog() {
            ArrayList<Bitmap> sourceImages = showingYoutubeCatalog && !youtubeFeaturedBackdrops.isEmpty()
                ? youtubeFeaturedBackdrops : tmdbFeaturedBackdrops;
            ArrayList<String> sourceTitles = showingYoutubeCatalog && !youtubeFeaturedTitles.isEmpty()
                ? youtubeFeaturedTitles : tmdbFeaturedTitles;
            if (sourceImages.isEmpty() && !youtubeFeaturedBackdrops.isEmpty()) {
                sourceImages = youtubeFeaturedBackdrops;
                sourceTitles = youtubeFeaturedTitles;
                showingYoutubeCatalog = true;
            }
            featuredBackdrops.clear();
            featuredBackdrops.addAll(sourceImages);
            featuredTitles.clear();
            featuredTitles.addAll(sourceTitles);
            catalogPosterIndex = 0;

            // Keep the large cinematic background synchronized with the active catalog.
            ArrayList<Bitmap> activeHeroes = showingYoutubeCatalog && !youtubeFeaturedBackdrops.isEmpty()
                ? youtubeFeaturedBackdrops : heroRotationBackdrops;
            if (!activeHeroes.isEmpty()) {
                heroRotationIndex = 0;
                heroBackdrop = activeHeroes.get(0);
                refreshBlurredHero();
            }
        }

        void loadYoutube(String apiKey) {
            if (apiKey == null || apiKey.trim().isEmpty() || youtubeLoading) return;
            youtubeLoading = true;
            new Thread(() -> {
                try {
                    String endpoint = "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&regionCode=AR&maxResults=50&key="
                        + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8");
                    JSONObject root = new JSONObject(new String(httpGet(endpoint), "UTF-8"));
                    JSONArray results = root.optJSONArray("items");
                    if (results == null || results.length() == 0) throw new Exception("YouTube no devolvió videos");
                    ArrayList<String> allTitles = new ArrayList<>();
                    ArrayList<String> allImageUrls = new ArrayList<>();
                    ArrayList<String> allVideoIds = new ArrayList<>();
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        JSONObject snippet = item.optJSONObject("snippet");
                        if (snippet == null) continue;
                        String title = snippet.optString("title", "Video de YouTube");
                        JSONObject thumbnails = snippet.optJSONObject("thumbnails");
                        if (thumbnails == null) continue;
                        JSONObject image = thumbnails.optJSONObject("high");
                        if (image == null) image = thumbnails.optJSONObject("medium");
                        if (image == null) image = thumbnails.optJSONObject("default");
                        if (image == null) continue;
                        String url = image.optString("url", "");
                        if (url.isEmpty()) continue;
                        String videoId = item.optString("id", "");
                        if (videoId.isEmpty()) continue;
                        allTitles.add(title);
                        allImageUrls.add(url);
                        allVideoIds.add(videoId);
                    }
                    ArrayList<String> titles = new ArrayList<>();
                    ArrayList<String> imageUrls = new ArrayList<>();
                    ArrayList<String> videoIds = new ArrayList<>();
                    ArrayList<Integer> picks = new ArrayList<>();
                    for (int i = 0; i < allTitles.size(); i++) picks.add(i);
                    java.util.Collections.shuffle(picks, new java.util.Random());
                    for (int i = 0; i < picks.size() && titles.size() < 5; i++) {
                        int pick = picks.get(i);
                        titles.add(allTitles.get(pick));
                        imageUrls.add(allImageUrls.get(pick));
                        videoIds.add(allVideoIds.get(pick));
                    }
                    ArrayList<Bitmap> images = new ArrayList<>();
                    for (int i = 0; i < imageUrls.size(); i++) {
                        Bitmap image = null;
                        // Prefer YouTube's 1280x720 max-resolution frame when the video provides it.
                        // Fall back to the official API thumbnail for videos without maxres artwork.
                        String maxResolutionUrl = "https://i.ytimg.com/vi/" + videoIds.get(i) + "/maxresdefault.jpg";
                        try { image = downloadBitmap(maxResolutionUrl); } catch (Exception ignored) { }
                        if (image == null) {
                            try { image = downloadBitmap(imageUrls.get(i)); } catch (Exception ignored) { }
                        }
                        images.add(image);
                    }
                    a.runOnUiThread(() -> {
                        youtubeFeaturedBackdrops.clear();
                        youtubeFeaturedTitles.clear();
                        int added = 0;
                        for (int i = 0; i < images.size(); i++) {
                            Bitmap image = images.get(i);
                            if (image == null) continue;
                            youtubeFeaturedBackdrops.add(image);
                            youtubeFeaturedTitles.add(titles.get(i));
                            added++;
                        }
                        if (added > 0) {
                            // Ensure the YouTube catalog becomes visible at the next source switch.
                            if (tmdbFeaturedBackdrops.isEmpty()) showingYoutubeCatalog = true;
                            applyActiveFeaturedCatalog();
                        }
                        youtubeLoading = false;
                        invalidate();
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> {
                        youtubeLoading = false;
                        Toast.makeText(a, "YouTube: revisá la clave, la cuota y la conexión", Toast.LENGTH_LONG).show();
                    });
                }
            }, "PG-YouTube").start();
        }

        void loadTmdb(String apiKey) {
            if (apiKey == null || apiKey.trim().isEmpty() || tmdbLoading) return;
            tmdbLoading = true;
            new Thread(() -> {
                try {
                    int randomPage = 1 + new java.util.Random().nextInt(5);
                    String endpoint = "https://api.themoviedb.org/3/trending/all/day?api_key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8") + "&language=es-ES&page=" + randomPage;
                    JSONObject root = new JSONObject(new String(httpGet(endpoint), "UTF-8"));
                    JSONArray results = root.optJSONArray("results");
                    if (results == null || results.length() == 0) throw new Exception("TMDB no devolvió títulos");
                    ArrayList<String> titles = new ArrayList<>();
                    ArrayList<String> paths = new ArrayList<>();
                    for (int i = 0; i < results.length() && titles.size() < 5; i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("title", item.optString("name", "Título"));
                        String backdrop = item.optString("backdrop_path", "");
                        if (backdrop.isEmpty() || backdrop.equals("null")) continue;
                        titles.add(name); paths.add(backdrop);
                    }
                    if (paths.isEmpty()) throw new Exception("TMDB no devolvió imágenes");
                    ArrayList<Bitmap> heroes = new ArrayList<>();
                    ArrayList<Bitmap> cards = new ArrayList<>();
                    for (String path : paths) {
                        heroes.add(downloadBitmap("https://image.tmdb.org/t/p/w1280" + path));
                        cards.add(downloadBitmap("https://image.tmdb.org/t/p/w780" + path));
                    }
                    a.runOnUiThread(() -> {
                        heroRotationBackdrops.clear();
                        heroRotationBackdrops.addAll(heroes);
                        heroRotationIndex = 0;
                        heroBackdrop = heroRotationBackdrops.get(0);
                        refreshBlurredHero();
                        tmdbFeaturedBackdrops.clear(); tmdbFeaturedBackdrops.addAll(cards);
                        tmdbFeaturedTitles.clear(); tmdbFeaturedTitles.addAll(titles);
                        applyActiveFeaturedCatalog();
                        tmdbLoading = false; invalidate();
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> {
                        tmdbLoading = false;
                        Toast.makeText(a, "TMDB: revisá la clave y la conexión a internet", Toast.LENGTH_LONG).show();
                    });
                }
            }, "PG-TMDB").start();
        }

        private void refreshBlurredHero() {
            if (blurredHeroBackdrop != null && !blurredHeroBackdrop.isRecycled()) blurredHeroBackdrop.recycle();
            blurredHeroBackdrop = null;
            if (heroBackdrop == null || heroBackdrop.isRecycled()) return;
            // Downsample heavily, then enlarge with filtering: a lightweight frosted-glass blur
            // that also works on older Android TV boxes without RenderEffect (Android 12+).
            int w = Math.max(1, heroBackdrop.getWidth() / 28);
            int h = Math.max(1, heroBackdrop.getHeight() / 28);
            Bitmap tiny = Bitmap.createScaledBitmap(heroBackdrop, w, h, true);
            blurredHeroBackdrop = Bitmap.createScaledBitmap(tiny, w, h, true);
            if (tiny != blurredHeroBackdrop && !tiny.isRecycled()) tiny.recycle();
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
            appBannerCache.evictAll();
            appsWithoutBanner.clear();
            PackageManager pm = a.getPackageManager();

            // Start with ordinary Android apps, then prefer the TV activity when a package
            // also declares CATEGORY_LEANBACK_LAUNCHER.
            LinkedHashMap<String, ResolveInfo> byPackage = new LinkedHashMap<>();
            Intent mobileQuery = new Intent(Intent.ACTION_MAIN, null);
            mobileQuery.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> mobileApps = pm.queryIntentActivities(mobileQuery, 0);
            for (ResolveInfo info : mobileApps) {
                if (info == null || info.activityInfo == null) continue;
                String packageName = info.activityInfo.packageName;
                if (excludedPackages.contains(packageName) || packageName.equals(a.getPackageName())) continue;
                byPackage.put(packageName, info);
            }

            Intent tvQuery = new Intent(Intent.ACTION_MAIN, null);
            tvQuery.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> tvApps = pm.queryIntentActivities(tvQuery, 0);
            for (ResolveInfo info : tvApps) {
                if (info == null || info.activityInfo == null) continue;
                String packageName = info.activityInfo.packageName;
                if (excludedPackages.contains(packageName) || packageName.equals(a.getPackageName())) continue;
                // A TV-specific activity takes precedence over the phone/tablet launcher activity.
                byPackage.put(packageName, info);
            }

            appConfigs.clear();
            android.content.SharedPreferences controlPrefs = a.getSharedPreferences("pg_launcher_app_config", MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : controlPrefs.getAll().entrySet()) {
                try { appConfigs.put(entry.getKey(), new JSONObject(String.valueOf(entry.getValue()))); }
                catch (Exception ignored) { }
            }
            ArrayList<ResolveInfo> found = new ArrayList<>(byPackage.values());
            Iterator<ResolveInfo> iterator = found.iterator();
            while (iterator.hasNext()) {
                ResolveInfo info = iterator.next();
                JSONObject cfg = appConfigs.get(info.activityInfo.packageName);
                if (cfg != null && cfg.optBoolean("hidden", false)) iterator.remove();
            }
            Collections.sort(found, (left, right) -> {
                JSONObject lc = appConfigs.get(left.activityInfo.packageName);
                JSONObject rc = appConfigs.get(right.activityInfo.packageName);
                int lo = lc == null ? 10000 : lc.optInt("order", 10000);
                int ro = rc == null ? 10000 : rc.optInt("order", 10000);
                if (lo != ro) return Integer.compare(lo, ro);
                String ln = lc == null ? String.valueOf(left.loadLabel(pm)) : lc.optString("label", String.valueOf(left.loadLabel(pm)));
                String rn = rc == null ? String.valueOf(right.loadLabel(pm)) : rc.optString("label", String.valueOf(right.loadLabel(pm)));
                return ln.compareToIgnoreCase(rn);
            });
            installedApps.addAll(found);

            apps = new String[installedApps.size()];
            File iconDir = new File(a.getFilesDir(), "pg_control_icons");
            for (int i = 0; i < installedApps.size(); i++) {
                ResolveInfo info = installedApps.get(i);
                String pkg = info.activityInfo.packageName;
                JSONObject cfg = appConfigs.get(pkg);
                apps[i] = cfg == null ? String.valueOf(info.loadLabel(pm))
                    : cfg.optString("label", String.valueOf(info.loadLabel(pm)));
                Bitmap custom = null;
                try {
                    File customFile = new File(iconDir, pkg.replaceAll("[^A-Za-z0-9_.]", "_") + ".png");
                    if (customFile.isFile()) custom = BitmapFactory.decodeFile(customFile.getAbsolutePath());
                } catch (Exception ignored) { }
                try {
                    appIconBitmaps.add(custom != null ? drawableToBitmap(new android.graphics.drawable.BitmapDrawable(getResources(), custom), 54, 54)
                        : drawableToBitmap(info.loadIcon(pm), 54, 54));
                } catch (Exception ignored) { appIconBitmaps.add(null); }
                if (custom != null && !custom.isRecycled()) custom.recycle();
            }
            invalidate();
        }

        private ArrayList<Integer> visibleAppIndices() {
            ArrayList<Integer> result = new ArrayList<>();
            String layout = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString("apps_layout", "horizontal");
            if ("grid".equals(layout)) {
                int start = (appScroll / 8) * 8;
                for (int i = start; i < installedApps.size() && i < start + 8; i++) result.add(i);
            } else if ("category_rows".equals(layout) || "category_grid".equals(layout)) {
                ArrayList<String> cats = availableAppCategories();
                int rows = "category_rows".equals(layout) ? 2 : 1;
                for (int r = 0; r < rows && categoryScroll + r < cats.size(); r++) {
                    ArrayList<Integer> ids = appsInCategory(cats.get(categoryScroll + r));
                    int limit = "category_rows".equals(layout) ? 6 : 8;
                    for (int j = categoryAppScroll; j < ids.size() && j < categoryAppScroll + limit; j++) result.add(ids.get(j));
                }
            } else {
                for (int i = appScroll; i < installedApps.size() && i < appScroll + 8; i++) result.add(i);
            }
            return result;
        }

        private String appCategory(int index) {
            if (index < 0 || index >= installedApps.size()) return "Otros";
            String pkg = installedApps.get(index).activityInfo.packageName;
            JSONObject cfg = appConfigs.get(pkg);
            String custom = cfg == null ? "" : cfg.optString("category", "").trim();
            if (!custom.isEmpty()) return custom;
            String value = (pkg + " " + apps[index]).toLowerCase(Locale.ROOT);
            if (containsAny(value, "netflix","disney","youtube","prime","hbo","plex","twitch","paramount","crunchyroll","stream")) return "Streaming";
            if (containsAny(value, "spotify","music","musica","audio","deezer","soundcloud","radio")) return "Música";
            if (containsAny(value, "game","juego","play","retro","emulator","emulador")) return "Juegos";
            if (containsAny(value, "setting","ajuste","vpn","file","archivo","browser","explorador","manager","gestor")) return "Herramientas";
            return "Otros";
        }

        private boolean containsAny(String value, String... words) {
            for (String word : words) if (value.contains(word)) return true;
            return false;
        }

        private ArrayList<String> availableAppCategories() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String[] order = {"Streaming", "Música", "Juegos", "Herramientas", "Otros"};
            for (String category : order) for (int i = 0; i < installedApps.size(); i++)
                if (category.equals(appCategory(i))) { result.add(category); break; }
            for (int i = 0; i < installedApps.size(); i++) result.add(appCategory(i));
            return new ArrayList<>(result);
        }

        private ArrayList<Integer> appsInCategory(String category) {
            ArrayList<Integer> result = new ArrayList<>();
            for (int i = 0; i < installedApps.size(); i++) if (category.equals(appCategory(i))) result.add(i);
            return result;
        }

        private void drawControlAppCard(int index, float x, float y, float width, float height) {
            if (index < 0 || index >= installedApps.size()) return;
            String pkg = installedApps.get(index).activityInfo.packageName;
            JSONObject cfg = appConfigs.get(pkg);
            String style = cfg == null ? "glass" : cfg.optString("cardStyle", "glass");
            float radius = cfg == null ? 16 : cfg.optInt("cardRadiusDp", 16);
            Bitmap banner = loadAppBanner(installedApps.get(index));
            c.save();
            Path clip = new Path();
            clip.addRoundRect(x, y, x + width, y + height, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            if (banner != null && !banner.isRecycled()) {
                drawCenterCrop(banner, new RectF(x, y, x + width, y + height));
                reset();
                p.setColor("solid".equals(style) ? 0x66070B12 : 0x22000000);
                c.drawRect(x, y, x + width, y + height, p);
            } else {
                reset();
                int tr = Color.red(themeColor), tg = Color.green(themeColor), tb = Color.blue(themeColor);
                int alpha = "minimal".equals(style) ? 65 : 185;
                p.setShader(new LinearGradient(x, y, x + width, y + height,
                    new int[]{Color.argb(alpha, Math.min(255,tr+28), Math.min(255,tg+28), Math.min(255,tb+32)),
                        Color.argb(220, tr/2, tg/2, tb/2), 0xE0080D16}, null, Shader.TileMode.CLAMP));
                c.drawRect(x, y, x + width, y + height, p);
                reset();
                Bitmap icon = null;
                try { icon = appIconBitmaps.get(index); } catch (Exception ignored) { }
                float size = Math.min(48, height * 0.62f);
                if (icon != null && !icon.isRecycled())
                    c.drawBitmap(icon, null, new RectF(x + 10, y + (height-size)/2, x + 10 + size, y + (height+size)/2), p);
                String name = apps[index];
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                p.setTextSize(Math.min(17, Math.max(12, height * 0.19f)));
                float tx = x + size + 22;
                while (p.measureText(name) > width - size - 34 && name.length() > 4) name = name.substring(0, name.length()-1);
                p.setColor(0xFFF4F7FC); c.drawText(name, tx, y + height * 0.60f, p); reset();
            }
            c.restore();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth("minimal".equals(style) ? 0.7f : 1.2f);
            p.setColor("outline".equals(style) ? 0xFFE4F2FF : 0x668DDCFF);
            c.drawRoundRect(x, y, x + width, y + height, radius, radius, p); reset();
            if (focused == 100 + index) glass(x-2,y-2,x+width+2,y+height+2,radius+1,true);
        }

        private Bitmap loadAppBanner(ResolveInfo info) {
            if (info == null || info.activityInfo == null) return null;
            JSONObject cfg = appConfigs.get(info.activityInfo.packageName);
            if (cfg != null && cfg.optBoolean("hasCustomIcon", false)) return null;
            String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
            Bitmap cached = appBannerCache.get(key);
            if (cached != null) return cached;
            if (appsWithoutBanner.contains(key)) return null;

            PackageManager pm = a.getPackageManager();
            // Android exposes the activity/application android:banner resources through PackageManager.
            // Leanback metadata is read without installing a separate bridge APK.
            Drawable banner = null;
            try { banner = pm.getActivityBanner(new ComponentName(
                info.activityInfo.packageName, info.activityInfo.name)); }
            catch (Exception ignored) { }
            if (banner == null) {
                try { banner = pm.getApplicationBanner(info.activityInfo.applicationInfo); }
                catch (Exception ignored) { }
            }
            if (banner == null) {
                appsWithoutBanner.add(key);
                return null;
            }
            try {
                Bitmap decoded = drawableToBitmap(banner, 320, 180);
                appBannerCache.put(key, decoded);
                return decoded;
            } catch (Exception ignored) {
                appsWithoutBanner.add(key);
                return null;
            }
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

package com.gastonpg.replicagoogletv;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.InputStream;
import java.util.*;

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
        private float sx, sy;
        // Focus index 0-4: Seguir viendo; 5-13: apps.
        private int focused = 0;
        private final String[] nav = {"Para ti", "Películas", "Series", "Apps", "Biblioteca"};
        private final String[] apps = {"NETFLIX", "YouTube", "Disney+", "prime video", "max", "Spotify", "TCL CHANNEL", "YouTube Kids", "tv"};
        private final int[] appColors = {
            0xFF090909, 0xFFF7F7F7, 0xFF07132E, 0xFF071D32, 0xFF1018A0,
            0xFF101010, 0xFFB92027, 0xFFFFFFFF, 0xFF080808
        };
        private final int[] appTextColors = {
            0xFFE50914, 0xFF202020, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
            0xFF1ED760, 0xFFFFFFFF, 0xFF202020, 0xFFFFFFFF
        };
        private final String[] continueTitles = {"", "", "", "", ""};

        TVHome(Activity activity) {
            super(activity);
            a = activity;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            String saved = a.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PROFILE_URI, null);
            if (saved != null) setProfileImage(Uri.parse(saved));
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
                BitmapFactory.Options logoOptions = new BitmapFactory.Options();
                logoOptions.inScaled = false;
                Bitmap decodedLogo = BitmapFactory.decodeResource(getResources(), R.drawable.pg_logo, logoOptions);
                Bitmap logo = decodedLogo;
                if (decodedLogo != null) {
                    // Match the earlier version: remove only near-white background pixels.
                    // Leave the silver PG artwork and every other pixel unchanged.
                    Bitmap keyedLogo = Bitmap.createBitmap(decodedLogo.getWidth(), decodedLogo.getHeight(), Bitmap.Config.ARGB_8888);
                    int[] pixels = new int[decodedLogo.getWidth() * decodedLogo.getHeight()];
                    decodedLogo.getPixels(pixels, 0, decodedLogo.getWidth(), 0, 0, decodedLogo.getWidth(), decodedLogo.getHeight());
                    for (int i = 0; i < pixels.length; i++) {
                        int color = pixels[i];
                        int a = Color.alpha(color);
                        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
                        int min = Math.min(red, Math.min(green, blue));
                        int max = Math.max(red, Math.max(green, blue));
                        if (a > 0 && min >= 245 && max >= 245) {
                            pixels[i] = Color.TRANSPARENT;
                        }
                    }
                    keyedLogo.setPixels(pixels, 0, decodedLogo.getWidth(), 0, 0, decodedLogo.getWidth(), decodedLogo.getHeight());
                    logo = keyedLogo;
                    p.setAlpha(255);
                    p.setFilterBitmap(true);
                    c.drawBitmap(logo, null, new RectF(70, 39, 124, 97), p);
                    reset();
                } else {
                    centerLabel("PG", 97, 80, 28, 0xFFFFFFFF, true);
                }
            } catch (Exception ignored) {
                centerLabel("PG", 97, 80, 28, 0xFFFFFFFF, true);
            }
            label("Gastón Puig", 140, 75, 18, 0xFFFFFFFF, true);
            glass(502, 42, 672, 100, 29, false);
            label("⌕", 522, 79, 29, 0xFFDDEAFF, true);
            label("Buscar", 555, 77, 19, 0xFFE1E7EF, false);

            float nx = 728;
            String[] navLabels = {"Para ti", "Películas", "Series", "Apps", "Biblioteca"};
            float[] widths = {65, 102, 70, 62, 108};
            for (int i = 0; i < navLabels.length; i++) {
                int color = i == 0 ? 0xFFF5F8FC : 0xFFB0BAC9;
                label(navLabels[i], nx, 75, 19, color, i == 0);
                nx += widths[i] + 80;
            }
            glass(1685, 46, 1735, 96, 25, false);
            centerLabel("⚙", 1710, 77, 23, 0xFFE8F0FA, true);
            glass(1751, 46, 1801, 96, 25, false);
            drawProfileAvatar();

            // Hero surface: kept clean as in the reference, without invented marketing copy.
            glass(73, 138, 1847, 480, 24, false);
            // Subtle light on the hero's right side to give the empty artwork panel depth.
            reset();
            p.setShader(new LinearGradient(950, 138, 1847, 480,
                new int[]{0x00000000, 0x143D5D80, 0x253D5575},
                null, Shader.TileMode.CLAMP));
            c.drawRoundRect(73, 138, 1847, 480, 24, 24, p);
            reset();
            for (int i = 0; i < 5; i++) {
                fill(i == 0 ? 0xFFF3F7FC : 0x668D9EB2);
                c.drawCircle(126 + i * 25, 447, i == 0 ? 5 : 4, p);
            }

            // Continue watching row.
            label("Seguir viendo", 80, 541, 24, 0xFFE2E8F0, true);
            float[] cardX = {72, 447, 802, 1145, 1508};
            float[] cardW = {355, 332, 332, 332, 332};
            for (int i = 0; i < cardX.length; i++) {
                float x = cardX[i], w = cardW[i];
                boolean active = focused == i;
                rect(x, 560, x + w, 560 + (active ? 188 : 180), active ? 20 : 18,
                    i == 0 ? 0xB51E293B : 0x7D1E293B);
                // Fine internal light gradient gives the cards their frosted surface.
                reset();
                p.setShader(new LinearGradient(x, 560, x + w, 740,
                    new int[]{0x2AFFFFFF, 0x101A2638, 0x20101828},
                    null, Shader.TileMode.CLAMP));
                c.drawRoundRect(x, 560, x + w, 560 + (active ? 188 : 180), active ? 20 : 18, active ? 20 : 18, p);
                reset();
                if (active) glass(x, 560, x + w, 748, 20, true);
            }

            // Apps row, including the partially clipped Apple TV card at the right edge.
            label("Tus apps", 80, 823, 24, 0xFFE2E8F0, true);
            float[] appX = {72, 308, 526, 748, 974, 1188, 1408, 1628, 1860};
            float[] appW = {210, 196, 196, 202, 190, 196, 196, 206, 60};
            for (int i = 0; i < apps.length; i++) {
                float x = appX[i], w = appW[i];
                boolean active = focused == 5 + i;
                rect(x, 852, x + w, 964, 16, appColors[i]);
                if (i == 0) {
                    centerLabel("NETFLIX", x + w / 2, 920, 23, appTextColors[i], true);
                } else if (i == 1) {
                    centerLabel("You", x + w / 2, 904, 21, 0xFF252525, true);
                    centerLabel("▶Tube", x + w / 2, 930, 21, 0xFF252525, true);
                    rect(x + w/2 - 20, 882, x + w/2 + 20, 910, 7, 0xFFE62117);
                    centerLabel("▶", x + w/2, 902, 15, 0xFFFFFFFF, true);
                } else if (i == 2) {
                    centerLabel("Disney+", x + w / 2, 917, 23, appTextColors[i], true);
                } else if (i == 3) {
                    centerLabel("prime video", x + w / 2, 916, 21, appTextColors[i], true);
                    centerLabel("⌣", x + w / 2, 935, 18, 0xFF39B7F2, true);
                } else if (i == 4) {
                    centerLabel("max", x + w / 2, 924, 37, 0xFFFFFFFF, true);
                } else if (i == 5) {
                    centerLabel("Spotify", x + w / 2, 920, 25, appTextColors[i], true);
                } else if (i == 6) {
                    centerLabel("TCL", x + w / 2, 913, 24, appTextColors[i], true);
                    centerLabel("CHANNEL", x + w / 2, 934, 13, appTextColors[i], true);
                } else if (i == 7) {
                    centerLabel("YouTube", x + w / 2, 908, 19, 0xFF242424, true);
                    centerLabel("Kids", x + w / 2, 932, 21, 0xFF242424, true);
                } else {
                    centerLabel("tv", x + w / 2, 920, 24, 0xFFFFFFFF, true);
                }
                if (active) glass(x - 2, 850, x + w + 2, 966, 17, true);
            }

            c.restore();
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float x = event.getX() / sx;
                float y = event.getY() / sy;
                if (x >= 1745 && x <= 1810 && y >= 40 && y <= 105) {
                    ((MainActivity) a).chooseProfileImage();
                    return true;
                }
            }
            return true;
        }

        @Override public boolean onKeyDown(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused == 0) focused = 4;
                else if (focused >= 5 && focused <= 13) focused = focused == 5 ? 13 : focused - 1;
                else focused--;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (focused >= 0 && focused <= 4) focused = (focused + 1) % 5;
                else focused = focused == 13 ? 5 : focused + 1;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (focused <= 4) focused = 5;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (focused >= 5) focused = 0;
                invalidate(); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (focused >= 5) launchApp(focused - 5);
                else if (focused == 4) ((MainActivity) a).chooseProfileImage();
                return true;
            }
            return super.onKeyDown(key, event);
        }

        private void launchApp(int index) {
            if (index < 0 || index >= apps.length) return;
            PackageManager pm = a.getPackageManager();
            String[] packages = {
                "com.netflix.ninja",
                "com.google.android.youtube.tv",
                "com.disney.disneyplus",
                "com.amazon.amazonvideo.livingroom",
                "com.hbo.hbonow",
                "com.spotify.tv.android",
                "",
                "com.google.android.youtube.tvkids",
                "com.apple.atve.androidtv.appletv"
            };
            Intent intent = packages[index].isEmpty() ? null : pm.getLaunchIntentForPackage(packages[index]);
            if (intent == null && index == 6) {
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    String label = String.valueOf(pm.getApplicationLabel(info)).toLowerCase(Locale.ROOT);
                    if (label.contains("tcl channel")) { intent = pm.getLaunchIntentForPackage(info.packageName); if (intent != null) break; }
                }
            }
            if (intent == null && index == 1) intent = pm.getLaunchIntentForPackage("com.google.android.youtube");
            if (intent != null) {
                try { a.startActivity(intent); } catch (Exception ignored) { }
            }
        }
    }
}

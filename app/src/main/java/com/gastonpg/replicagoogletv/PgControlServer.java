package com.gastonpg.replicagoogletv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** LAN-only PG Control bridge. All writes are limited to launcher preferences and icon files. */
public final class PgControlServer extends NanoHTTPD {
    public static final int PORT = 8765;
    private static final String PREFS = "pg_control_connection";
    private static PgControlServer instance;
    private final Context app;

    private PgControlServer(Context context) throws Exception {
        super("0.0.0.0", PORT);
        app = context.getApplicationContext();
        start(SOCKET_READ_TIMEOUT, false);
    }

    public static synchronized void ensureStarted(Context context) {
        try {
            if (instance != null && instance.isAlive()) return;
            instance = new PgControlServer(context);
        } catch (Exception e) { instance = null; }
    }

    public static String token(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = p.getString("token", "");
        if (value.isEmpty()) {
            value = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("token", value).apply();
        }
        return value;
    }

    @Override public Response serve(IHTTPSession session) {
        try {
            String path = session.getUri();
            if ("GET".equals(session.getMethod().name()) && "/v1/health".equals(path)) {
                JSONObject o = new JSONObject(); o.put("ok", true); o.put("service", "PG Control Agent"); o.put("protocol", 1);
                return json(Response.Status.OK, o);
            }
            if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, error("unauthorized"));
            if ("GET".equals(session.getMethod().name())) {
                if ("/v1/device".equals(path)) return device();
                if ("/v1/packages".equals(path)) return packages();
                if ("/v1/launcher/app-configs".equals(path)) return configs();
                if ("/v1/launcher/widgets".equals(path)) return widgets();
                if ("/v1/launcher/card-scale".equals(path)) return cardScale();
                if ("/v1/launcher/layout".equals(path)) {
                    JSONObject o = new JSONObject();
                    o.put("layout", app.getSharedPreferences("pg_launcher_preferences", Context.MODE_PRIVATE).getString("apps_layout", "horizontal"));
                    return json(Response.Status.OK, o);
                }
            }
            if ("POST".equals(session.getMethod().name()) && "/v1/launcher/app-config".equals(path)) return updateApp(session);
            if ("POST".equals(session.getMethod().name()) && "/v1/launcher/widgets".equals(path)) return updateWidgets(session);
            if ("POST".equals(session.getMethod().name()) && "/v1/launcher/card-scale".equals(path)) return updateCardScale(session);
            if ("POST".equals(session.getMethod().name()) && "/v1/launcher/layout".equals(path)) return updateLayout(session);
            return json(Response.Status.NOT_FOUND, error("not_found"));
        } catch (Exception e) { return json(Response.Status.BAD_REQUEST, error("bad_request")); }
    }

    private boolean authorized(IHTTPSession s) {
        String h = s.getHeaders().get("authorization");
        return h != null && constant("Bearer " + token(app), h.trim());
    }

    private Response device() throws Exception {
        JSONObject o = new JSONObject();
        o.put("model", android.os.Build.MODEL); o.put("manufacturer", android.os.Build.MANUFACTURER);
        o.put("androidRelease", android.os.Build.VERSION.RELEASE); o.put("sdkInt", android.os.Build.VERSION.SDK_INT);
        o.put("device", android.os.Build.DEVICE); o.put("protocol", 1);
        o.put("launcher", "Réplica Google TV PG");
        return json(Response.Status.OK, o);
    }

    private Response packages() throws Exception {
        JSONArray a = new JSONArray();
        PackageManager pm = app.getPackageManager();
        android.content.Intent q = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        q.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(q, 0);
        android.content.Intent tv = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        tv.addCategory("android.intent.category.LEANBACK_LAUNCHER");
        java.util.LinkedHashMap<String, android.content.pm.ResolveInfo> unique = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : list) if (ri.activityInfo != null && !ri.activityInfo.packageName.equals(app.getPackageName())) unique.put(ri.activityInfo.packageName, ri);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(tv, 0)) if (ri.activityInfo != null && !ri.activityInfo.packageName.equals(app.getPackageName())) unique.put(ri.activityInfo.packageName, ri);
        for (Map.Entry<String, android.content.pm.ResolveInfo> e : unique.entrySet()) {
            JSONObject x = new JSONObject(); x.put("packageName", e.getKey());
            x.put("label", String.valueOf(e.getValue().loadLabel(pm))); a.put(x);
        }
        JSONObject out = new JSONObject(); out.put("apps", a); out.put("count", a.length());
        return json(Response.Status.OK, out);
    }

    private Response configs() throws Exception {
        android.content.SharedPreferences p = app.getSharedPreferences("pg_launcher_app_config", Context.MODE_PRIVATE);
        JSONObject all = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            try { all.put(e.getKey(), new JSONObject(String.valueOf(e.getValue()))); } catch (Exception ignored) {}
        }
        JSONObject out = new JSONObject(); out.put("configs", all);
        return json(Response.Status.OK, out);
    }

    private Response updateApp(IHTTPSession s) throws Exception {
        Map<String,String> body = new HashMap<>(); s.parseBody(body);
        String raw = body.get("postData"); if (raw == null) return json(Response.Status.BAD_REQUEST, error("missing_body"));
        JSONObject in = new JSONObject(raw);
        String pkg = in.optString("packageName", "").trim();
        if (pkg.isEmpty() || pkg.length() > 200) return json(Response.Status.BAD_REQUEST, error("invalid_package"));
        try { app.getPackageManager().getApplicationInfo(pkg, 0); }
        catch (Exception e) { return json(Response.Status.NOT_FOUND, error("package_not_installed")); }
        android.content.SharedPreferences prefs = app.getSharedPreferences("pg_launcher_app_config", Context.MODE_PRIVATE);
        JSONObject cfg = new JSONObject(prefs.getString(pkg, "{}"));
        if (in.has("label")) cfg.put("label", in.optString("label").trim());
        if (in.has("hidden")) cfg.put("hidden", in.optBoolean("hidden"));
        if (in.has("order")) cfg.put("order", clamp(in.optInt("order", 10000), 0, 10000));
        if (in.has("category")) cfg.put("category", in.optString("category").trim());
        if (in.has("cardWidthDp")) cfg.put("cardWidthDp", clamp(in.optInt("cardWidthDp", 196), 100, 420));
        if (in.has("cardHeightDp")) cfg.put("cardHeightDp", clamp(in.optInt("cardHeightDp", 110), 60, 240));
        if (in.has("cardStyle")) {
            String v = in.optString("cardStyle");
            if (!v.matches("glass|solid|outline|minimal")) return json(Response.Status.BAD_REQUEST, error("invalid_card_style"));
            cfg.put("cardStyle", v);
        }
        if (in.has("cardRadiusDp")) {
            int v = in.optInt("cardRadiusDp", 16);
            if (v != 0 && v != 8 && v != 16 && v != 28) return json(Response.Status.BAD_REQUEST, error("invalid_card_radius"));
            cfg.put("cardRadiusDp", v);
        }
        if (in.optBoolean("restoreOriginalIcon", false)) {
            File dir = new File(app.getFilesDir(), "pg_control_icons");
            String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
            File custom = new File(dir, safe + ".png");
            if (custom.exists() && !custom.delete())
                return json(Response.Status.INTERNAL_ERROR, error("icon_restore_failed"));
            cfg.remove("hasCustomIcon");
        }
        if (in.optBoolean("removeCustomBanner", false)) {
            File dir = new File(app.getFilesDir(), "pg_control_banners");
            String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
            File custom = new File(dir, safe + ".png");
            if (custom.exists() && !custom.delete())
                return json(Response.Status.INTERNAL_ERROR, error("banner_remove_failed"));
            cfg.remove("hasCustomBanner");
        }
        if (in.has("bannerBase64")) {
            String encoded = in.optString("bannerBase64", "");
            if (encoded.length() > 9000000) return json(Response.Status.BAD_REQUEST, error("banner_too_large"));
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth != 320 || bounds.outHeight != 180)
                return json(Response.Status.BAD_REQUEST, error("banner_must_be_320x180"));
            File dir = new File(app.getFilesDir(), "pg_control_banners");
            if (!dir.exists() && !dir.mkdirs()) return json(Response.Status.INTERNAL_ERROR, error("banner_storage_failed"));
            String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
            File target = new File(dir, safe + ".png");
            File temp = new File(dir, safe + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) { temp.delete(); return json(Response.Status.INTERNAL_ERROR, error("banner_replace_failed")); }
            if (!temp.renameTo(target)) { temp.delete(); return json(Response.Status.INTERNAL_ERROR, error("banner_storage_failed")); }
            cfg.put("hasCustomBanner", true);
        }
        if (in.has("copyIconFromPackage")) {
            String sourcePkg = in.optString("copyIconFromPackage", "").trim();
            if (sourcePkg.isEmpty() || sourcePkg.length() > 200)
                return json(Response.Status.BAD_REQUEST, error("invalid_icon_source"));
            try {
                android.graphics.drawable.Drawable drawable = app.getPackageManager().getApplicationIcon(sourcePkg);
                Bitmap icon = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(icon);
                drawable.setBounds(0, 0, 256, 256);
                drawable.draw(canvas);
                File dir = new File(app.getFilesDir(), "pg_control_icons");
                if (!dir.exists() && !dir.mkdirs()) {
                    icon.recycle();
                    return json(Response.Status.INTERNAL_ERROR, error("icon_storage_failed"));
                }
                String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
                File target = new File(dir, safe + ".png");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    if (!icon.compress(Bitmap.CompressFormat.PNG, 100, out))
                        return json(Response.Status.INTERNAL_ERROR, error("icon_write_failed"));
                    out.getFD().sync();
                } finally { icon.recycle(); }
                cfg.put("hasCustomIcon", true);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return json(Response.Status.NOT_FOUND, error("icon_source_not_installed"));
            } catch (Exception e) {
                return json(Response.Status.INTERNAL_ERROR, error("icon_copy_failed"));
            }
        }
        if (in.has("iconBase64")) {
            String encoded = in.optString("iconBase64", "");
            if (encoded.length() > 1400000) return json(Response.Status.BAD_REQUEST, error("icon_too_large"));
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null || bmp.getWidth() > 512 || bmp.getHeight() > 512) {
                if (bmp != null) bmp.recycle();
                return json(Response.Status.BAD_REQUEST, error("invalid_icon"));
            }
            File dir = new File(app.getFilesDir(), "pg_control_icons");
            if (!dir.exists() && !dir.mkdirs()) return json(Response.Status.INTERNAL_ERROR, error("icon_storage_failed"));
            String safe = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
            try (FileOutputStream out = new FileOutputStream(new File(dir, safe + ".png"))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally { bmp.recycle(); }
            cfg.put("hasCustomIcon", true);
        }
        prefs.edit().putString(pkg, cfg.toString()).apply();
        boolean bannerOnly = (in.has("bannerBase64") || in.optBoolean("removeCustomBanner", false))
            && !in.has("label") && !in.has("hidden") && !in.has("order") && !in.has("category")
            && !in.has("cardWidthDp") && !in.has("cardHeightDp") && !in.has("cardStyle")
            && !in.has("cardRadiusDp") && !in.has("iconBase64") && !in.has("copyIconFromPackage") && !in.optBoolean("restoreOriginalIcon", false);
        app.sendBroadcast(new Intent("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED")
            .setPackage(app.getPackageName()).putExtra("pg_banner_only", bannerOnly));
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("packageName", pkg); out.put("config", cfg);
        return json(Response.Status.OK, out);
    }


    private Response widgets() throws Exception {
        android.content.SharedPreferences p = app.getSharedPreferences("pg_launcher_widgets", Context.MODE_PRIVATE);
        JSONArray items = new JSONArray(p.getString("widgets", "[]"));
        JSONObject out = new JSONObject();
        out.put("widgets", items);
        return json(Response.Status.OK, out);
    }

    private Response updateWidgets(IHTTPSession s) throws Exception {
        Map<String,String> body = new HashMap<>();
        s.parseBody(body);
        String raw = body.get("postData");
        if (raw == null) return json(Response.Status.BAD_REQUEST, error("missing_body"));
        JSONObject in = new JSONObject(raw);
        JSONArray source = in.optJSONArray("widgets");
        if (source == null || source.length() > 12)
            return json(Response.Status.BAD_REQUEST, error("invalid_widget_list"));
        JSONArray clean = new JSONArray();
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) return json(Response.Status.BAD_REQUEST, error("invalid_widget"));
            String id = item.optString("id", "").trim();
            String type = item.optString("type", "").trim();
            String style = item.optString("style", "glass").trim();
            if (id.isEmpty() || id.length() > 40 || !ids.add(id))
                return json(Response.Status.BAD_REQUEST, error("invalid_widget_id"));
            if (!type.matches("clock|date|clock_date|device|wifi|greeting"))
                return json(Response.Status.BAD_REQUEST, error("invalid_widget_type"));
            if (!style.matches("glass|cinematic|solid|outline"))
                return json(Response.Status.BAD_REQUEST, error("invalid_widget_style"));
            int w = clamp(item.optInt("w", 320), 140, 900);
            int h = clamp(item.optInt("h", 140), 70, 360);
            int x = clamp(item.optInt("x", 1400), 0, 1920 - w);
            int y = clamp(item.optInt("y", 140), 0, 1080 - h);
            JSONObject saved = new JSONObject();
            saved.put("id", id); saved.put("type", type); saved.put("style", style);
            saved.put("x", x); saved.put("y", y); saved.put("w", w); saved.put("h", h);
            clean.put(saved);
        }
        app.getSharedPreferences("pg_launcher_widgets", Context.MODE_PRIVATE)
            .edit().putString("widgets", clean.toString()).apply();
        app.sendBroadcast(new Intent("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED")
            .setPackage(app.getPackageName()).putExtra("pg_widgets_changed", true));
        JSONObject out = new JSONObject();
        out.put("ok", true); out.put("count", clean.length()); out.put("widgets", clean);
        return json(Response.Status.OK, out);
    }

    private Response cardScale() throws Exception {
        float scale = app.getSharedPreferences("pg_launcher_preferences", Context.MODE_PRIVATE)
            .getFloat("apps_card_scale", 1.0f);
        JSONObject out = new JSONObject();
        out.put("scale", scale);
        return json(Response.Status.OK, out);
    }

    private Response updateCardScale(IHTTPSession s) throws Exception {
        Map<String,String> body = new HashMap<>();
        s.parseBody(body);
        String raw = body.get("postData");
        if (raw == null) return json(Response.Status.BAD_REQUEST, error("missing_body"));
        JSONObject in = new JSONObject(raw);
        double requested = in.optDouble("scale", 1.0);
        if (Double.isNaN(requested) || Double.isInfinite(requested) || requested < 0.75 || requested > 1.50)
            return json(Response.Status.BAD_REQUEST, error("invalid_card_scale"));
        float scale = (float) requested;
        app.getSharedPreferences("pg_launcher_preferences", Context.MODE_PRIVATE)
            .edit().putFloat("apps_card_scale", scale).apply();
        app.sendBroadcast(new Intent("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED")
            .setPackage(app.getPackageName()).putExtra("pg_card_scale_changed", true));
        JSONObject out = new JSONObject();
        out.put("ok", true); out.put("scale", scale);
        return json(Response.Status.OK, out);
    }

    private Response updateLayout(IHTTPSession s) throws Exception {
        Map<String,String> body = new HashMap<>(); s.parseBody(body);
        JSONObject in = new JSONObject(body.get("postData"));
        String layout = in.optString("layout", "horizontal");
        if (!layout.matches("horizontal|grid|category_rows|category_grid"))
            return json(Response.Status.BAD_REQUEST, error("invalid_layout"));
        app.getSharedPreferences("pg_launcher_preferences", Context.MODE_PRIVATE).edit().putString("apps_layout", layout).apply();
        app.sendBroadcast(new Intent("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED").setPackage(app.getPackageName()));
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("layout", layout);
        return json(Response.Status.OK, out);
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private boolean constant(String a, String b) {
        if (a.length() != b.length()) return false; int d = 0;
        for (int i=0;i<a.length();i++) d |= a.charAt(i) ^ b.charAt(i);
        return d == 0;
    }
    private JSONObject error(String e) { JSONObject o = new JSONObject(); try { o.put("ok", false); o.put("error", e); } catch (Exception ignored) { } return o; }
    private Response json(Response.Status status, JSONObject o) {
        Response r = newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString());
        r.addHeader("Cache-Control", "no-store"); return r;
    }
}

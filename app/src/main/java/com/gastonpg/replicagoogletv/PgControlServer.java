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
                if ("/v1/launcher/layout".equals(path)) {
                    JSONObject o = new JSONObject();
                    o.put("layout", app.getSharedPreferences("pg_launcher_preferences", Context.MODE_PRIVATE).getString("apps_layout", "horizontal"));
                    return json(Response.Status.OK, o);
                }
            }
            if ("POST".equals(session.getMethod().name()) && "/v1/launcher/app-config".equals(path)) return updateApp(session);
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
        app.sendBroadcast(new Intent("com.gastonpg.replicagoogletv.PG_CONTROL_CONFIG_CHANGED").setPackage(app.getPackageName()));
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("packageName", pkg); out.put("config", cfg);
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

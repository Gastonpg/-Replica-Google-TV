package com.gastonpg.replicagoogletv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android 9-compatible recent-app switcher with upward swipe / DPAD_UP force-stop. */
public final class RecentAppsDialog {
    private RecentAppsDialog() {}

    private static final class Entry {
        String pkg;
        String label;
        Intent intent;
        Drawable icon;
        View card;
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        List<Entry> entries = collect(activity);
        if (entries.isEmpty()) {
            Toast.makeText(activity, "No hay aplicaciones recientes para mostrar.", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 30), dp(activity, 22), dp(activity, 30), dp(activity, 22));
        root.setBackground(background(0xf0151d2a, 0x99f5f8ff, dp(activity, 22)));

        TextView title = new TextView(activity);
        title.setText("APLICACIONES RECIENTES");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(activity, 32)));

        TextView hint = new TextView(activity);
        hint.setText("Deslizá una tarjeta hacia arriba o presioná ↑ para cerrarla · OK para volver");
        hint.setTextColor(0xffcbd8e8);
        hint.setTextSize(12);
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(activity, 28)));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout rail = new LinearLayout(activity);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setClipToPadding(false);
        scroll.addView(rail, new HorizontalScrollView.LayoutParams(-2, -1));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(activity, 190));
        scrollParams.topMargin = dp(activity, 12);
        root.addView(scroll, scrollParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.78f;
            window.setAttributes(lp);
        }

        Handler main = new Handler(Looper.getMainLooper());
        for (Entry entry : entries) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 8));
            card.setBackground(background(0x99263346, 0x77ffffff, dp(activity, 16)));

            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (entry.icon != null) icon.setImageDrawable(entry.icon);
            card.addView(icon, new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54)));

            TextView label = new TextView(activity);
            label.setText(entry.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(14);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, dp(activity, 42));
            labelLp.topMargin = dp(activity, 5);
            card.addView(label, labelLp);

            TextView closeHint = new TextView(activity);
            closeHint.setText("↑  CERRAR");
            closeHint.setTextColor(0xffd8eaff);
            closeHint.setTextSize(11);
            closeHint.setGravity(Gravity.CENTER);
            card.addView(closeHint, new LinearLayout.LayoutParams(-1, dp(activity, 22)));

            entry.card = card;
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(dp(activity, 205), dp(activity, 166));
            item.rightMargin = dp(activity, 13);
            rail.addView(card, item);

            card.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(background(focused ? 0xdd33475e : 0x99263346,
                    focused ? 0xfff5fbff : 0x77ffffff, dp(activity, 16)));
                v.setScaleX(focused ? 1.045f : 1f);
                v.setScaleY(focused ? 1.045f : 1f);
                v.setElevation(focused ? dp(activity, 10) : 0);
            });

            card.setOnClickListener(v -> {
                try {
                    Intent launch = new Intent(entry.intent);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    activity.startActivity(launch);
                    dialog.dismiss();
                } catch (Exception ex) {
                    Toast.makeText(activity, "No se pudo volver a esa aplicación.", Toast.LENGTH_SHORT).show();
                }
            });

            final float[] touchStartY = new float[]{0f};
            card.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    touchStartY[0] = event.getY();
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float distance = touchStartY[0] - event.getY();
                    if (distance > dp(activity, 48)) {
                        forceStop(activity, entry, dialog, main);
                        return true;
                    }
                }
                return false;
            });

            card.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                    forceStop(activity, entry, dialog, main);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                    dialog.dismiss();
                    return true;
                }
                return false;
            });
        }

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        entries.get(0).card.requestFocus();
    }

    private static List<Entry> collect(Activity activity) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = activity.getPackageManager();
            List<ActivityManager.RecentTaskInfo> tasks =
                manager.getRecentTasks(40, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo task : tasks) {
                Intent base = task.baseIntent;
                if (base == null) continue;
                ComponentName component = base.getComponent();
                if (component == null && task.baseActivity != null) component = task.baseActivity;
                if (component == null) continue;
                String pkg = component.getPackageName();
                if (pkg.equals(activity.getPackageName()) || pkg.equals("com.android.systemui") ||
                    pkg.equals("android") || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") ||
                    !seen.add(pkg)) continue;
                try {
                    Entry entry = new Entry();
                    entry.pkg = pkg;
                    entry.intent = pm.getLaunchIntentForPackage(pkg);
                    if (entry.intent == null) entry.intent = new Intent(base);
                    CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                    entry.label = label == null ? pkg : label.toString();
                    try { entry.icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) { }
                    out.add(entry);
                } catch (Exception ignored) { }
            }
        } catch (SecurityException ignored) {
            // Some Android builds restrict getRecentTasks for third-party launchers.
        } catch (Exception ignored) { }
        return out;
    }

    private static void forceStop(Activity activity, Entry entry, Dialog dialog, Handler main) {
        Toast.makeText(activity, "Cerrando " + entry.label + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean stopped = false;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "am force-stop " + entry.pkg)
                    .redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) { }
                stopped = process.waitFor() == 0;
            } catch (Exception ignored) {
                stopped = false;
            } finally {
                if (process != null) try { process.destroy(); } catch (Exception ignored) { }
            }
            final boolean success = stopped;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (success) {
                    if (entry.card != null && entry.card.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) entry.card.getParent()).removeView(entry.card);
                    }
                    Toast.makeText(activity, entry.label + " se cerró.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity,
                        "No se pudo cerrar la app. Revisá que el acceso root esté autorizado.",
                        Toast.LENGTH_LONG).show();
                }
            });
        }, "PG-ForceStop").start();
    }

    private static GradientDrawable background(int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{fill, (fill & 0x00ffffff) | (Math.min(255, Color.alpha(fill) + 8) << 24)});
        drawable.setCornerRadius(radius);
        drawable.setStroke(2, stroke);
        return drawable;
    }

    private static int dp(Context context, float value) {
        return (int)(value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}

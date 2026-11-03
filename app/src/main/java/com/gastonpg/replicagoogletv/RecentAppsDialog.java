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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android 9-compatible recent-app switcher with upward swipe / DPAD_UP force-stop. */
public final class RecentAppsDialog {
    private RecentAppsDialog() {}

    private static final class Entry {
        String pkg;
        int taskId = -1;
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
                moveTaskToFront(activity, entry, dialog, main);
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
        Process process = null;
        try {
            PackageManager pm = activity.getPackageManager();

            // This Android 9 TV ROM exposes its real recent-task list through dumpsys,
            // while ActivityManager.getRecentTasks() returns an empty list to launchers.
            process = new ProcessBuilder("su", "0", "sh", "-c", "dumpsys activity recents")
                .redirectErrorStream(true).start();
            StringBuilder dump = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) dump.append(line).append('\n');
            process.waitFor();

            // This ROM stores the app identifier in the TaskRecord header (A= or I=).
            // Most entries do not contain a following realActivity line.
            Pattern recent = Pattern.compile(
                "(?m)^\\s*\\* Recent #\\d+: TaskRecord\\{[^\\n]*?#(\\d+)\\s+(?:A|I)=([^\\s}]+)"
            );
            Matcher matcher = recent.matcher(dump.toString());
            while (matcher.find() && out.size() < 40) {
                int taskId;
                try { taskId = Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { continue; }
                String taskName = matcher.group(2);
                ComponentName component = taskName.contains("/")
                    ? ComponentName.unflattenFromString(taskName) : null;
                String pkg = component != null ? component.getPackageName() : taskName;
                if (pkg.equals(activity.getPackageName()) || pkg.equals("com.android.systemui") ||
                    pkg.equals("android") || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") ||
                    !seen.add(pkg)) continue;
                try {
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch == null) {
                        launch = new Intent(Intent.ACTION_MAIN);
                        launch.addCategory(Intent.CATEGORY_LAUNCHER);
                        launch.setComponent(component);
                    }
                    Entry entry = new Entry();
                    entry.pkg = pkg;
                    entry.taskId = taskId;
                    entry.intent = launch;
                    CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                    entry.label = label == null ? pkg : label.toString();
                    try { entry.icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) { }
                    out.add(entry);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
            // Root access or dumpsys may be unavailable; keep the switcher safely empty.
        } finally {
            if (process != null) try { process.destroy(); } catch (Exception ignored) { }
        }
        return out;
    }

    /** Bring the existing Android task forward through the privileged ActivityManager shell. */
    private static void moveTaskToFront(Activity activity, Entry entry, Dialog dialog, Handler main) {
        new Thread(() -> {
            boolean moved = false;
            Process process = null;
            try {
                if (entry.taskId < 0) throw new IllegalStateException("Missing task id");
                process = new ProcessBuilder("su", "0", "sh", "-c",
                    "am stack move-task " + entry.taskId + " 1 true")
                    .redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\\n');
                moved = process.waitFor() == 0 &&
                    !output.toString().toLowerCase(java.util.Locale.ROOT).contains("error");
            } catch (Exception ignored) {
                moved = false;
            } finally {
                if (process != null) try { process.destroy(); } catch (Exception ignored) { }
            }
            final boolean success = moved;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (success) dialog.dismiss();
                else Toast.makeText(activity, "Android no pudo recuperar esa tarea.", Toast.LENGTH_SHORT).show();
            });
        }, "PG-TaskSwitch").start();
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

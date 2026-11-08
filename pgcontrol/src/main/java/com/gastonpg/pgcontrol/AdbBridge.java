package com.gastonpg.pgcontrol;

import android.content.Context;
import android.util.Base64;
import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct, authenticated ADB connection from PG Control to the TV Box. */
public final class AdbBridge {
    private static final String PREFS_PATH =
        "/data/data/com.gastonpg.replicagoogletv.bannereditor/shared_prefs/pg_control_connection.xml";
    private final Context context;
    private AdbConnection connection;
    private String connectedHost;
    private int connectedPort;
    private AdbStream nativeMouseStream;

    public AdbBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    private AdbCrypto crypto() throws Exception {
        File privateKey = new File(context.getFilesDir(), "pg-adbkey");
        File publicKey = new File(context.getFilesDir(), "pg-adbkey.pub");
        AdbBase64 encoder = data -> Base64.encodeToString(data, Base64.NO_WRAP);
        if (privateKey.exists() && publicKey.exists()) {
            return AdbCrypto.loadAdbKeyPair(encoder, privateKey, publicKey);
        }
        AdbCrypto generated = AdbCrypto.generateAdbKeyPair(encoder);
        generated.saveAdbKeyPair(privateKey, publicKey);
        return generated;
    }

    private synchronized void ensureConnected(String host, int port) throws Exception {
        if (connection != null && host.equals(connectedHost) && port == connectedPort) return;
        close();
        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(15000);
        AdbConnection next = AdbConnection.create(socket, crypto());
        boolean ok = next.connect(8, TimeUnit.SECONDS, false);
        if (!ok) {
            next.close();
            throw new IOException("ADB no respondió. Si aparece una autorización RSA en el televisor, aceptala y volvé a intentar.");
        }
        connection = next;
        connectedHost = host;
        connectedPort = port;
    }

    public synchronized String readToken(String host, int port) throws Exception {
        String xml = shell(host, port, "su 0 cat " + PREFS_PATH);
        Matcher matcher = Pattern.compile("<string name=\"token\">([^<]+)</string>").matcher(xml);
        if (!matcher.find()) throw new IOException("No se encontró la preferencia token en el Launcher.");
        String value = matcher.group(1).trim();
        if (value.length() < 32) throw new IOException("El token leído parece incompleto.");
        return value;
    }

    public synchronized String shell(String host, int port, String command) throws Exception {
        ensureConnected(host, port);
        AdbStream stream = connection.open("shell:" + command);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            readToEnd(stream, out, 2 * 1024 * 1024);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try { stream.close(); } catch (Exception ignored) { }
        }
    }

    public synchronized byte[] screenshot(String host, int port) throws Exception {
        ensureConnected(host, port);
        AdbStream stream = connection.open("shell:screencap -p");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512 * 1024);
            readToEnd(stream, out, 12 * 1024 * 1024);
            byte[] png = out.toByteArray();
            if (png.length < 8 || png[0] != (byte)0x89 || png[1] != 0x50 ||
                png[2] != 0x4e || png[3] != 0x47) {
                throw new IOException("El TV Box no devolvió una captura PNG válida.");
            }
            return png;
        } finally {
            try { stream.close(); } catch (Exception ignored) { }
        }
    }

    private void readToEnd(AdbStream stream, ByteArrayOutputStream out, int maxBytes) throws Exception {
        while (true) {
            byte[] part;
            try {
                part = stream.read();
            } catch (IOException closed) {
                if (stream.isClosed()) break;
                throw closed;
            }
            if (part == null) break;
            out.write(part);
            if (out.size() > maxBytes) throw new IOException("La respuesta ADB es demasiado grande.");
        }
    }

    public synchronized void tap(String host, int port, int x, int y) throws Exception {
        shell(host, port, "su 0 input tap " + Math.max(0, x) + " " + Math.max(0, y));
    }

    public synchronized void swipe(String host, int port, int x1, int y1, int x2, int y2, int duration) throws Exception {
        shell(host, port, "su 0 input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration);
    }

    public synchronized void mouseTap(String host, int port, int x, int y) throws Exception {
        shell(host, port, "su 0 input mouse tap " + Math.max(0, x) + " " + Math.max(0, y));
    }

    /** Deploy and start the persistent root uinput mouse process on the TV Box. */
    public synchronized void startNativeMouse(String host, int port, byte[] executable) throws Exception {
        ensureConnected(host, port);
        if (nativeMouseStream != null && !nativeMouseStream.isClosed()) return;
        shell(host, port, "su 0 rm -f /data/local/tmp/pgmouse.b64 /data/local/tmp/pgmouse");
        String encoded = Base64.encodeToString(executable, Base64.NO_WRAP);
        for (int offset = 0; offset < encoded.length(); offset += 1800) {
            String chunk = encoded.substring(offset, Math.min(encoded.length(), offset + 1800));
            shell(host, port, "su 0 sh -c 'printf %s " + chunk + " >> /data/local/tmp/pgmouse.b64'");
        }
        shell(host, port, "su 0 sh -c 'base64 -d /data/local/tmp/pgmouse.b64 > /data/local/tmp/pgmouse && chmod 755 /data/local/tmp/pgmouse && rm -f /data/local/tmp/pgmouse.b64'");
        AdbStream stream = connection.open("shell:su 0 /data/local/tmp/pgmouse");
        StringBuilder ready = new StringBuilder();
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline && ready.indexOf("READY") < 0) {
            byte[] part = stream.read();
            if (part == null) break;
            ready.append(new String(part, "UTF-8"));
        }
        if (ready.indexOf("READY") < 0) {
            try { stream.close(); } catch (Exception ignored) { }
            throw new IOException("El mouse virtual no pudo iniciar /dev/uinput: " + ready.toString().trim());
        }
        nativeMouseStream = stream;
    }

    /** Send a relative movement packet over the already-open ADB shell stream. */
    public synchronized void nativeMouseMove(int dx, int dy) throws Exception {
        if (nativeMouseStream == null || nativeMouseStream.isClosed())
            throw new IOException("El mouse virtual no está iniciado.");
        byte[] packet = new byte[9];
        packet[0] = 1;
        putInt32(packet, 1, dx);
        putInt32(packet, 5, dy);
        nativeMouseStream.write(packet);
    }

    /** Send Android DPAD events through the persistent virtual input device. */
    public synchronized void nativeMouseKey(int androidKeyCode) throws Exception {
        if (nativeMouseStream == null || nativeMouseStream.isClosed())
            throw new IOException("El dispositivo virtual no está iniciado.");
        if (androidKeyCode != 19 && androidKeyCode != 20 && androidKeyCode != 21 && androidKeyCode != 22)
            throw new IOException("Tecla direccional no válida.");
        nativeMouseStream.write(new byte[]{4, (byte) androidKeyCode});
    }

    public synchronized void nativeMouseClick() throws Exception {
        if (nativeMouseStream == null || nativeMouseStream.isClosed())
            throw new IOException("El mouse virtual no está iniciado.");
        nativeMouseStream.write(new byte[]{2});
    }

    private static void putInt32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    public synchronized void stopNativeMouse() {
        if (nativeMouseStream != null) {
            try { if (!nativeMouseStream.isClosed()) nativeMouseStream.write(new byte[]{3}); } catch (Exception ignored) { }
            try { nativeMouseStream.close(); } catch (Exception ignored) { }
        }
        nativeMouseStream = null;
    }

    public synchronized void mouseSwipe(String host, int port, int x1, int y1, int x2, int y2, int duration) throws Exception {
        shell(host, port, "su 0 input mouse swipe " + Math.max(0, x1) + " " + Math.max(0, y1) + " " +
            Math.max(0, x2) + " " + Math.max(0, y2) + " " + Math.max(1, duration));
    }

    public synchronized void key(String host, int port, int keyCode) throws Exception {
        shell(host, port, "su 0 input keyevent " + keyCode);
    }

    public synchronized String recentTasks(String host, int port) throws Exception {
        return shell(host, port, "su 0 sh -c 'dumpsys activity recents'");
    }

    public synchronized void closeTask(String host, int port, String packageName) throws Exception {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
            throw new IOException("Paquete no válido.");
        shell(host, port, "su 0 am force-stop --user 0 " + packageName);
    }

    public synchronized void launchApp(String host, int port, String packageName) throws Exception {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
            throw new IOException("Paquete no válido.");
        shell(host, port, "su 0 monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
    }

    public synchronized void moveTask(String host, int port, int taskId) throws Exception {
        if (taskId < 0) throw new IOException("Tarea no válida.");
        shell(host, port, "su 0 am stack move-task " + taskId + " 1 true");
    }

    public synchronized void close() {
        stopNativeMouse();
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) { }
        }
        connection = null;
        connectedHost = null;
        connectedPort = 0;
    }
}

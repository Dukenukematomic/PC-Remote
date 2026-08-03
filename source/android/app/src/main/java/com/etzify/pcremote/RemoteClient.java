package com.etzify.pcremote;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Talks to remote_server.py over TCP using newline-delimited JSON.
 *
 * Finger movement arrives far faster than it is worth putting on the wire, so
 * moves are accumulated and flushed on a fixed tick. Clicks and media keys are
 * queued and always sent after any movement that preceded them, which keeps a
 * tap landing where the pointer actually is.
 */
public class RemoteClient {

    public static final int DEFAULT_PORT = 7712;

    private static final int FLUSH_INTERVAL_MS = 8;     // ~125 updates/sec
    private static final int HEARTBEAT_MS = 10000;
    private static final int CONNECT_TIMEOUT_MS = 4000;

    public interface Listener {
        void onConnected(String hostName);

        void onDisconnected(String reason);

        /**
         * What the server said about itself in its welcome. screenPort is 0
         * when this PC is not sharing its screen.
         */
        void onServerInfo(String hostName, int screenPort);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    private Socket socket;
    private Thread worker;
    private volatile boolean running;

    private float pendingDx, pendingDy;
    private boolean hasPendingMove;

    private Listener listener;

    public boolean isConnected() {
        return running;
    }

    public void connect(final String host, final int port, Listener l) {
        disconnect();
        this.listener = l;
        running = true;

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runConnection(host, port);
            }
        }, "pc-remote-client");
        worker.start();
    }

    public void disconnect() {
        running = false;
        synchronized (lock) {
            queue.clear();
            hasPendingMove = false;
            pendingDx = pendingDy = 0;
            lock.notifyAll();
        }
        closeSocket();
        worker = null;
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // -- outgoing commands -------------------------------------------------

    public void move(float dx, float dy) {
        if (!running) return;
        synchronized (lock) {
            pendingDx += dx;
            pendingDy += dy;
            hasPendingMove = true;
            lock.notifyAll();
        }
    }

    public void scroll(int dx, int dy) {
        send(String.format(Locale.US, "{\"t\":\"s\",\"dx\":%d,\"dy\":%d}", dx, dy));
    }

    public void click(String button) {
        send("{\"t\":\"c\",\"b\":\"" + button + "\"}");
    }

    public void doubleClick(String button) {
        send("{\"t\":\"dc\",\"b\":\"" + button + "\"}");
    }

    public void buttonDown(String button) {
        send("{\"t\":\"down\",\"b\":\"" + button + "\"}");
    }

    public void buttonUp(String button) {
        send("{\"t\":\"up\",\"b\":\"" + button + "\"}");
    }

    public void media(String key) {
        send("{\"t\":\"k\",\"k\":\"" + key + "\"}");
    }

    /**
     * Types literal text on the PC. Newlines become real Enter presses rather
     * than a literal control character, which is what applications expect.
     */
    public void typeText(String text) {
        if (text == null || text.isEmpty()) return;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            if (i > start) sendType(text.substring(start, i));
            key("enter");
            start = i + 1;
        }
        if (start < text.length()) sendType(text.substring(start));
    }

    private void sendType(String chunk) {
        send("{\"t\":\"type\",\"s\":\"" + escape(chunk) + "\"}");
    }

    /** enter, backspace, tab, esc, delete, arrows, home, end, pgup, pgdn. */
    public void key(String name) {
        send("{\"t\":\"key\",\"k\":\"" + name + "\"}");
    }

    /** Minimal JSON string escaping; the payload is arbitrary user text. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** Queues a command, after any movement that was already pending. */
    private void send(String json) {
        if (!running) return;
        synchronized (lock) {
            drainPendingMoveLocked();
            if (queue.size() < 256) queue.add(json);
            lock.notifyAll();
        }
    }

    /** Turns accumulated finger movement into one queued message. */
    private void drainPendingMoveLocked() {
        if (!hasPendingMove) return;
        if (pendingDx != 0 || pendingDy != 0) {
            queue.add(String.format(Locale.US,
                    "{\"t\":\"m\",\"dx\":%.2f,\"dy\":%.2f}", pendingDx, pendingDy));
        }
        pendingDx = pendingDy = 0;
        hasPendingMove = false;
    }

    // -- worker ------------------------------------------------------------

    private void runConnection(String host, int port) {
        OutputStream out;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            socket = s;
            out = new BufferedOutputStream(s.getOutputStream(), 4096);
            startReader(s);
            write(out, "{\"t\":\"hello\"}");
        } catch (Exception e) {
            running = false;
            notifyDisconnected("Could not reach " + host);
            return;
        }

        notifyConnected(host);

        long nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_MS;
        StringBuilder batch = new StringBuilder();

        try {
            while (running) {
                batch.setLength(0);

                synchronized (lock) {
                    if (queue.isEmpty() && !hasPendingMove) {
                        lock.wait(FLUSH_INTERVAL_MS);
                    }
                    drainPendingMoveLocked();
                    while (!queue.isEmpty()) {
                        batch.append(queue.poll()).append('\n');
                    }
                }

                if (batch.length() > 0) {
                    out.write(batch.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                long now = System.currentTimeMillis();
                if (now >= nextHeartbeat) {
                    write(out, "{\"t\":\"ping\"}");
                    nextHeartbeat = now + HEARTBEAT_MS;
                }
            }
        } catch (Exception e) {
            if (running) {
                running = false;
                notifyDisconnected("Connection lost");
            }
        } finally {
            closeSocket();
        }
    }

    /**
     * Drains the server's replies. The welcome is the only one that carries
     * anything we act on, but the stream has to be read regardless or the
     * heartbeat pongs would sit in the socket buffer forever.
     */
    private void startReader(final Socket s) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(),
                                    StandardCharsets.UTF_8), 1024);
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        handleReply(line);
                    }
                } catch (Exception ignored) {
                    // The write side reports the disconnect; nothing to add.
                }
            }
        }, "pc-remote-reader").start();
    }

    private void handleReply(String line) {
        line = line.trim();
        if (line.isEmpty()) return;
        try {
            JSONObject msg = new JSONObject(line);
            if (!"welcome".equals(msg.optString("t"))) return;
            final String name = msg.optString("host", "");
            final int screenPort = msg.optInt("screen", 0);
            final Listener l = listener;
            if (l == null) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (running) l.onServerInfo(name, screenPort);
                }
            });
        } catch (JSONException ignored) {
        }
    }

    private void write(OutputStream out, String json) throws Exception {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void notifyConnected(final String host) {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onConnected(host);
            }
        });
    }

    private void notifyDisconnected(final String reason) {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                l.onDisconnected(reason);
            }
        });
    }
}

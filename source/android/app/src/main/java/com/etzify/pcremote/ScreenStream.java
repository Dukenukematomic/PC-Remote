package com.etzify.pcremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the server's never-ending multipart JPEG stream and hands back decoded
 * frames.
 *
 * Every part arrives with a Content-Length, so each frame is read as an exact
 * number of bytes rather than by hunting for the next boundary in the middle
 * of binary data.
 */
public class ScreenStream {

    public static final int DEFAULT_SCREEN_PORT = 7714;

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int POOL_LIMIT = 3;
    /** Roughly a whole frame already queued behind the one just read. */
    private static final int STALE_BACKLOG_BYTES = 16 * 1024;

    public interface Listener {
        /** A decoded frame, on the main thread. Ownership passes to the view. */
        void onFrame(Bitmap frame);

        /** Human-readable status; error means the stream has stopped. */
        void onState(String message, boolean error);
    }

    /** One display that can be watched. */
    public static class Monitor {
        public final int id;
        public final String name;

        Monitor(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public interface MonitorsListener {
        void onMonitors(List<Monitor> monitors);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Bitmap> pool = new ArrayDeque<>();

    private Thread worker;
    private volatile boolean running;
    private volatile HttpURLConnection connection;

    private String host;
    private int port;
    private int width = 960;
    private int fps = 60;
    private int quality = 60;
    private volatile int monitor;

    public boolean isRunning() {
        return running;
    }

    public int getMonitor() {
        return monitor;
    }

    public void configure(String host, int port, int width) {
        this.host = host;
        this.port = port;
        this.width = Math.max(160, Math.min(1920, width));
    }

    public void start(int monitor, Listener listener) {
        stop();
        this.monitor = monitor;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(listener);
            }
        }, "pc-screen-stream");
        worker.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection c = connection;
        connection = null;
        if (c != null) {
            // disconnect() is what unblocks a read that would otherwise sit
            // there until the read timeout expires.
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        worker = null;
        synchronized (pool) {
            for (Bitmap b : pool) b.recycle();
            pool.clear();
        }
    }

    /** Hands a frame back once the view has stopped drawing it. */
    public void release(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        synchronized (pool) {
            if (pool.size() >= POOL_LIMIT) {
                frame.recycle();
            } else {
                pool.add(frame);
            }
        }
    }

    /** Asks the PC which displays it can show. Runs on its own thread. */
    public void fetchMonitors(final MonitorsListener listener) {
        final String url = String.format(Locale.US, "http://%s:%d/monitors",
                host, port);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Monitor> out = new ArrayList<>();
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    c.setReadTimeout(CONNECT_TIMEOUT_MS);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    InputStream in = c.getInputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0 && buf.size() < 64 * 1024) {
                        buf.write(chunk, 0, n);
                    }
                    JSONArray arr = new JSONObject(buf.toString("UTF-8"))
                            .getJSONArray("monitors");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        out.add(new Monitor(m.optInt("id", i),
                                m.optString("name", "Screen " + i)));
                    }
                } catch (Exception ignored) {
                    // An older server has no /monitors; one implicit screen.
                } finally {
                    if (c != null) c.disconnect();
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onMonitors(out);
                    }
                });
            }
        }, "pc-screen-monitors").start();
    }

    // -- worker ------------------------------------------------------------

    private void pump(Listener listener) {
        String url = String.format(Locale.US,
                "http://%s:%d/stream?w=%d&fps=%d&q=%d&mon=%d",
                host, port, width, fps, quality, monitor);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            connection = c;

            int code = c.getResponseCode();
            if (code == 503) {
                fail(listener, "The PC already has too many viewers");
                return;
            }
            if (code != 200) {
                fail(listener, "The PC refused the screen (HTTP " + code + ")");
                return;
            }

            String boundary = boundaryOf(c.getHeaderField("Content-Type"));
            BufferedInputStream in =
                    new BufferedInputStream(c.getInputStream(), 64 * 1024);
            readParts(in, boundary, listener);

            if (running) fail(listener, "The screen stream ended");
        } catch (Exception e) {
            if (running) fail(listener, "Could not reach the PC's screen");
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String boundaryOf(String contentType) {
        if (contentType != null) {
            int at = contentType.indexOf("boundary=");
            if (at >= 0) {
                String b = contentType.substring(at + 9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() > 1) {
                    b = b.substring(1, b.length() - 1);
                }
                if (!b.isEmpty()) return b;
            }
        }
        return "pcremoteframe";
    }

    private void readParts(BufferedInputStream in, String boundary,
                           Listener listener) throws Exception {
        String marker = "--" + boundary;

        while (running) {
            // Walk to the boundary line, skipping the CRLF that closed the
            // previous part.
            String line = readLine(in);
            while (line != null && !line.startsWith(marker)) {
                line = readLine(in);
            }
            if (line == null) return;

            int length = -1;
            String header = readLine(in);
            while (header != null && !header.isEmpty()) {
                String lower = header.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    try {
                        length = Integer.parseInt(
                                header.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                header = readLine(in);
            }
            if (length <= 0 || length > MAX_FRAME_BYTES) return;

            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) return;
                read += n;
            }

            // If the next frame is already waiting we are behind the PC, so
            // this one is stale. Decoding it would only add to the delay
            // between a swipe and seeing the pointer move, and it would be
            // painted over immediately anyway.
            if (in.available() >= STALE_BACKLOG_BYTES) continue;

            Bitmap frame = decode(data, length);
            if (frame != null) publish(listener, frame);
        }
    }

    private Bitmap decode(byte[] data, int length) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;

        Bitmap candidate;
        synchronized (pool) {
            candidate = pool.poll();
        }
        if (candidate != null && !candidate.isRecycled()) {
            options.inBitmap = candidate;
        }

        try {
            return BitmapFactory.decodeByteArray(data, 0, length, options);
        } catch (IllegalArgumentException e) {
            // The pooled bitmap did not fit this frame after all; it is no
            // longer safe to reuse, so drop it and decode fresh.
            if (candidate != null) candidate.recycle();
            options.inBitmap = null;
            try {
                return BitmapFactory.decodeByteArray(data, 0, length, options);
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void publish(final Listener listener, final Bitmap frame) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    listener.onFrame(frame);
                } else {
                    frame.recycle();
                }
            }
        });
    }

    private void fail(final Listener listener, final String message) {
        running = false;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(message, true);
            }
        });
    }

    /** Reads one CRLF-terminated header line without over-reading the body. */
    private static String readLine(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
            if (out.size() > 8192) break;
        }
        if (b == -1 && out.size() == 0) return null;
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

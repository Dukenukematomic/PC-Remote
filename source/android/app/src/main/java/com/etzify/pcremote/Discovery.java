package com.etzify.pcremote;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Finds PCs on the local network that are running remote_server.py.
 *
 * We broadcast a short probe and listen for unicast replies on our own
 * ephemeral port. Replying directly to the sender means the phone never has to
 * receive a broadcast packet, which Android drops unless the app holds a
 * multicast lock.
 *
 * Each scan runs in its own worker that owns its socket outright. An earlier
 * version shared one socket field across restarts, which let a stopped worker
 * keep reading the live socket and swallow replies meant for the new scan --
 * the PC you had just disconnected from would then never reappear in the list.
 */
public class Discovery {

    public static final int DISCOVERY_PORT = 7713;
    private static final String MAGIC = "PCREMOTE-DISCOVER-1";
    private static final int PROBE_INTERVAL_MS = 1500;
    private static final int RECEIVE_TIMEOUT_MS = 250;

    /** A PC that answered a probe. */
    public static class Pc {
        public final String name;
        public final String host;
        public final int port;
        /** Where the screen is streamed, or 0 when this PC is not sharing. */
        public final int screenPort;

        public Pc(String name, String host, int port) {
            this(name, host, port, 0);
        }

        public Pc(String name, String host, int port, int screenPort) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.screenPort = screenPort;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pc
                    && host.equals(((Pc) o).host)
                    && port == ((Pc) o).port;
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    public interface Listener {
        /** Called on the main thread every time this scan finds a new PC. */
        void onPcsFound(List<Pc> pcs);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Worker worker;

    /** Replaces any scan in progress with a fresh one. */
    public synchronized void start(Listener listener) {
        stop();
        worker = new Worker(listener);
        worker.start();
    }

    public synchronized void stop() {
        Worker w = worker;
        worker = null;
        if (w != null) w.shutdown();
    }

    public synchronized boolean isRunning() {
        Worker w = worker;
        return w != null && w.alive;
    }

    /**
     * One scan. Owns its socket for its whole life, so shutting it down can
     * never disturb a worker that replaced it.
     */
    private class Worker extends Thread {

        private final Listener listener;
        private final List<Pc> found = new ArrayList<>();
        private volatile boolean alive = true;
        private volatile DatagramSocket sock;

        Worker(Listener listener) {
            super("pc-discovery");
            this.listener = listener;
        }

        void shutdown() {
            alive = false;
            DatagramSocket s = sock;
            if (s != null) s.close();
        }

        @Override
        public void run() {
            DatagramSocket s;
            try {
                s = new DatagramSocket();
                s.setBroadcast(true);
                s.setSoTimeout(RECEIVE_TIMEOUT_MS);
            } catch (Exception e) {
                return;
            }
            sock = s;

            // shutdown() may have been called while the socket was opening.
            if (!alive) {
                s.close();
                return;
            }

            try {
                loop(s);
            } finally {
                s.close();
            }
        }

        private void loop(DatagramSocket s) {
            long nextProbe = 0;
            byte[] buf = new byte[1024];

            while (alive) {
                long now = System.currentTimeMillis();
                if (now >= nextProbe) {
                    sendProbes(s);
                    nextProbe = now + PROBE_INTERVAL_MS;
                }

                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    s.receive(packet);
                    Pc pc = parse(packet);
                    if (pc != null && addIfNew(pc)) publish();
                } catch (SocketTimeoutException ignored) {
                    // expected: lets us re-probe on schedule
                } catch (Exception e) {
                    if (!alive) return;
                }
            }
        }

        private boolean addIfNew(Pc pc) {
            synchronized (found) {
                if (found.contains(pc)) return false;
                found.add(pc);
                return true;
            }
        }

        private void publish() {
            final List<Pc> copy;
            synchronized (found) {
                copy = new ArrayList<>(found);
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    // A worker that was stopped mid-flight must not write to
                    // the UI on behalf of a scan that has been replaced.
                    if (alive) listener.onPcsFound(copy);
                }
            });
        }

        private void sendProbes(DatagramSocket s) {
            byte[] data = MAGIC.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastAddresses()) {
                try {
                    s.send(new DatagramPacket(data, data.length, target,
                            DISCOVERY_PORT));
                } catch (Exception ignored) {
                    // one dead interface should not stop the others
                }
            }
        }
    }

    private static Pc parse(DatagramPacket packet) {
        try {
            String body = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(body);
            if (!"pcremote".equals(json.optString("t"))) return null;
            String host = packet.getAddress().getHostAddress();
            String name = json.optString("name", host);
            int port = json.optInt("port", RemoteClient.DEFAULT_PORT);
            // Absent on servers built before screen sharing existed.
            int screenPort = json.optInt("screen", 0);
            return new Pc(name, host, port, screenPort);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Every plausible broadcast target: the global address plus each
     * interface's own subnet broadcast, since some routers drop 255.255.255.255.
     */
    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> ifaces =
                    NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return out;
            for (NetworkInterface iface : Collections.list(ifaces)) {
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    InetAddress b = addr.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}

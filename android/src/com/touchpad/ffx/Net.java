package com.touchpad.ffx;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Sends full controller state over UDP (immediately on change, otherwise ~30Hz) and pings for latency. */
public class Net extends Thread {

    public interface StatusSink { void onStatus(String s); }

    private final String host;
    private final int port;
    private final PadView pad;
    private final StatusSink sink;
    private volatile boolean running = true;
    private final Object wake = new Object();
    private DatagramSocket sock;

    private volatile long lastPong;
    private volatile int rtt = -1;

    public Net(String host, int port, PadView pad, StatusSink sink) {
        super("touchpad-net");
        this.host = host; this.port = port; this.pad = pad; this.sink = sink;
    }

    /** Called from the UI thread after a touch event so the change goes out right away. */
    public void poke() {
        synchronized (wake) { wake.notify(); }
    }

    public void shutdown() {
        running = false;
        poke();
        DatagramSocket s = sock;
        if (s != null) s.close();
    }

    @Override
    public void run() {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
            sock = new DatagramSocket();
        } catch (Exception e) {
            sink.onStatus("地址错误");
            return;
        }
        Thread rx = new Thread(this::receive, "touchpad-rx");
        rx.setDaemon(true);
        rx.start();

        int[] cur = new int[8], last = new int[8];
        Arrays.fill(last, Integer.MIN_VALUE);
        ByteBuffer st = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer pg = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        int seq = 0, pingSeq = 0;
        long lastSend = 0, lastPing = 0, lastStatus = 0;
        lastPong = SystemClock.uptimeMillis();

        while (running) {
            synchronized (wake) {
                try { wake.wait(8); } catch (InterruptedException ignored) { }
            }
            if (!running) break;
            long now = SystemClock.uptimeMillis();
            pad.snapshot(cur);
            boolean changed = !Arrays.equals(cur, last);
            try {
                if (changed || now - lastSend >= 33) {
                    st.clear();
                    st.put((byte) 'T').put((byte) 'P').put((byte) 1).put((byte) 1);
                    st.putInt(seq++);
                    st.putShort((short) cur[0]);
                    st.putShort((short) cur[1]).putShort((short) cur[2]).putShort((short) cur[3]).putShort((short) cur[4]);
                    st.put((byte) cur[5]).put((byte) cur[6]).put((byte) cur[7]);
                    sock.send(new DatagramPacket(st.array(), 21, addr, port));
                    System.arraycopy(cur, 0, last, 0, 8);
                    lastSend = now;
                }
                if (now - lastPing >= 500) {
                    pg.clear();
                    pg.put((byte) 'T').put((byte) 'P').put((byte) 1).put((byte) 2);
                    pg.putInt(pingSeq++).putLong(System.nanoTime());
                    sock.send(new DatagramPacket(pg.array(), 16, addr, port));
                    lastPing = now;
                }
            } catch (Exception e) {
                // network hiccup; keep trying
            }
            if (now - lastStatus >= 500) {
                lastStatus = now;
                if (now - lastPong > 2000) sink.onStatus("电脑无响应");
                else if (rtt >= 0) sink.onStatus(rtt + " ms");
            }
        }
        sock.close();
    }

    private void receive() {
        byte[] buf = new byte[64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                sock.receive(p);
                if (p.getLength() >= 16 && buf[0] == 'T' && buf[1] == 'P' && buf[3] == 3) {
                    long t = ByteBuffer.wrap(buf, 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    rtt = (int) ((System.nanoTime() - t) / 1_000_000L);
                    lastPong = SystemClock.uptimeMillis();
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }
}

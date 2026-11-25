package com.touchpad.ffx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {

    static final String ACTION_STOP = "com.touchpad.ffx.STOP";
    static final String ACTION_RELOAD = "com.touchpad.ffx.RELOAD";
    static volatile boolean running;

    private WindowManager wm;
    private PadView pad;
    private TextView bubble;
    private Net net;
    private boolean padShown, bubbleShown;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pad != null && intent != null && ACTION_RELOAD.equals(intent.getAction())) teardown();
        if (pad == null) start();
        return START_NOT_STICKY;
    }

    private void teardown() {
        if (net != null) net.shutdown();
        net = null;
        try { if (padShown) wm.removeView(pad); } catch (Exception ignored) { }
        try { if (bubbleShown) wm.removeView(bubble); } catch (Exception ignored) { }
        padShown = bubbleShown = false;
        pad = null;
    }

    private void start() {
        startInForeground();
        running = true;
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        String host = sp.getString("host", "");
        int port = sp.getInt("port", 39500);
        float scale = sp.getInt("scale", 100) / 100f;
        float alpha = sp.getInt("alpha", 75) / 100f;
        int profile = sp.getInt("profile", PadView.PROFILE_FFX);
        float camSens = sp.getInt("camSens", 100) / 100f;
        boolean invertY = sp.getBoolean("camInvertY", false);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        boolean floatingJoy = sp.getBoolean("floatingJoy", false);
        pad = new PadView(this, profile, scale, alpha, camSens, invertY, floatingJoy) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                boolean r = super.onTouchEvent(e);
                if (net != null) net.poke();
                return r;
            }
        };
        pad.setListener(this::hidePad);

        bubble = new TextView(this);
        bubble.setText("手柄");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x993B6EF5);
        bg.setStroke(2, 0xCCFFFFFF);
        bubble.setBackground(bg);
        bubble.setOnClickListener(v -> showPad());

        showPad();
        net = new Net(host, port, pad, pad::setStatus);
        net.start();
    }

    private WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private void showPad() {
        if (bubbleShown) { wm.removeView(bubble); bubbleShown = false; }
        if (!padShown) {
            WindowManager.LayoutParams lp = baseParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(pad, lp);
            padShown = true;
        }
    }

    private void hidePad() {
        pad.releaseAll();
        if (net != null) net.poke();
        if (padShown) { wm.removeView(pad); padShown = false; }
        if (!bubbleShown) {
            int size = Math.round(48 * getResources().getDisplayMetrics().density);
            WindowManager.LayoutParams lp = baseParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.round(12 * getResources().getDisplayMetrics().density);
            lp.y = lp.x;
            wm.addView(bubble, lp);
            bubbleShown = true;
        }
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pad", "触屏手柄", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "pad")
                .setSmallIcon(R.drawable.ic_pad)
                .setContentTitle("触屏手柄运行中")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "关闭", pi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        running = false;
        teardown();
        super.onDestroy();
    }
}

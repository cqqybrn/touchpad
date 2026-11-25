package com.touchpad.ffx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Full-screen transparent touch layer, laid out like miHoYo mobile games:
 * floating joystick on the left, a big action button with a small fan bottom-right,
 * system buttons in the top corners, and a collapsible "more" grid on the right.
 * Each finger keeps the role it got at touch-down until it lifts.
 */
public class PadView extends View {

    // XUSB button bits
    static final int UP = 0x0001, DOWN = 0x0002, LEFT = 0x0004, RIGHT = 0x0008;
    static final int START = 0x0010, BACK = 0x0020, LS = 0x0040, RS = 0x0080, LB = 0x0100, RB = 0x0200;
    static final int A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
    static final int LT_FLAG = 1 << 16, RT_FLAG = 1 << 17; // internal, mapped to triggers

    static final int PROFILE_FFX = 0, PROFILE_HSR = 1;

    static final int KIND_PAD = 0, KIND_KEY = 1, KIND_HIDE = 2, KIND_MORE = 3;
    static final int IC_TEXT = 0, IC_CHECK = 1, IC_CROSS = 2, IC_MENU = 3, IC_FAST = 4, IC_PLAY = 5, IC_MORE = 6;

    static class Btn {
        final String label;
        final int icon, kind, mask, accent;
        boolean more;       // belongs to the collapsible grid
        boolean bar;        // drawn as a pill (hide handle)
        float cx, cy, r, bw, bh;
        int pressed;

        Btn(String label, int icon, int kind, int mask, int accent) {
            this.label = label; this.icon = icon; this.kind = kind; this.mask = mask; this.accent = accent;
        }
    }

    static final int ROLE_NONE = 0, ROLE_BTN = 1, ROLE_JOY = 2, ROLE_SWIPE = 3;

    static class Finger {
        int role;
        Btn btn;
        float lx, ly, ax, ay, moved;
        long downAt;
        boolean pulsed;
    }

    public interface Listener { void onHide(); }

    private final float dp;
    private final float u;
    private final float alpha;
    private final int profile;
    private final boolean cameraMode;
    private final int tapMask;
    private final float camFullSpeed; // px/s for full stick deflection
    private final boolean camInvertY;
    private final boolean floatingJoy;
    private Listener listener;
    private final Vibrator vibrator;

    private final ArrayList<Btn> btns = new ArrayList<>();
    private final ArrayList<Btn> moreBtns = new ArrayList<>();
    private Btn mainBtn;
    private final ArrayList<Btn> fan = new ArrayList<>();
    private Btn topLeft, hideBtn, moreBtn;
    private final ArrayList<Btn> topRight = new ArrayList<>();
    private boolean moreOpen;
    private final SparseArray<Finger> fingers = new SparseArray<>();

    // joystick
    private float joyR, knobR, joyIdleX, joyIdleY;
    private float joyCx, joyCy, joyKx, joyKy;
    private boolean joyActive;
    private float stickX, stickY; // -1..1, y up positive

    // camera (right stick from finger speed)
    private boolean camActive;
    private float camAccX, camAccY, camVx, camVy;
    private long camLastNs;

    // pulses (d-pad steps, tap-confirm)
    private final ArrayDeque<int[]> pulses = new ArrayDeque<>(); // {mask, durationMs}
    private int pulseMask;
    private long pulseEnd, gapEnd;

    private volatile String status = "";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public PadView(Context c, int profile, float scale, float alpha, float camSens, boolean camInvertY,
                   boolean floatingJoy) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        u = dp * scale;
        this.alpha = alpha;
        this.profile = profile;
        this.camInvertY = camInvertY;
        this.floatingJoy = floatingJoy;
        camFullSpeed = 720 * dp / Math.max(0.2f, camSens);
        vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);

        stroke.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        hideBtn = add(new Btn("", IC_TEXT, KIND_HIDE, 0, 0));
        hideBtn.bar = true;
        moreBtn = add(new Btn("", IC_MORE, KIND_MORE, 0, 0));

        if (profile == PROFILE_HSR) {
            cameraMode = true;
            tapMask = 0;
            // Star Rail default Xbox mapping: A confirm/interact, B cancel, X basic ATK, Y technique, Start phone
            mainBtn = add(new Btn("普攻", IC_TEXT, KIND_PAD, X, 0));
            fan.add(add(new Btn("", IC_CHECK, KIND_PAD, A, 0)));      // left: confirm / interact
            fan.add(add(new Btn("", IC_CROSS, KIND_PAD, B, 0)));      // upper-left: cancel
            fan.add(add(new Btn("秘技", IC_TEXT, KIND_PAD, Y, 0)));    // above
            topLeft = add(new Btn("", IC_MENU, KIND_PAD, START, 0));  // phone
            topRight.add(add(new Btn("轮盘", IC_TEXT, KIND_PAD, LB, 0)));
            moreItem("选择", KIND_PAD, BACK); moreItem("RB", KIND_PAD, RB);
            moreItem("LT", KIND_PAD, LT_FLAG); moreItem("RT", KIND_PAD, RT_FLAG);
            moreItem("L3", KIND_PAD, LS); moreItem("R3", KIND_PAD, RS);
        } else {
            cameraMode = false;
            tapMask = B;
            // this FFX build: B confirms, A cancels, X opens the menu
            mainBtn = add(new Btn("", IC_CHECK, KIND_PAD, B, 0));
            fan.add(add(new Btn("", IC_CROSS, KIND_PAD, A, 0)));
            topLeft = add(new Btn("", IC_MENU, KIND_PAD, X, 0));
            topRight.add(add(new Btn("", IC_FAST, KIND_KEY, 1, 0)));     // F1 speed-up
            topRight.add(add(new Btn("", IC_PLAY, KIND_PAD, START, 0)));
            moreItem("LB", KIND_PAD, LB); moreItem("RB", KIND_PAD, RB);
            moreItem("LT", KIND_PAD, LT_FLAG); moreItem("RT", KIND_PAD, RT_FLAG);
            moreItem("Y", KIND_PAD, Y); moreItem("选择", KIND_PAD, BACK);
            moreItem("F2", KIND_KEY, 1 << 1); moreItem("F3", KIND_KEY, 1 << 2);
            moreItem("F4", KIND_KEY, 1 << 3); moreItem("F5", KIND_KEY, 1 << 4);
        }
    }

    private Btn add(Btn b) { btns.add(b); return b; }

    private void moreItem(String label, int kind, int mask) {
        Btn b = add(new Btn(label, IC_TEXT, kind, mask, 0));
        b.more = true;
        moreBtns.add(b);
    }

    public void setListener(Listener l) { listener = l; }

    public void setStatus(String s) {
        if (!s.equals(status)) { status = s; postInvalidate(); }
    }

    private boolean visible(Btn b) { return !b.more || moreOpen; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float top = 38 * u;

        // top corners
        set(topLeft, 44 * u, top, 24 * u);
        float x = w - 44 * u;
        for (int i = topRight.size() - 1; i >= 0; i--) { set(topRight.get(i), x, top, 24 * u); x -= 60 * u; }

        // hide handle, top centre
        hideBtn.cx = w / 2f; hideBtn.cy = 16 * u; hideBtn.bw = 84 * u; hideBtn.bh = 10 * u; hideBtn.r = 26 * u;

        // action cluster bottom-right
        float ax = w - 104 * u, ay = h - 92 * u;
        set(mainBtn, ax, ay, 50 * u);
        if (fan.size() == 1) {
            set(fan.get(0), ax - 108 * u, ay - 38 * u, 34 * u);              // upper-left of main
        } else {
            float rr = 112 * u;
            double[] ang = {190, 140, 92};                                   // left, upper-left, above
            for (int i = 0; i < fan.size(); i++) {
                double a = Math.toRadians(ang[i]);
                set(fan.get(i), ax + (float) Math.cos(a) * rr, ay - (float) Math.sin(a) * rr, 32 * u);
            }
        }

        // "more" toggle + grid on the right middle (like the party column)
        set(moreBtn, w - 44 * u, top + 64 * u, 20 * u);
        int cols = 3;
        float gx = w - 44 * u, gy = top + 116 * u, step = 46 * u;
        for (int i = 0; i < moreBtns.size(); i++) {
            int row = i / cols, col = i % cols;
            set(moreBtns.get(i), gx - col * step, gy + row * step, 19 * u);
        }

        joyR = 64 * u;
        knobR = 28 * u;
        joyIdleX = 150 * u;
        joyIdleY = h - 130 * u;
    }

    private void set(Btn b, float cx, float cy, float r) { b.cx = cx; b.cy = cy; b.r = r; }

    // ------------------------------------------------------------------ touch

    private Btn hit(float x, float y) {
        Btn best = null;
        float bestD = Float.MAX_VALUE;
        for (Btn b : btns) {
            if (!visible(b)) continue;
            float dx = x - b.cx, dy = y - b.cy, d = dx * dx + dy * dy;
            float slop = b.r + 8 * u;
            if (d <= slop * slop && d < bestD) { best = b; bestD = d; }
        }
        return best;
    }

    private boolean roleTaken(int role) {
        for (int i = 0; i < fingers.size(); i++) if (fingers.valueAt(i).role == role) return true;
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int i = e.getActionIndex();
                    down(e.getPointerId(i), e.getX(i), e.getY(i), now);
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int i = e.getActionIndex();
                    up(e.getPointerId(i), now, false);
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseAllLocked();
                    break;
            }
        }
        invalidate();
        return true;
    }

    private void down(int id, float x, float y, long now) {
        Finger f = new Finger();
        f.lx = x; f.ly = y; f.downAt = now;
        Btn b = hit(x, y);
        int w = getWidth();
        if (b != null) {
            f.role = ROLE_BTN; f.btn = b;
            b.pressed++;
            if (b.kind == KIND_MORE) moreOpen = !moreOpen;
            buzz();
        } else if (x < w * 0.45f && y > 70 * u && !roleTaken(ROLE_JOY)
                && (floatingJoy || Math.hypot(x - joyIdleX, y - joyIdleY) <= joyR * 2.2f)) {
            f.role = ROLE_JOY;
            joyActive = true;
            if (floatingJoy) {
                joyCx = Math.max(joyR, Math.min(x, w * 0.45f));
                joyCy = Math.max(joyR, Math.min(y, getHeight() - joyR * 0.6f));
            } else {
                joyCx = joyIdleX; joyCy = joyIdleY;   // fixed bottom-left
            }
            joyKx = x; joyKy = y;
            updateStick();
        } else if (x >= w * 0.45f && !roleTaken(ROLE_SWIPE)) {
            f.role = ROLE_SWIPE;
            if (cameraMode) { camActive = true; camAccX = camAccY = 0; }
        } else {
            f.role = ROLE_NONE;
        }
        fingers.put(id, f);
    }

    private void move(int id, float x, float y) {
        Finger f = fingers.get(id);
        if (f == null) return;
        float dx = x - f.lx, dy = y - f.ly;
        f.moved += Math.abs(dx) + Math.abs(dy);
        f.lx = x; f.ly = y;
        if (f.role == ROLE_JOY) {
            joyKx = x; joyKy = y;
            float vx = x - joyCx, vy = y - joyCy, len = (float) Math.hypot(vx, vy);
            if (floatingJoy && len > joyR) {        // floating: base follows the finger past the rim
                joyCx = x - vx / len * joyR;
                joyCy = y - vy / len * joyR;
            }
            updateStick();
        } else if (f.role == ROLE_SWIPE) {
            if (cameraMode) {
                camAccX += dx; camAccY += dy;
                return;
            }
            f.ax += dx; f.ay += dy;
            float step = 36 * u;
            if (Math.abs(f.ay) >= Math.abs(f.ax)) {
                if (Math.abs(f.ay) >= step) {
                    pulse(f.ay < 0 ? UP : DOWN, 55);
                    f.ay -= Math.signum(f.ay) * step; f.ax *= 0.5f; f.pulsed = true;
                    buzz();
                }
            } else if (Math.abs(f.ax) >= step) {
                pulse(f.ax < 0 ? LEFT : RIGHT, 55);
                f.ax -= Math.signum(f.ax) * step; f.ay *= 0.5f; f.pulsed = true;
                buzz();
            }
        }
    }

    private void up(int id, long now, boolean cancel) {
        Finger f = fingers.get(id);
        if (f == null) return;
        fingers.remove(id);
        if (f.role == ROLE_BTN) {
            f.btn.pressed = Math.max(0, f.btn.pressed - 1);
            if (f.btn.kind == KIND_HIDE && !cancel && listener != null) post(listener::onHide);
        } else if (f.role == ROLE_JOY) {
            joyActive = false;
            stickX = stickY = 0;
        } else if (f.role == ROLE_SWIPE) {
            if (cameraMode) {
                camActive = false;
                camAccX = camAccY = camVx = camVy = 0;
            } else if (!cancel && tapMask != 0 && !f.pulsed && f.moved < 14 * u && now - f.downAt < 300) {
                pulse(tapMask, 70);
                buzz();
            }
        }
    }

    private void updateStick() {
        float vx = (joyKx - joyCx) / joyR, vy = (joyKy - joyCy) / joyR;
        float mag = (float) Math.hypot(vx, vy);
        if (mag < 0.12f) { stickX = stickY = 0; return; }
        float m = Math.min(1f, (mag - 0.12f) / 0.88f) / mag;
        stickX = vx * m;
        stickY = -vy * m;
    }

    private void pulse(int mask, int ms) {
        if (pulses.size() < 8) pulses.add(new int[]{mask, ms});
    }

    public void releaseAll() {
        synchronized (this) { releaseAllLocked(); }
        postInvalidate();
    }

    private void releaseAllLocked() {
        fingers.clear();
        for (Btn b : btns) b.pressed = 0;
        joyActive = false;
        stickX = stickY = 0;
        camActive = false;
        camAccX = camAccY = camVx = camVy = 0;
        pulses.clear();
        pulseMask = 0;
    }

    private void buzz() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            else vibrator.vibrate(VibrationEffect.createOneShot(10, 60));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ state

    /** Finger speed (px/s) -> stick value, with an outer boost so small swipes beat the game's deadzone. */
    private float camShape(float v) {
        float m = Math.abs(v) / camFullSpeed;
        if (m < 0.015f) return 0;
        m = Math.min(1f, m);
        return Math.signum(v) * (0.16f + 0.84f * m);
    }

    /** out: [buttons, lx, ly, rx, ry, lt, rt, keys] */
    public synchronized void snapshot(int[] out) {
        long now = SystemClock.uptimeMillis();
        if (pulseMask != 0 && now >= pulseEnd) { pulseMask = 0; gapEnd = now + 45; }
        if (pulseMask == 0 && now >= gapEnd && !pulses.isEmpty()) {
            int[] p = pulses.poll();
            pulseMask = p[0];
            pulseEnd = now + p[1];
        }

        long ns = System.nanoTime();
        float dt = (ns - camLastNs) / 1e9f;
        camLastNs = ns;
        float rx = 0, ry = 0;
        if (camActive) {
            if (dt <= 0.001f || dt > 0.1f) dt = 0.008f;
            float k = Math.min(1f, dt / 0.025f);          // ~25 ms smoothing
            camVx += (camAccX / dt - camVx) * k;
            camVy += (camAccY / dt - camVy) * k;
            camAccX = camAccY = 0;
            rx = camShape(camVx);
            ry = camShape(camVy * 0.75f) * (camInvertY ? 1 : -1);
        }

        int buttons = pulseMask, keys = 0;
        for (Btn b : btns) {
            if (b.pressed <= 0) continue;
            if (b.kind == KIND_PAD) buttons |= b.mask;
            else if (b.kind == KIND_KEY) keys |= b.mask;
        }
        out[0] = buttons & 0xFFFF;
        out[1] = Math.round(stickX * 32767);
        out[2] = Math.round(stickY * 32767);
        out[3] = Math.round(rx * 32767);
        out[4] = Math.round(ry * 32767);
        out[5] = (buttons & LT_FLAG) != 0 ? 255 : 0;
        out[6] = (buttons & RT_FLAG) != 0 ? 255 : 0;
        out[7] = keys;
    }

    // ------------------------------------------------------------------ draw

    private int a(int v) { return Math.round(v * alpha); }

    @Override
    protected void onDraw(Canvas c) {
        // joystick
        float jx = joyActive ? joyCx : joyIdleX, jy = joyActive ? joyCy : joyIdleY;
        fill.setColor(Color.argb(a(joyActive ? 70 : 40), 0, 0, 0));
        c.drawCircle(jx, jy, joyR, fill);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(Color.argb(a(joyActive ? 200 : 120), 255, 255, 255));
        c.drawCircle(jx, jy, joyR, stroke);
        float kx = jx, ky = jy;
        if (joyActive) {
            float vx = joyKx - joyCx, vy = joyKy - joyCy, len = (float) Math.hypot(vx, vy);
            float lim = Math.min(len, joyR);
            if (len > 0) { kx = joyCx + vx / len * lim; ky = joyCy + vy / len * lim; }
        }
        fill.setColor(Color.argb(a(joyActive ? 230 : 150), 255, 255, 255));
        c.drawCircle(kx, ky, knobR, fill);

        for (Btn b : btns) {
            if (!visible(b)) continue;
            if (b.bar) { drawBar(c, b); continue; }
            drawButton(c, b);
        }

        String s = status;
        if (!s.isEmpty()) {
            text.setTextSize(11 * dp);
            text.setColor(Color.argb(a(220), 255, 255, 255));
            c.drawText(s, getWidth() / 2f, 44 * u, text);
        }
    }

    private void drawBar(Canvas c, Btn b) {
        boolean p = b.pressed > 0;
        rect.set(b.cx - b.bw / 2, b.cy - b.bh / 2, b.cx + b.bw / 2, b.cy + b.bh / 2);
        fill.setColor(Color.argb(a(p ? 240 : 150), 255, 255, 255));
        c.drawRoundRect(rect, b.bh / 2, b.bh / 2, fill);
    }

    private void drawButton(Canvas c, Btn b) {
        boolean p = b.pressed > 0 || (b.kind == KIND_MORE && moreOpen);
        float r = p ? b.r * 0.93f : b.r;
        fill.setColor(p ? Color.argb(a(150), 255, 255, 255) : Color.argb(a(90), 0, 0, 0));
        c.drawCircle(b.cx, b.cy, r, fill);
        stroke.setStrokeWidth((b == mainBtn ? 2.2f : 1.5f) * dp);
        stroke.setColor(Color.argb(a(p ? 255 : 190), 255, 255, 255));
        c.drawCircle(b.cx, b.cy, r, stroke);

        int fg = p ? Color.argb(a(255), 30, 30, 30) : Color.argb(a(255), 255, 255, 255);
        if (b.icon == IC_TEXT) {
            int col = (b.accent != 0 && !p) ? b.accent : fg;
            text.setColor(Color.argb(a(255), Color.red(col), Color.green(col), Color.blue(col)));
            float ts = b.label.length() <= 1 ? r * 0.8f : r * (b.label.length() == 2 ? 0.62f : 0.5f);
            text.setTextSize(ts);
            c.drawText(b.label, b.cx, b.cy + ts * 0.36f, text);
            return;
        }
        icon.setColor(fg);
        icon.setStrokeWidth(Math.max(2 * dp, r * 0.11f));
        float s = r * 0.42f, x = b.cx, y = b.cy;
        path.reset();
        switch (b.icon) {
            case IC_CHECK:
                path.moveTo(x - s, y + s * 0.05f);
                path.lineTo(x - s * 0.3f, y + s * 0.7f);
                path.lineTo(x + s, y - s * 0.6f);
                c.drawPath(path, icon);
                break;
            case IC_CROSS:
                s *= 0.8f;
                c.drawLine(x - s, y - s, x + s, y + s, icon);
                c.drawLine(x + s, y - s, x - s, y + s, icon);
                break;
            case IC_MENU:
                for (int i = -1; i <= 1; i++) c.drawLine(x - s, y + i * s * 0.6f, x + s, y + i * s * 0.6f, icon);
                break;
            case IC_FAST:
                icon.setStyle(Paint.Style.FILL);
                path.moveTo(x - s, y - s * 0.7f); path.lineTo(x, y); path.lineTo(x - s, y + s * 0.7f); path.close();
                path.moveTo(x, y - s * 0.7f); path.lineTo(x + s, y); path.lineTo(x, y + s * 0.7f); path.close();
                c.drawPath(path, icon);
                icon.setStyle(Paint.Style.STROKE);
                break;
            case IC_PLAY:
                icon.setStyle(Paint.Style.FILL);
                path.moveTo(x - s * 0.55f, y - s * 0.8f); path.lineTo(x + s * 0.8f, y); path.lineTo(x - s * 0.55f, y + s * 0.8f); path.close();
                c.drawPath(path, icon);
                icon.setStyle(Paint.Style.STROKE);
                break;
            case IC_MORE:
                icon.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(x + i * s * 0.75f, y, Math.max(1.6f * dp, r * 0.1f), icon);
                icon.setStyle(Paint.Style.STROKE);
                break;
        }
    }
}

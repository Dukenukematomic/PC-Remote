package com.etzify.pcremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The touch surface. Translates finger gestures into pointer commands:
 *
 *   one finger drag        move the pointer (with acceleration)
 *   one finger tap         left click
 *   two finger tap         right click
 *   two finger drag        scroll
 *   double tap then hold   press and hold left, so you can drag things
 */
public class TrackpadView extends View {

    private static final int TAP_TIMEOUT_MS = 220;
    private static final int DOUBLE_TAP_WINDOW_MS = 280;
    private static final float TAP_SLOP_DP = 10f;
    private static final float SCROLL_NOTCH_DP = 22f;

    private static final float ACCEL_PER_DP = 0.11f;
    private static final float ACCEL_MAX = 3.2f;

    /** Both are overwritten from saved settings; these are the defaults. */
    private float sensitivity = 1.9f;
    private float scrollSpeed = 1.0f;

    private enum Mode {IDLE, POINT, SCROLL, DRAG}

    private RemoteClient client;
    private float density;

    private Mode mode = Mode.IDLE;
    private float lastX, lastY;
    private float downX, downY;
    private long downTime;
    private float travelled;
    private int maxPointers;

    private float scrollAccum;
    private float lastScrollY;

    private long lastTapUpTime;
    private float lastTapX, lastTapY;
    private boolean dragArmed;

    private boolean touching;
    private float touchX, touchY;
    private boolean transparent;
    private int cardTop;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public TrackpadView(Context c) {
        super(c);
        init();
    }

    public TrackpadView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        fill.setColor(Color.parseColor("#1B1D26"));
        fill.setStyle(Paint.Style.FILL);

        border.setColor(Color.parseColor("#33FFFFFF"));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(1.5f));
        border.setPathEffect(new DashPathEffect(
                new float[]{dp(7f), dp(6f)}, 0f));

        hint.setColor(Color.parseColor("#59FFFFFF"));
        hint.setTextSize(dp(13f));
        hint.setTextAlign(Paint.Align.CENTER);

        glow.setColor(Color.parseColor("#4D6C7BFF"));
        glow.setStyle(Paint.Style.FILL);

        setClickable(true);
    }

    public void setClient(RemoteClient client) {
        this.client = client;
    }

    /** Pointer gain, 0.5x (slow and precise) to 4.0x (flick across the screen). */
    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** Scroll gain; higher means fewer millimetres of finger per wheel notch. */
    public void setScrollSpeed(float scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    /**
     * Stops painting the card so the screen view behind stays visible. The
     * gestures are unchanged; only the backdrop goes away.
     */
    public void setTransparent(boolean transparent) {
        this.transparent = transparent;
        invalidate();
    }

    /**
     * Where the picture above ends, in pixels down from the top.
     *
     * A 16:9 desktop cannot fill a tall phone, so whatever is left under it
     * gets the trackpad card drawn on it. The whole view still takes touches
     * either way -- this only decides how much of it looks like a trackpad.
     */
    public void setCardTop(int top) {
        if (cardTop == top) return;
        cardTop = top;
        invalidate();
    }

    private float dp(float v) {
        return v * density;
    }

    // -- drawing -----------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = dp(1f);
        float radius = dp(18f);
        // With a picture above, the card covers only what is left below it.
        float top = transparent ? cardTop + dp(10f) : inset;
        float bottom = getHeight() - inset;
        boolean showCard = bottom - top > dp(40f);

        if (showCard) {
            bounds.set(inset, top, getWidth() - inset, bottom);
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, border);
        }

        if (touching) {
            canvas.drawCircle(touchX, touchY, dp(26f), glow);
        } else if (showCard) {
            float cy = (top + bottom) / 2f;
            if (bottom - top > dp(80f)) {
                canvas.drawText("Slide to move the pointer",
                        getWidth() / 2f, cy - dp(10f), hint);
                canvas.drawText("Tap to click  ·  Two fingers to scroll",
                        getWidth() / 2f, cy + dp(14f), hint);
            } else {
                canvas.drawText("Slide to move  ·  Tap to click",
                        getWidth() / 2f, cy + dp(4f), hint);
            }
        }
    }

    // -- touch handling ----------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (client == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onFirstDown(event);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                onExtraFingerDown(event);
                break;

            case MotionEvent.ACTION_MOVE:
                onMove(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                onFingerUp(event);
                break;

            case MotionEvent.ACTION_UP:
                onLastUp(event);
                break;

            case MotionEvent.ACTION_CANCEL:
                endDragIfNeeded();
                reset();
                break;

            default:
                return true;
        }

        invalidate();
        return true;
    }

    private void onFirstDown(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);

        lastX = downX = event.getX();
        lastY = downY = event.getY();
        downTime = System.currentTimeMillis();
        travelled = 0f;
        maxPointers = 1;
        mode = Mode.POINT;
        touching = true;
        touchX = lastX;
        touchY = lastY;

        // A second tap landing in the same spot means "grab and drag".
        boolean quick = downTime - lastTapUpTime < DOUBLE_TAP_WINDOW_MS;
        boolean nearby = Math.hypot(downX - lastTapX, downY - lastTapY)
                < dp(TAP_SLOP_DP) * 2.5f;
        dragArmed = quick && nearby;
        if (dragArmed) {
            mode = Mode.DRAG;
            client.buttonDown("left");
        }
    }

    private void onExtraFingerDown(MotionEvent event) {
        maxPointers = Math.max(maxPointers, event.getPointerCount());
        if (event.getPointerCount() == 2 && mode != Mode.DRAG) {
            mode = Mode.SCROLL;
            scrollAccum = 0f;
            lastScrollY = averageY(event);
        }
    }

    private void onMove(MotionEvent event) {
        touchX = event.getX();
        touchY = event.getY();

        if (mode == Mode.SCROLL && event.getPointerCount() >= 2) {
            float y = averageY(event);
            scrollAccum += y - lastScrollY;
            lastScrollY = y;

            float notch = dp(SCROLL_NOTCH_DP) / scrollSpeed;
            while (scrollAccum >= notch) {
                client.scroll(0, -1);
                scrollAccum -= notch;
            }
            while (scrollAccum <= -notch) {
                client.scroll(0, 1);
                scrollAccum += notch;
            }
            return;
        }

        if (mode != Mode.POINT && mode != Mode.DRAG) return;

        float dx = event.getX() - lastX;
        float dy = event.getY() - lastY;
        lastX = event.getX();
        lastY = event.getY();
        travelled += (float) Math.hypot(dx, dy);

        // Work in dp so the same swipe feels identical on any screen density.
        float ddx = dx / density;
        float ddy = dy / density;
        float magnitude = (float) Math.hypot(ddx, ddy);
        float accel = Math.min(1f + magnitude * ACCEL_PER_DP, ACCEL_MAX);
        float gain = sensitivity * accel;

        client.move(ddx * gain, ddy * gain);
    }

    private void onFingerUp(MotionEvent event) {
        // Hand the pointer over to whichever finger is still down, so lifting
        // one of two fingers does not jump the cursor.
        int leavingIndex = event.getActionIndex();
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == leavingIndex) continue;
            lastX = event.getX(i);
            lastY = event.getY(i);
            break;
        }
    }

    private void onLastUp(MotionEvent event) {
        long held = System.currentTimeMillis() - downTime;
        boolean stationary = travelled < dp(TAP_SLOP_DP);
        boolean quick = held < TAP_TIMEOUT_MS;

        if (mode == Mode.DRAG) {
            client.buttonUp("left");
        } else if (quick && stationary && maxPointers == 1) {
            client.click("left");
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            lastTapUpTime = System.currentTimeMillis();
            lastTapX = downX;
            lastTapY = downY;
        } else if (quick && stationary && maxPointers == 2) {
            client.click("right");
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }

        reset();
    }

    private void endDragIfNeeded() {
        if (mode == Mode.DRAG) client.buttonUp("left");
    }

    private void reset() {
        mode = Mode.IDLE;
        touching = false;
        maxPointers = 0;
        dragArmed = false;
    }

    private float averageY(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / event.getPointerCount();
    }
}

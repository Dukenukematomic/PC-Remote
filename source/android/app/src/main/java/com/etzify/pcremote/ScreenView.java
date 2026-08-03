package com.etzify.pcremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the PC's screen, letterboxed to keep its shape.
 *
 * The trackpad sits on top of this as a transparent gesture layer, so the same
 * swipe that moves the pointer is happening over a picture of the pointer.
 */
public class ScreenView extends View {

    public interface FrameRecycler {
        /** The previous frame is finished with and can be reused. */
        void release(Bitmap frame);
    }

    private Bitmap frame;
    private FrameRecycler recycler;
    private String status = "";
    /** Width over height of what we are showing; 16:9 until a frame lands. */
    private float aspect = 16f / 9f;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private float density;

    public ScreenView(Context c) {
        super(c);
        init();
    }

    public ScreenView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        backdrop.setColor(Color.parseColor("#0A0B10"));
        backdrop.setStyle(Paint.Style.FILL);
        statusPaint.setColor(Color.parseColor("#8A8FA3"));
        statusPaint.setTextSize(13f * density);
        statusPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRecycler(FrameRecycler recycler) {
        this.recycler = recycler;
    }

    public void setStatus(String text) {
        status = text == null ? "" : text;
        invalidate();
    }

    /**
     * Takes only the height the picture actually needs.
     *
     * Filling the space and letterboxing inside it left broad black bands
     * above and below a 16:9 desktop on a tall phone, and pushed the buttons
     * off the bottom. Measuring to the picture's own shape gives that space
     * back to the controls.
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int heightMode = MeasureSpec.getMode(heightSpec);
        int heightAvailable = MeasureSpec.getSize(heightSpec);

        int height = aspect > 0 ? Math.round(width / aspect) : heightAvailable;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightAvailable;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(height, heightAvailable);
        }
        setMeasuredDimension(width, Math.max(1, height));
    }

    /** Shows a new frame and returns the old one to the pool. */
    public void setFrame(Bitmap next) {
        Bitmap previous = frame;
        frame = next;
        status = "";

        if (next != null && next.getHeight() > 0) {
            float next_aspect = next.getWidth() / (float) next.getHeight();
            // Only a real change is worth a re-layout; every frame would be.
            if (Math.abs(next_aspect - aspect) > 0.01f) {
                aspect = next_aspect;
                requestLayout();
            }
        }
        invalidate();
        // Safe to reuse now: the swap happened on the same thread that draws,
        // so nothing is still reading the old bitmap.
        if (previous != null && previous != next) {
            if (recycler != null) {
                recycler.release(previous);
            } else {
                previous.recycle();
            }
        }
    }

    public void clear() {
        Bitmap previous = frame;
        frame = null;
        invalidate();
        if (previous != null) {
            if (recycler != null) {
                recycler.release(previous);
            } else {
                previous.recycle();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = 18f * density;
        target.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(target, radius, radius, backdrop);

        if (frame != null && !frame.isRecycled()) {
            float scale = Math.min(getWidth() / (float) frame.getWidth(),
                    getHeight() / (float) frame.getHeight());
            float w = frame.getWidth() * scale;
            float h = frame.getHeight() * scale;
            float left = (getWidth() - w) / 2f;
            float top = (getHeight() - h) / 2f;

            source.set(0, 0, frame.getWidth(), frame.getHeight());
            target.set(left, top, left + w, top + h);
            canvas.drawBitmap(frame, source, target, bitmapPaint);
        } else if (!status.isEmpty()) {
            canvas.drawText(status, getWidth() / 2f,
                    getHeight() / 2f + 5f * density, statusPaint);
        }
    }
}

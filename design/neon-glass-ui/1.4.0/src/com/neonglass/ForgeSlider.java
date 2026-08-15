package com.neonglass;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Compact touch slider drawn in the active Neon Glass palette. */
@SuppressLint("ViewConstructor") // Created only from code because its numeric range is mandatory.
public final class ForgeSlider extends View {
    interface ValueListener { void onValueChanged(int value); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int min;
    private final int max;
    private final int step;
    private int value;
    private int primary = Color.rgb(50, 224, 255);
    private int secondary = Color.rgb(167, 255, 49);
    private LinearGradient fillGradient;
    private ValueListener listener;

    public ForgeSlider(Context context, int min, int max, int step) {
        super(context);
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.value = min;
        setClickable(true);
        setFocusable(true);
    }

    public void setListener(ValueListener listener) { this.listener = listener; }

    public void setPalette(int primary, int secondary) {
        this.primary = primary;
        this.secondary = secondary;
        rebuildGradient();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuildGradient();
    }

    private void rebuildGradient() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            fillGradient = null;
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float left = 8f * density;
        float right = getWidth() - 8f * density;
        float cy = getHeight() * 0.5f;
        fillGradient = new LinearGradient(left, cy, right, cy,
                primary, secondary, Shader.TileMode.CLAMP);
    }

    public void setValue(int value, boolean notify) {
        int next = snap(value);
        if (this.value == next) return;
        this.value = next;
        invalidate();
        if (notify && listener != null) listener.onValueChanged(next);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float left = 8f * density;
        float right = getWidth() - 8f * density;
        float cy = getHeight() * 0.5f;
        float trackHeight = 8f * density;
        rect.set(left, cy - trackHeight * 0.5f, right, cy + trackHeight * 0.5f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(76, 199, 217, 235));
        canvas.drawRoundRect(rect, trackHeight, trackHeight, paint);

        float fraction = (value - min) / (float) (max - min);
        float knobX = left + (right - left) * fraction;
        if (knobX > left) {
            rect.right = knobX;
            paint.setShader(fillGradient);
            canvas.drawRoundRect(rect, trackHeight, trackHeight, paint);
            paint.setShader(null);
        }

        paint.setColor(Color.argb(48, Color.red(primary), Color.green(primary), Color.blue(primary)));
        canvas.drawCircle(knobX, cy, 15f * density, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(knobX, cy, 6.5f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(Color.argb(210, Color.red(secondary), Color.green(secondary), Color.blue(secondary)));
        canvas.drawCircle(knobX, cy, 9f * density, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                updateFromTouch(event.getX());
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateFromTouch(float x) {
        float density = getResources().getDisplayMetrics().density;
        float left = 8f * density;
        float right = Math.max(left + 1f, getWidth() - 8f * density);
        float fraction = Math.max(0f, Math.min(1f, (x - left) / (right - left)));
        setValue(Math.round(min + (max - min) * fraction), true);
    }

    private int snap(int raw) {
        int constrained = clamp(raw, min, max);
        return clamp(min + Math.round((constrained - min) / (float) step) * step, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.neonglass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;

/** A light-weight, code-drawn backdrop that gives Liquid Neon its living depth. */
public final class LiquidBackdropView extends View {
    private static final int TRAIL_COUNT = 7;
    /** How often the panel is asked what rate it is running at, in milliseconds. */
    private static final long REFRESH_SAMPLE_MS = 1000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF edgeRect = new RectF();
    private final float[] trailX = new float[TRAIL_COUNT];
    private final float[] trailY = new float[TRAIL_COUNT];
    private final float density;

    private int primary = Color.rgb(50, 224, 255);
    private int secondary = Color.rgb(167, 255, 49);
    private boolean liquidEnabled;
    private boolean motionEnabled = true;
    private boolean trailInitialized;
    private float pointerX = -1f;
    private float pointerY = -1f;
    private float targetPointerX = -1f;
    private float targetPointerY = -1f;
    private float pointerStrength;
    private float targetTouchSpeed;
    private float touchSpeed;
    private float edgeEnergy;
    private float inputX = -1f;
    private float inputY = -1f;
    private long inputAt;
    private long lastFrameAt;
    private float rippleX = -1f;
    private float rippleY = -1f;
    private long rippleStartedAt;
    private final int[] windowLocation = new int[2];
    private float refreshHz = DisplayRefreshRate.FALLBACK_HZ;
    private long refreshSampledAt;
    private RadialGradient primaryGlow;
    private RadialGradient secondaryGlow;
    private RadialGradient violetGlow;
    private RadialGradient pointerGlow;
    private RadialGradient trailGlow;
    private RadialGradient rippleGlow;
    private LinearGradient verticalShade;
    private LinearGradient edgeGlow;
    /** The glows and the shade as one shader, so the glass can sample what the backdrop draws. */
    private Shader composedBackdrop;

    public LiquidBackdropView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        rebuildShaders();
    }

    public void setPalette(int primary, int secondary) {
        this.primary = primary;
        this.secondary = secondary;
        rebuildShaders();
        invalidate();
    }

    public void setLiquidEnabled(boolean enabled) {
        liquidEnabled = enabled;
        setVisibility(enabled ? VISIBLE : GONE);
        lastFrameAt = 0L;
        requestFrameRate();
        if (enabled) {
            postInvalidateOnAnimation();
        } else {
            // Nothing is behind the glass any more, so it must stop refracting a stale scene.
            NeonGlassScene.current().publish(null);
        }
    }

    public void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        requestFrameRate();
        if (enabled && liquidEnabled) postInvalidateOnAnimation();
    }

    /** Moves the interaction light without starting a new refraction wave. */
    public void setPointer(float x, float y) {
        if (x < 0f || y < 0f) {
            releasePointer();
            return;
        }
        updatePointerTarget(x, y, false);
    }

    /** Starts a Liquid Neon 3.0 press wave at absolute view coordinates. */
    public void pressAt(float x, float y) {
        targetPointerX = x;
        targetPointerY = y;
        pointerX = x;
        pointerY = y;
        inputX = x;
        inputY = y;
        inputAt = SystemClock.uptimeMillis();
        targetTouchSpeed = 0f;
        touchSpeed = 0f;
        pointerStrength = 1f;
        edgeEnergy = Math.max(edgeEnergy, 0.82f);
        initializeTrail(x, y);
        rippleX = x;
        rippleY = y;
        rippleStartedAt = inputAt;
        if (liquidEnabled) postInvalidateOnAnimation();
    }

    /** Updates the light and derives trail length from the real touch velocity. */
    public void movePointer(float x, float y) {
        updatePointerTarget(x, y, true);
    }

    public void releasePointer() {
        targetPointerX = -1f;
        targetPointerY = -1f;
        targetTouchSpeed = 0f;
        inputAt = 0L;
        if (liquidEnabled) postInvalidateOnAnimation();
    }

    /** Optional intensity hook for future accessibility or battery-saving controls. */
    public void setInteractionIntensity(float intensity) {
        float safe = clamp(intensity, 0f, 1f);
        pointerStrength *= safe;
        edgeEnergy *= safe;
        if (liquidEnabled) postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sampleRefreshRate(SystemClock.uptimeMillis());
        requestFrameRate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NeonGlassScene.current().publish(null);
    }

    /** Reads the rate the display is drawing at right now, which an LTPO panel keeps changing. */
    private void sampleRefreshRate(long now) {
        refreshSampledAt = now;
        Display display = getDisplay();
        float sampled = DisplayRefreshRate.sanitize(
                display == null ? 0f : display.getRefreshRate());
        if (sampled == refreshHz) return;
        refreshHz = sampled;
        requestFrameRate();
    }

    /**
     * Asks the system to run at the rate this panel reported, while the backdrop is genuinely
     * in motion.
     *
     * <p>The rate is asked for as a number rather than as one of the platform's categories.
     * A category is not the panel's rate: this Pixel resolves the "high" category to 90Hz on
     * a panel that runs at 120, so a category would have quietly capped the very thing this
     * is here to deliver.
     *
     * <p>Asking only while moving matters too. Holding the panel at its top rate for a screen
     * that is not changing costs battery for nothing.
     */
    private void requestFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        setRequestedFrameRate(liquidEnabled && motionEnabled
                ? refreshHz
                : REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!liquidEnabled || getWidth() == 0 || getHeight() == 0) return;

        long now = SystemClock.uptimeMillis();
        // The panel can change mode while the app runs, so the rate is re-read as we draw.
        if (now - refreshSampledAt >= REFRESH_SAMPLE_MS) sampleRefreshRate(now);
        float frameSeconds = lastFrameAt == 0L
                ? DisplayRefreshRate.frameSeconds(refreshHz)
                : clamp((now - lastFrameAt) / 1000f,
                        DisplayRefreshRate.SHORTEST_FRAME_SECONDS,
                        DisplayRefreshRate.longestFrameSeconds(refreshHz));
        lastFrameAt = now;

        float w = getWidth();
        float h = getHeight();
        float t = motionEnabled ? (now % 18000L) / 18000f : 0.18f;
        double phase = t * Math.PI * 2.0;

        float x1 = w * (0.20f + 0.12f * (float) Math.sin(phase));
        float y1 = h * (0.22f + 0.08f * (float) Math.cos(phase * 0.83));
        drawGlow(canvas, x1, y1, Math.max(w, h) * 0.46f, primaryGlow, 255);

        float x2 = w * (0.82f + 0.10f * (float) Math.cos(phase * 0.71));
        float y2 = h * (0.70f + 0.11f * (float) Math.sin(phase * 0.91));
        drawGlow(canvas, x2, y2, Math.max(w, h) * 0.50f, secondaryGlow, 255);

        float x3 = w * (0.72f + 0.14f * (float) Math.sin(phase * 0.47 + 1.8));
        float y3 = h * (0.16f + 0.06f * (float) Math.cos(phase * 0.62));
        drawGlow(canvas, x3, y3, Math.max(w, h) * 0.28f, violetGlow, 255);

        // The three glows have just been positioned for this frame, so the composed shader the
        // glass samples now describes exactly the light behind it. Nothing is captured: the
        // glass evaluates these same gradient objects.
        publishScene();

        // The shade belongs to the atmosphere. Interactive light is composited above it so
        // taps retain the crisp, glassy specular peak that was lost in Liquid Neon 2.0.
        shaderMatrix.reset();
        shaderMatrix.setScale(1f, h);
        verticalShade.setLocalMatrix(shaderMatrix);
        paint.setShader(verticalShade);
        paint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, paint);

        boolean pointerAnimating = updatePointer(frameSeconds);
        boolean rippleAnimating = drawRefractionWave(canvas, now, w, h);

        if (pointerX >= 0f && pointerY >= 0f && pointerStrength > 0.003f) {
            updateTrail(frameSeconds);
            drawVelocityTrail(canvas, w, h);

            float pointerRadius = Math.max(w, h) * (0.155f + touchSpeed * 0.055f);
            drawGlow(canvas, pointerX, pointerY, pointerRadius, pointerGlow,
                    Math.round(255f * pointerStrength));

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((1.25f + touchSpeed * 1.15f) * density);
            paint.setColor(Color.argb(Math.round(145f * pointerStrength), 255, 255, 255));
            canvas.drawCircle(pointerX, pointerY, (8.5f + touchSpeed * 5f) * density, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        float edgeTarget = targetPointerX >= 0f
                ? 0.18f + touchSpeed * 0.62f : 0f;
        edgeEnergy = approach(edgeEnergy, edgeTarget,
                response(frameSeconds, targetPointerX >= 0f ? 9f : 3.6f));
        if (edgeEnergy > 0.004f) drawEdgeEnergy(canvas, w, h);

        paint.setAlpha(255);
        paint.setShader(null);
        if (motionEnabled || pointerAnimating || rippleAnimating || edgeEnergy > 0.004f) {
            postInvalidateOnAnimation();
        }
    }

    private void updatePointerTarget(float x, float y, boolean measureSpeed) {
        long now = SystemClock.uptimeMillis();
        if (measureSpeed && inputAt > 0L) {
            long elapsed = Math.max(1L, now - inputAt);
            float dx = x - inputX;
            float dy = y - inputY;
            float pixelsPerSecond = (float) Math.sqrt(dx * dx + dy * dy) * 1000f / elapsed;
            targetTouchSpeed = clamp(pixelsPerSecond / (2150f * density), 0f, 1f);
        }
        targetPointerX = x;
        targetPointerY = y;
        inputX = x;
        inputY = y;
        inputAt = now;
        pointerStrength = Math.max(pointerStrength, 0.72f);
        if (pointerX < 0f) {
            pointerX = x;
            pointerY = y;
            initializeTrail(x, y);
        }
        if (liquidEnabled) postInvalidateOnAnimation();
    }

    private boolean updatePointer(float frameSeconds) {
        targetTouchSpeed *= (float) Math.exp(-5.4f * frameSeconds);
        touchSpeed = approach(touchSpeed, targetTouchSpeed, response(frameSeconds, 11f));

        if (targetPointerX >= 0f && targetPointerY >= 0f) {
            if (pointerX < 0f) {
                pointerX = targetPointerX;
                pointerY = targetPointerY;
                initializeTrail(pointerX, pointerY);
            }
            float dx = targetPointerX - pointerX;
            float dy = targetPointerY - pointerY;
            float follow = response(frameSeconds, 15.5f + touchSpeed * 5f);
            pointerX += dx * follow;
            pointerY += dy * follow;
            pointerStrength = approach(pointerStrength, 1f, response(frameSeconds, 12f));
            return Math.abs(dx) + Math.abs(dy) > 0.35f || touchSpeed > 0.006f;
        }

        if (pointerStrength > 0.003f) {
            pointerStrength *= (float) Math.exp(-4.8f * frameSeconds);
            return true;
        }
        pointerStrength = 0f;
        pointerX = -1f;
        pointerY = -1f;
        trailInitialized = false;
        return false;
    }

    private void initializeTrail(float x, float y) {
        for (int i = 0; i < TRAIL_COUNT; i++) {
            trailX[i] = x;
            trailY[i] = y;
        }
        trailInitialized = true;
    }

    private void updateTrail(float frameSeconds) {
        if (!trailInitialized) initializeTrail(pointerX, pointerY);
        trailX[0] = pointerX;
        trailY[0] = pointerY;
        for (int i = 1; i < TRAIL_COUNT; i++) {
            // Later nodes deliberately react more slowly, producing a natural curved wake
            // without allocating Paths or particles on every high-refresh frame.
            float follow = response(frameSeconds, 13.5f - i * 1.15f);
            trailX[i] += (trailX[i - 1] - trailX[i]) * follow;
            trailY[i] += (trailY[i - 1] - trailY[i]) * follow;
        }
    }

    private void drawVelocityTrail(Canvas canvas, float w, float h) {
        float velocityEnergy = touchSpeed * pointerStrength;
        if (velocityEnergy < 0.012f) return;
        float baseRadius = Math.max(w, h) * (0.052f + touchSpeed * 0.034f);
        for (int i = TRAIL_COUNT - 1; i >= 1; i--) {
            float life = 1f - i / (float) TRAIL_COUNT;
            int alpha = Math.round(150f * velocityEnergy * life * life);
            if (alpha <= 0) continue;
            drawGlow(canvas, trailX[i], trailY[i], baseRadius * (0.68f + life * 0.56f),
                    trailGlow, alpha);
        }
    }

    private boolean drawRefractionWave(Canvas canvas, long now, float w, float h) {
        if (rippleStartedAt <= 0L) return false;
        float progress = (now - rippleStartedAt) / 780f;
        if (progress >= 1f) {
            rippleStartedAt = 0L;
            return false;
        }

        float eased = 1f - (float) Math.pow(1f - progress, 3.0);
        float radius = (18f + 150f * eased) * density;
        drawGlow(canvas, rippleX, rippleY, radius, rippleGlow,
                Math.round(190f * (1f - progress)));

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float ringProgress = progress - i * 0.105f;
            if (ringProgress <= 0f || ringProgress >= 1f) continue;
            float ringEase = 1f - (float) Math.pow(1f - ringProgress, 2.7);
            float shimmer = 0.86f + 0.14f
                    * (float) Math.sin(ringProgress * Math.PI * 5.0 + i * 0.8);
            paint.setStrokeWidth((2.8f - ringProgress * 1.65f) * density);
            paint.setColor(Color.argb(Math.round(170f * (1f - ringProgress) * shimmer),
                    255, 255, 255));
            canvas.drawCircle(rippleX, rippleY,
                    (13f + (112f + i * 24f) * ringEase) * density, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        return true;
    }

    private void drawEdgeEnergy(Canvas canvas, float w, float h) {
        shaderMatrix.reset();
        shaderMatrix.setScale(w, 1f);
        edgeGlow.setLocalMatrix(shaderMatrix);
        paint.setShader(edgeGlow);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((0.8f + edgeEnergy * 1.8f) * density);
        paint.setAlpha(Math.round(205f * edgeEnergy));
        float inset = (1.5f + edgeEnergy) * density;
        edgeRect.set(inset, inset, w - inset, h - inset);
        canvas.drawRoundRect(edgeRect, 25f * density, 25f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawGlow(Canvas canvas, float x, float y, float radius,
                          RadialGradient shader, int alpha) {
        if (alpha <= 0 || radius <= 0f) return;
        shaderMatrix.reset();
        shaderMatrix.setScale(radius, radius);
        shaderMatrix.postTranslate(x, y);
        shader.setLocalMatrix(shaderMatrix);
        paint.setShader(shader);
        paint.setAlpha(Math.min(255, alpha));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setAlpha(255);
    }

    private void rebuildShaders() {
        primaryGlow = unitGlow(primary, 88);
        secondaryGlow = unitGlow(secondary, 66);
        violetGlow = unitGlow(Color.rgb(155, 92, 255), 50);
        pointerGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(60, 255, 255, 255),
                        Color.argb(24, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.29f, 1f}, Shader.TileMode.CLAMP);
        trailGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(55, 255, 255, 255),
                        Color.argb(35, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.34f, 1f}, Shader.TileMode.CLAMP);
        rippleGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.TRANSPARENT,
                        Color.argb(24, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(72, 255, 255, 255),
                        Color.argb(18, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.52f, 0.68f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        verticalShade = new LinearGradient(0f, 0f, 0f, 1f,
                new int[]{Color.argb(20, 255, 255, 255), Color.TRANSPARENT,
                        Color.argb(72, 0, 0, 12)},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        // The three glows and the shade, in the order they are painted, as one shader the glass
        // can evaluate. Composing the gradient objects themselves — rather than describing them
        // to the glass — is what keeps the refraction from drifting away from what is drawn.
        Shader glows = new ComposeShader(primaryGlow, secondaryGlow, PorterDuff.Mode.SRC_OVER);
        glows = new ComposeShader(glows, violetGlow, PorterDuff.Mode.SRC_OVER);
        composedBackdrop = new ComposeShader(glows, verticalShade, PorterDuff.Mode.SRC_OVER);
        edgeGlow = new LinearGradient(0f, 0f, 1f, 0f,
                new int[]{Color.TRANSPARENT,
                        Color.argb(205, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(215, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.24f, 0.76f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Offers this frame's ambient light to {@link NeonGlassScene} for the glass to sample. */
    private void publishScene() {
        NeonGlassScene scene = NeonGlassScene.current();
        getLocationInWindow(windowLocation);
        scene.setOrigin(windowLocation[0], windowLocation[1]);
        scene.publish(composedBackdrop);
        // The gradients move by their own local matrices, which the scene cannot see, so the
        // frame itself is the signal that the light behind the glass has moved on.
        if (motionEnabled) scene.markMoved();
    }

    private RadialGradient unitGlow(int color, int alpha) {
        return new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(alpha / 3, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.34f, 1f}, Shader.TileMode.CLAMP);
    }

    private static float response(float seconds, float speed) {
        return 1f - (float) Math.exp(-speed * seconds);
    }

    private static float approach(float current, float target, float amount) {
        return current + (target - current) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

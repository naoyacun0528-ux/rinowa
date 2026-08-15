package com.neonglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The XP bar as a channel cut into glass, with the level standing in it as light.
 *
 * <p>A progress bar is usually two rectangles: a dark one and a coloured one on top. That reads
 * as a diagram. Here the empty part is a groove — dark at its upper lip where a cut edge would
 * be in shadow, bright along its lower one where light pools — and the filled part is not laid
 * over the groove but sits inside it, carrying its own highlight along the top the way anything
 * with a rounded surface does.
 *
 * <p>The head of the level glows. That is where the eye goes, and it is the one part of the bar
 * that means something on its own: this is how far you have come, and it is still moving.
 *
 * <p>Drawn directly rather than assembled from layers because the head has to know where the
 * level ends, and a ClipDrawable does not tell what it clips.
 */
public final class NeonGlassGauge extends Drawable {
    /** ProgressBar speaks in ten-thousandths. */
    private static final float LEVEL_SPAN = 10000f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF fill = new RectF();
    private final int primary;
    private final int secondary;

    private LinearGradient levelGradient;
    private LinearGradient crownGradient;
    private RadialGradient headGlow;
    private int shaderWidth = -1;
    private int shaderHeight = -1;

    public NeonGlassGauge(int primary, int secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect box = getBounds();
        if (box.width() <= 0 || box.height() <= 0) return;
        bounds.set(box);
        bounds.inset(0.75f, 0.75f);
        float radius = bounds.height() * 0.5f;
        ensureShaders(box.width(), box.height());

        // The groove itself belongs to the pane underneath: this bar stands on NEON GLASS
        // rather than imitating it. What is left here is only the part a base cannot draw —
        // the light standing in the channel, and where that light currently ends.
        //
        // The channel is barely tinted. Anything heavier reads as a black rod lying on the
        // glass, and its edge then draws a second ring inside the pane's own. At this weight
        // the pane shows through the empty part of the bar and the two become one object.
        float remaining = Math.max(0f, Math.min(1f, getLevel() / LEVEL_SPAN));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Color.argb(70, 1, 4, 12));
        canvas.drawRoundRect(bounds, radius, radius, paint);

        float level = remaining;
        if (level <= 0.0005f) return;

        // The level stands in the groove. Its width never falls below its own round end, so a
        // few points of XP still read as a bead of light rather than a sliver.
        float head = bounds.left + Math.max(bounds.height(), bounds.width() * level);
        fill.set(bounds.left, bounds.top, Math.min(head, bounds.right), bounds.bottom);
        int save = canvas.save();
        canvas.clipRect(fill);

        paint.setShader(levelGradient);
        canvas.drawRoundRect(bounds, radius, radius, paint);

        // Its own crown: a bright line along the upper half, which is what a rounded surface
        // does with a light above it, and what keeps the level from reading as flat paint.
        paint.setShader(crownGradient);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);

        // The head. Where the eye goes, and the only part of the bar that says "still moving".
        if (head < bounds.right - 0.5f) {
            float glow = bounds.height() * 2.1f;
            paint.setShader(headGlow);
            canvas.save();
            canvas.translate(head, bounds.centerY());
            canvas.scale(glow, glow);
            canvas.drawRect(-1f, -1f, 1f, 1f, paint);
            canvas.restore();
            paint.setShader(null);
        }
    }

    private void ensureShaders(int width, int height) {
        if (levelGradient != null && width == shaderWidth && height == shaderHeight) return;
        shaderWidth = width;
        shaderHeight = height;

        levelGradient = new LinearGradient(0f, 0f, width, 0f,
                new int[]{primary, blend(primary, secondary, 0.5f), secondary},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        crownGradient = new LinearGradient(0f, 0f, 0f, height,
                new int[]{Color.argb(205, 255, 255, 255), Color.argb(60, 255, 255, 255),
                        Color.TRANSPARENT, Color.argb(92, 0, 3, 14)},
                new float[]{0f, 0.3f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        headGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(210, 255, 255, 255),
                        Color.argb(120, Color.red(secondary), Color.green(secondary),
                                Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        levelGradient = null;
        shaderWidth = -1;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

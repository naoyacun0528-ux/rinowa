package com.neonglass;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;

/** Layered translucent surface with a colored edge and a soft specular highlight. */
public final class LiquidGlassDrawable extends Drawable {
    /** Below this the pane is smaller than its own bevel would need, so it stays painted. */
    private static final float MIN_PANE_PX = 72f;
    /** Key light from the upper left, matching the crown highlight this class already draws. */
    private static final float LIGHT_X = -0.55f;
    private static final float LIGHT_Y = -0.835f;
    /** How often the panel is asked what rate it is running at, in milliseconds. */
    private static final long REFRESH_SAMPLE_MS = 1000L;
    /** The reach is still capped against the pane, so an edge cannot sample half the screen. */
    private static final float REACH_LIMIT = 0.5f;
    /**
     * How often a pane repaints purely to follow the drifting backdrop, in milliseconds.
     * The glows take eighteen seconds to cross the screen, so twenty times a second tracks
     * them to well under a pixel while leaving the panel's real rate to the game.
     */
    private static final long SCENE_FOLLOW_MS = 50L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF baseRect = new RectF();
    private final RectF rect = new RectF();
    private final RectF innerRect = new RectF();
    private final Path highlight = new Path();
    private final Path interactionClip = new Path();
    private final int primary;
    private final int secondary;
    private final float radius;
    private final float visualScale;
    private final boolean strong;
    private final Matrix interactionMatrix = new Matrix();

    private int alpha = 255;
    private final boolean gameSurface;
    private LinearGradient fillGradient;
    private LinearGradient borderGradient;
    private LinearGradient innerBorderGradient;
    /**
     * The face of the pane, lit.
     *
     * <p>Refraction gives the rim its light, and a rim alone reads as an outline on a dark
     * screen: the glass bends the scene but never catches anything itself. A wide diagonal sheen
     * and a soft bloom towards the light source are what an actual sheet of glass does with a
     * room, and they are painted rather than computed because the face is flat — there is no
     * normal to reflect off, only the fact that the pane is there.
     */
    private LinearGradient surfaceSheen;
    private RadialGradient reflectionBloom;
    /** Kept between frames: the crown only changes span when the pane resizes or is pressed. */
    private LinearGradient crownGradient;
    private float crownGradientLeft = Float.NaN;
    private float crownGradientRight = Float.NaN;
    private int crownGradientTint;
    private RadialGradient interactionGlow;
    private RadialGradient trailGlow;
    private RadialGradient refractionBand;
    private RadialGradient edgeEnergyGlow;

    private float interactionX = -1f;
    private float interactionY = -1f;
    private float renderedX = -1f;
    private float renderedY = -1f;
    private float lastInputX = -1f;
    private float lastInputY = -1f;
    private float velocityUnitX;
    private float velocityUnitY;
    private float targetTouchSpeed;
    private float touchSpeed;
    private float pressFraction;
    private float glowEnergy;
    private float edgeEnergy;
    private long rippleStartedAt;
    private long lastInputAt;
    private long lastDrawAt;
    private boolean pressed;
    private int shaderLeft = Integer.MIN_VALUE;
    private int shaderTop;
    private int shaderRight;
    private int shaderBottom;
    private final int[] hostLocation = new int[2];
    private final Matrix paneMatrix = new Matrix();
    private final Runnable followScene = this::invalidateSelf;
    private NeonGlassShader refraction;
    private boolean refractionUnavailable;
    private long drawnSceneRevision = Long.MIN_VALUE;
    private long shaderTuningRevision = Long.MIN_VALUE;
    private float refreshHz = DisplayRefreshRate.FALLBACK_HZ;
    private long refreshSampledAt;

    public LiquidGlassDrawable(int primary, int secondary, float radius, boolean strong) {
        this(primary, secondary, radius, strong, false);
    }

    /**
     * @param gameSurface whether this pane belongs to a round in progress, in which case the
     *                    studio's game scale rides on top of everything it paints. A reaction
     *                    game is not the place to be looking at the glass, and the person
     *                    playing should get to say how much of it stays.
     */
    public LiquidGlassDrawable(int primary, int secondary, float radius, boolean strong,
                        boolean gameSurface) {
        this.primary = primary;
        this.secondary = secondary;
        this.radius = radius;
        this.strong = strong;
        this.gameSurface = gameSurface;
        visualScale = Math.max(1f, Math.min(2.6f, radius / 24f));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth((strong ? 1.45f : 1.05f) * visualScale);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        rebuildInteractionShaders();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        baseRect.set(bounds);
        baseRect.inset(1.5f, 1.5f);
        if (baseRect.isEmpty()) return;

        long now = SystemClock.uptimeMillis();
        if (now - refreshSampledAt >= REFRESH_SAMPLE_MS) sampleRefreshRate(now);
        float frameSeconds = lastDrawAt == 0L
                ? DisplayRefreshRate.frameSeconds(refreshHz)
                : clamp((now - lastDrawAt) / 1000f,
                        DisplayRefreshRate.SHORTEST_FRAME_SECONDS,
                        DisplayRefreshRate.longestFrameSeconds(refreshHz));
        lastDrawAt = now;
        boolean animationNeeded = updateInteractionState(frameSeconds);

        // A pressed surface widens and becomes slightly shallower. The deformation is tiny
        // enough to preserve layout, but visible beside the matching View scale animation.
        rect.set(baseRect);
        rect.inset(-radius * 0.014f * pressFraction,
                radius * 0.042f * pressFraction);
        float liveRadius = radius * (1f + 0.075f * pressFraction);

        ensureShaders();
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        // Real glass first: the backdrop as this pane bends it. When that is not possible the
        // painted material stands on its own, which is what every build before 1.2.6 showed.
        // The bevel is drawn under the material rather than instead of it. The material is a
        // translucent wash and always has been: it is what lets the icon under the logo tile
        // and the blurred scene under a panel show through, and NEON GLASS is not NEON GLASS
        // without that. Refraction adds an edge to it; it does not replace it.
        boolean refracted = drawRefractedBackdrop(canvas, liveRadius, now);
        paint.setShader(fillGradient);
        canvas.drawRoundRect(rect, liveRadius, liveRadius, paint);

        // Light on the face, over the material and under the rim. Refraction gives the edge its
        // light and gives the face none, so a pane with a bright rim and a dark middle reads as
        // an outline rather than a sheet of glass. This is the part that answers "it looks dark".
        drawAmbientReflection(canvas, liveRadius, refracted);

        stroke.setShader(borderGradient);
        stroke.setStrokeWidth((strong ? 1.45f : 1.05f) * visualScale);
        stroke.setAlpha(255);
        canvas.drawRoundRect(rect, liveRadius, liveRadius, stroke);

        // A second, fainter rim just inside the first. The bevel below it is a soft gradient by
        // nature, and a soft edge on a dark screen reads as a smudge; this line gives the pane a
        // definite boundary without adding any opacity over the text it surrounds.
        float innerInset = 1.65f * visualScale;
        innerRect.set(rect);
        innerRect.inset(innerInset, innerInset);
        float innerRadius = Math.max(0f, liveRadius - innerInset);
        stroke.setShader(innerBorderGradient);
        stroke.setStrokeWidth((strong ? 0.82f : 0.62f) * visualScale);
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, stroke);

        boolean hasInteraction = renderedX >= 0f && renderedY >= 0f && glowEnergy > 0.004f;
        if (hasInteraction) {
            int save = canvas.save();
            interactionClip.reset();
            interactionClip.addRoundRect(rect, liveRadius, liveRadius, Path.Direction.CW);
            canvas.clipPath(interactionClip);

            float maxDimension = Math.max(rect.width(), rect.height());
            float glowRadius = Math.max(radius * 2.15f,
                    Math.min(rect.width(), rect.height()) * (0.80f + touchSpeed * 0.16f));
            float wakeDistance = Math.min(maxDimension * 0.15f,
                    radius * (0.45f + touchSpeed * 1.65f));
            if (touchSpeed > 0.018f) {
                drawUnitGlow(canvas,
                        renderedX - velocityUnitX * wakeDistance,
                        renderedY - velocityUnitY * wakeDistance,
                        glowRadius * (1f + touchSpeed * 0.45f), trailGlow,
                        Math.round(145f * glowEnergy * touchSpeed));
            }
            drawUnitGlow(canvas, renderedX, renderedY, glowRadius, interactionGlow,
                    Math.round((pressed ? 225f : 145f) * glowEnergy));

            animationNeeded |= drawRefraction(canvas, now, maxDimension);
            canvas.restoreToCount(save);
        } else if (rippleStartedAt > 0L) {
            // Keep the wave alive for its full release animation even after the highlight fades.
            int save = canvas.save();
            interactionClip.reset();
            interactionClip.addRoundRect(rect, liveRadius, liveRadius, Path.Direction.CW);
            canvas.clipPath(interactionClip);
            animationNeeded |= drawRefraction(canvas, now, Math.max(rect.width(), rect.height()));
            canvas.restoreToCount(save);
        }

        if (edgeEnergy > 0.004f && renderedX >= 0f) {
            float edgeRadius = Math.max(rect.width(), rect.height()) * 0.72f;
            interactionMatrix.reset();
            interactionMatrix.setScale(edgeRadius, edgeRadius);
            interactionMatrix.postTranslate(renderedX, renderedY);
            edgeEnergyGlow.setLocalMatrix(interactionMatrix);
            stroke.setShader(edgeEnergyGlow);
            stroke.setAlpha(Math.round(235f * edgeEnergy));
            stroke.setStrokeWidth((0.9f + 1.8f * edgeEnergy) * visualScale);
            canvas.drawRoundRect(rect, liveRadius, liveRadius, stroke);
            stroke.setAlpha(255);
        }

        // The crown highlight is light catching the top of the glass. The bevel produces most of
        // it once refraction is real, so the crown is held back there rather than dropped: the
        // top of a pane should not be darker than its sides. What made it read as a scratch was
        // never that it was drawn twice — it was that the line stopped dead at both ends.
        drawCrownHighlight(liveRadius, canvas, refracted);

        paint.setAlpha(255);
        paint.setShader(null);
        stroke.setAlpha(255);
        stroke.setShader(null);
        if (animationNeeded || rippleStartedAt > 0L) invalidateSelf();
    }

    /**
     * A wide reflection across the face, and a brighter shoulder towards the light.
     *
     * <p>Painted with SCREEN so it adds light rather than laying white over what is underneath:
     * the backdrop keeps its colour and the pane gets brighter, which is the difference between
     * glass catching a room and paint sitting on glass. Both parts stay quiet through the middle
     * where text lives, and both are on their own dial — the whole effect can be turned off.
     */
    private void drawAmbientReflection(Canvas canvas, float liveRadius, boolean refracted) {
        if (surfaceSheen == null || reflectionBloom == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setBlendMode(BlendMode.SCREEN);

        paint.setShader(surfaceSheen);
        paint.setAlpha(multiplyAlpha(refracted ? 245 : 215));
        canvas.drawRoundRect(rect, liveRadius, liveRadius, paint);

        // The bloom sits towards the light and follows a touch, because that is where a
        // reflection moves when the sheet is tilted. Stretching a unit radial rather than
        // drawing a circle keeps it an ellipse the shape of the pane, not a pointer glow.
        float bloomX = rect.centerX() + lightUnitX() * rect.width() * 0.38f;
        float bloomY = rect.centerY() + lightUnitY() * rect.height() * 0.42f;
        float bloomWidth = Math.max(rect.width() * 0.58f, radius * 3.2f);
        float bloomHeight = Math.max(rect.height() * 0.72f, radius * 2.4f);
        interactionMatrix.reset();
        interactionMatrix.setScale(bloomWidth, bloomHeight);
        interactionMatrix.postTranslate(bloomX, bloomY);
        reflectionBloom.setLocalMatrix(interactionMatrix);
        paint.setShader(reflectionBloom);
        paint.setAlpha(multiplyAlpha(strong ? 238 : 198));
        canvas.drawRoundRect(rect, liveRadius, liveRadius, paint);

        paint.setAlpha(255);
        paint.setShader(null);
        paint.setBlendMode(null);
    }

    /**
     * The painted crown: a curved gleam along the top edge, bending with the press morph.
     *
     * <p>It is drawn whether or not refraction succeeded, but quieter when it did. The bevel
     * produces most of this line by itself once it is real, and dropping the crown entirely
     * there leaves the top of the pane darker than the sides — the rim light has to join
     * something. Held back to about three quarters, it joins the broad reflection to a crisp
     * edge instead.
     *
     * <p>It fades out at both ends rather than stopping. The path runs between two points inside
     * the corners, and on a tall pane the curve has enough drop that the ends read as the gleam
     * turning away from the light. On a flat one — a gauge, a strip — the same path is very
     * nearly a straight line, and a straight line that stops dead is not a highlight. It is a
     * scratch.
     */
    private void drawCrownHighlight(float liveRadius, Canvas canvas, boolean refracted) {
        highlight.reset();
        float crownDrop = radius * 0.055f * pressFraction;
        float crownLeft = rect.left + liveRadius * 0.64f;
        float crownRight = rect.right - liveRadius * 0.70f;
        highlight.moveTo(crownLeft, rect.top + 3.2f + crownDrop);
        highlight.cubicTo(rect.left + rect.width() * 0.32f, rect.top + 0.8f + crownDrop,
                rect.left + rect.width() * (0.60f + 0.018f * pressFraction),
                rect.top + 1.5f + crownDrop,
                crownRight,
                rect.top + liveRadius * (0.18f + 0.025f * pressFraction));
        int tint = Color.argb(sheenAlpha(refracted
                ? (strong ? 132 : 88)
                : (strong ? 175 : 120)), 255, 255, 255);
        if (crownGradient == null || crownLeft != crownGradientLeft
                || crownRight != crownGradientRight || tint != crownGradientTint) {
            crownGradient = new LinearGradient(crownLeft, 0f, crownRight, 0f,
                    new int[]{Color.TRANSPARENT, tint, tint, Color.TRANSPARENT},
                    new float[]{0f, 0.17f, 0.83f, 1f}, Shader.TileMode.CLAMP);
            crownGradientLeft = crownLeft;
            crownGradientRight = crownRight;
            crownGradientTint = tint;
        }
        stroke.setShader(crownGradient);
        stroke.setAlpha(255);
        stroke.setStrokeWidth((strong ? 1.65f : 1.25f) * visualScale);
        canvas.drawPath(highlight, stroke);
    }

    /**
     * Supplies local drawable coordinates. Repeated down=true calls derive speed and direction;
     * a down transition starts a refraction wave while release animates the glass back to rest.
     */
    public void setInteraction(float x, float y, boolean down) {
        long now = SystemClock.uptimeMillis();
        if (down && pressed && lastInputAt > 0L) {
            long elapsed = Math.max(1L, now - lastInputAt);
            float dx = x - lastInputX;
            float dy = y - lastInputY;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance > 0.05f) {
                velocityUnitX = dx / distance;
                velocityUnitY = dy / distance;
                float pixelsPerSecond = distance * 1000f / elapsed;
                targetTouchSpeed = clamp(pixelsPerSecond /
                        Math.max(950f, radius * 28f), 0f, 1f);
            }
        }

        interactionX = x;
        interactionY = y;
        lastInputX = x;
        lastInputY = y;
        lastInputAt = down ? now : 0L;
        if (renderedX < 0f) {
            renderedX = x;
            renderedY = y;
        }
        if (down && !pressed) {
            rippleStartedAt = now;
            glowEnergy = Math.max(glowEnergy, 0.82f);
            edgeEnergy = Math.max(edgeEnergy, 0.72f);
            targetTouchSpeed = 0f;
            touchSpeed = 0f;
        }
        pressed = down;
        if (!down) targetTouchSpeed = 0f;
        invalidateSelf();
    }

    /** Optional velocity hook, expressed in local pixels per second. */
    public void setInteractionVelocity(float velocityX, float velocityY) {
        float magnitude = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY);
        if (magnitude > 0.05f) {
            velocityUnitX = velocityX / magnitude;
            velocityUnitY = velocityY / magnitude;
            targetTouchSpeed = clamp(magnitude / Math.max(950f, radius * 28f), 0f, 1f);
            invalidateSelf();
        }
    }

    private boolean updateInteractionState(float frameSeconds) {
        float pressTarget = pressed ? 1f : 0f;
        float glowTarget = pressed ? 1f : 0f;
        float edgeTarget = pressed ? 0.32f + touchSpeed * 0.68f : 0f;

        pressFraction = approach(pressFraction, pressTarget,
                response(frameSeconds, pressed ? 21f : 8.2f));
        glowEnergy = approach(glowEnergy, glowTarget,
                response(frameSeconds, pressed ? 16f : 4.9f));
        targetTouchSpeed *= (float) Math.exp(-5.2f * frameSeconds);
        touchSpeed = approach(touchSpeed, targetTouchSpeed, response(frameSeconds, 12f));
        edgeEnergy = approach(edgeEnergy, edgeTarget,
                response(frameSeconds, pressed ? 13f : 5.4f));

        if (interactionX >= 0f && interactionY >= 0f) {
            float follow = response(frameSeconds, 18f + touchSpeed * 7f);
            renderedX += (interactionX - renderedX) * follow;
            renderedY += (interactionY - renderedY) * follow;
        }

        boolean morphing = Math.abs(pressFraction - pressTarget) > 0.003f;
        boolean glowing = pressed || glowEnergy > 0.004f || edgeEnergy > 0.004f;
        boolean moving = touchSpeed > 0.005f;
        if (!glowing && !morphing && !moving) {
            glowEnergy = 0f;
            edgeEnergy = 0f;
            touchSpeed = 0f;
            renderedX = -1f;
            renderedY = -1f;
        }
        return morphing || glowing || moving;
    }

    private boolean drawRefraction(Canvas canvas, long now, float maxDimension) {
        if (rippleStartedAt <= 0L || interactionX < 0f) return false;
        float progress = (now - rippleStartedAt) / 620f;
        if (progress >= 1f) {
            rippleStartedAt = 0L;
            return false;
        }

        float eased = 1f - (float) Math.pow(1f - progress, 3.1);
        float waveRadius = 7f * visualScale + maxDimension * 0.72f * eased;
        interactionMatrix.reset();
        interactionMatrix.setScale(waveRadius, waveRadius);
        interactionMatrix.postTranslate(interactionX, interactionY);
        refractionBand.setLocalMatrix(interactionMatrix);
        paint.setShader(refractionBand);
        paint.setAlpha(multiplyAlpha(Math.round(205f * (1f - progress))));
        canvas.drawRect(rect, paint);

        stroke.setShader(null);
        stroke.setStrokeWidth((1.6f - 0.65f * progress) * visualScale);
        for (int i = 0; i < 2; i++) {
            float ringProgress = progress - i * 0.115f;
            if (ringProgress <= 0f || ringProgress >= 1f) continue;
            float ringEase = 1f - (float) Math.pow(1f - ringProgress, 2.6);
            float shimmer = 0.88f + 0.12f
                    * (float) Math.sin(ringProgress * Math.PI * 5.5 + i);
            stroke.setColor(Color.argb(multiplyAlpha(
                    Math.round(155f * (1f - ringProgress) * shimmer)), 255, 255, 255));
            canvas.drawCircle(interactionX, interactionY,
                    5f * visualScale + maxDimension * (0.55f + i * 0.10f) * ringEase, stroke);
        }
        paint.setAlpha(255);
        return true;
    }

    private void drawUnitGlow(Canvas canvas, float x, float y, float glowRadius,
                              RadialGradient shader, int requestedAlpha) {
        if (requestedAlpha <= 0) return;
        interactionMatrix.reset();
        interactionMatrix.setScale(glowRadius, glowRadius);
        interactionMatrix.postTranslate(x, y);
        shader.setLocalMatrix(interactionMatrix);
        paint.setShader(shader);
        paint.setAlpha(multiplyAlpha(Math.min(255, requestedAlpha)));
        canvas.drawRect(rect, paint);
        paint.setAlpha(255);
    }

    private int multiplyAlpha(int value) {
        return Math.round(value * (alpha / 255f));
    }

    /**
     * The alpha of the material's own wash, thinned by the density dial.
     *
     * <p>The wash is what stands between a pane and what it transmits. Taking the colour out of
     * it does not help — a white film is still a film. Painting less of it does, and the scene
     * behind then comes forward with its own colour rather than a pale substitute.
     */
    private int washAlpha(int value) {
        return Math.max(0, Math.min(255,
                Math.round(multiplyAlpha(value) * NeonGlassTuning.current().density())));
    }

    /** The sheen and the bloom, each on its own dial and both under the game scale. */
    private int sheenAlpha(int value) {
        return NeonGlassTuning.current().sheenAlpha(multiplyAlpha(value), gameSurface);
    }

    private int bloomAlpha(int value) {
        return NeonGlassTuning.current().bloomAlpha(multiplyAlpha(value), gameSurface);
    }

    /**
     * Where the light on this pane is coming from.
     *
     * <p>A fixed key light from the upper left when nothing is happening, swinging toward the
     * finger while the surface is being touched. A real pane of glass announces itself by the
     * way its edge catches the light as it moves, and a touch is the only thing that moves
     * here — so the rim brightens on the side the finger is on and fades as the touch decays.
     */
    private float lightUnitX() {
        return lightUnit(true);
    }

    private float lightUnitY() {
        return lightUnit(false);
    }

    private float lightUnit(boolean horizontal) {
        float baseX = LIGHT_X;
        float baseY = LIGHT_Y;
        if (renderedX >= 0f && renderedY >= 0f && glowEnergy > 0.01f) {
            float dx = renderedX - rect.centerX();
            float dy = renderedY - rect.centerY();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance > 1f) {
                float follow = Math.min(1f, glowEnergy);
                baseX = LIGHT_X * (1f - follow) + (dx / distance) * follow;
                baseY = LIGHT_Y * (1f - follow) + (dy / distance) * follow;
            }
        }
        float length = (float) Math.sqrt(baseX * baseX + baseY * baseY);
        if (length < 0.001f) return horizontal ? LIGHT_X : LIGHT_Y;
        return (horizontal ? baseX : baseY) / length;
    }

    /**
     * Reads the rate the panel is drawing at from the View this drawable is painted into.
     * The press and glow animations are timed in seconds, so the rate only decides how long
     * the very first frame of a press is assumed to be — which is exactly the frame the
     * player is looking at when they touch the screen.
     */
    private void sampleRefreshRate(long now) {
        refreshSampledAt = now;
        View host = hostView();
        if (host == null) return;
        Display display = host.getDisplay();
        refreshHz = DisplayRefreshRate.sanitize(display == null ? 0f : display.getRefreshRate());
    }

    /**
     * The View this drawable ends up painted into.
     *
     * <p>Not always the direct callback: a panel wraps its glass in a LayerDrawable, so the
     * callback is that layer and the View is one hop further up. Refraction needs the View to
     * ask where it is on screen, and a panel is exactly the surface most worth refracting.
     */
    private View hostView() {
        Callback callback = getCallback();
        for (int hops = 0; hops < 4 && callback instanceof Drawable; hops++) {
            callback = ((Drawable) callback).getCallback();
        }
        return callback instanceof View view ? view : null;
    }

    /**
     * Paints the pane as the backdrop seen through glass, bent along its bevel.
     *
     * <p>Returns false whenever the effect cannot be produced honestly — no AGSL, no hardware
     * canvas, no published scene, or a surface too small to read as a pane — so the caller can
     * paint the original material at full strength instead of a half-broken imitation.
     */
    private boolean drawRefractedBackdrop(Canvas canvas, float liveRadius, long now) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        if (refractionUnavailable || !canvas.isHardwareAccelerated()) return false;
        if (!GlassProfile.worthRefracting(rect.width(), rect.height(),
                Math.max(MIN_PANE_PX, radius * 1.6f))) {
            return false;
        }

        NeonGlassScene scene = NeonGlassScene.current();
        if (!scene.isPublished()) return false;

        // The backdrop drifts, and a pane that never repaints would keep refracting where the
        // light used to be. It drifts slowly though — the glows cross the screen over eighteen
        // seconds — so following it at the panel's full rate would repaint every pane in the
        // app a hundred times a second to move the refraction by a pixel. Following it a few
        // times a second is indistinguishable and leaves the frame budget to the game.
        // A moved dial counts as a moved scene: the pane has to repaint to show the new number.
        long revision = scene.revision() + NeonGlassTuning.current().revision();
        boolean sceneMoved = revision != drawnSceneRevision;
        drawnSceneRevision = revision;

        // The pane has to know where it sits over the backdrop, which only its host View knows.
        View host = hostView();
        if (host == null) return false;
        host.getLocationInWindow(hostLocation);

        if (refraction == null) {
            try {
                refraction = new NeonGlassShader();
            } catch (Throwable unsupported) {
                // A driver that will not compile the shader must not take the app with it.
                // Say so once: otherwise the effect simply stops appearing, and an effect that
                // silently went missing looks exactly like one that was never asked for.
                refractionUnavailable = true;
                Log.w("NEON_GLASS", "RuntimeShader unavailable; using the painted surface",
                        unsupported);
                return false;
            }
        }

        NeonGlassTuning tuning = NeonGlassTuning.current();
        float shorterSide = Math.min(rect.width(), rect.height());
        float bevel = GlassProfile.bevelWidth(shorterSide,
                Math.max(6f, radius * tuning.bevelRatio()));
        float reach = Math.min(bevel * tuning.reachRatio(strong), shorterSide * REACH_LIMIT);
        float originX = hostLocation[0] + rect.left - scene.originX();
        float originY = hostLocation[1] + rect.top - scene.originY();
        Shader pane = refraction.configure(rect.width(), rect.height(), originX, originY,
                liveRadius, bevel, reach, tuning.split(),
                tuning.edgeOpacity(strong), tuning.specular(strong), tuning.reflect(strong), reach,
                lightUnitX(), lightUnitY(), primary, strong ? 0.13f : 0.09f, scene);

        // The shader works from the pane's own top-left; the canvas draws in View coordinates.
        paneMatrix.reset();
        paneMatrix.setTranslate(rect.left, rect.top);
        pane.setLocalMatrix(paneMatrix);
        paint.setShader(pane);
        paint.setAlpha(multiplyAlpha(255));
        canvas.drawRoundRect(rect, liveRadius, liveRadius, paint);
        paint.setAlpha(255);
        paint.setShader(null);
        if (sceneMoved) {
            unscheduleSelf(followScene);
            scheduleSelf(followScene, now + SCENE_FOLLOW_MS);
        }
        return true;
    }

    private void ensureShaders() {
        int left = getBounds().left;
        int top = getBounds().top;
        int right = getBounds().right;
        int bottom = getBounds().bottom;
        long tuning = NeonGlassTuning.current().revision();
        if (fillGradient != null && left == shaderLeft && top == shaderTop
                && right == shaderRight && bottom == shaderBottom
                && tuning == shaderTuningRevision) return;
        shaderTuningRevision = tuning;
        shaderLeft = left;
        shaderTop = top;
        shaderRight = right;
        shaderBottom = bottom;
        fillGradient = new LinearGradient(baseRect.left, baseRect.top,
                baseRect.right, baseRect.bottom,
                new int[]{Color.argb(washAlpha(strong ? 88 : 58), 255, 255, 255),
                        Color.argb(washAlpha(strong ? 54 : 30),
                                Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(washAlpha(strong ? 116 : 126), 3, 8, 24)},
                new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP);
        // The sheen runs corner to corner rather than straight down, which is what separates a
        // reflection lying across a plane from a lamp painted behind it. It goes quiet through
        // the middle, where the text is.
        surfaceSheen = new LinearGradient(baseRect.left, baseRect.top,
                baseRect.right, baseRect.bottom,
                new int[]{Color.argb(sheenAlpha(strong ? 55 : 40), 255, 255, 255),
                        Color.argb(sheenAlpha(strong ? 20 : 14), 255, 255, 255),
                        Color.argb(sheenAlpha(strong ? 8 : 5), 255, 255, 255),
                        Color.TRANSPARENT,
                        Color.argb(sheenAlpha(strong ? 4 : 3), 255, 255, 255),
                        Color.TRANSPARENT},
                new float[]{0f, 0.16f, 0.31f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        // A unit circle, stretched over the pane at draw time so one gradient serves any shape.
        reflectionBloom = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(bloomAlpha(strong ? 62 : 46), 255, 255, 255),
                        Color.argb(bloomAlpha(strong ? 18 : 13), 255, 255, 255),
                        Color.argb(bloomAlpha(strong ? 6 : 4), 255, 255, 255),
                        Color.TRANSPARENT},
                new float[]{0f, 0.28f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        borderGradient = new LinearGradient(baseRect.left, baseRect.top,
                baseRect.right, baseRect.bottom,
                new int[]{Color.argb(multiplyAlpha(210), 255, 255, 255),
                        Color.argb(multiplyAlpha(145),
                                Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(multiplyAlpha(125),
                                Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.argb(multiplyAlpha(35), 255, 255, 255)},
                null, Shader.TileMode.CLAMP);
        innerBorderGradient = new LinearGradient(baseRect.left, baseRect.top,
                baseRect.right, baseRect.bottom,
                new int[]{Color.argb(multiplyAlpha(strong ? 115 : 82), 255, 255, 255),
                        Color.argb(multiplyAlpha(42),
                                Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(multiplyAlpha(18), 255, 255, 255)},
                new float[]{0f, 0.44f, 1f}, Shader.TileMode.CLAMP);
    }

    private void rebuildInteractionShaders() {
        interactionGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(104, 255, 255, 255),
                        Color.argb(34, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.27f, 1f}, Shader.TileMode.CLAMP);
        trailGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(72, 255, 255, 255),
                        Color.argb(31, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.32f, 1f}, Shader.TileMode.CLAMP);
        refractionBand = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.TRANSPARENT,
                        Color.argb(18, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(90, 255, 255, 255),
                        Color.argb(24, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.49f, 0.65f, 0.77f, 1f}, Shader.TileMode.CLAMP);
        edgeEnergyGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.WHITE,
                        Color.argb(215, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        Color.argb(82, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.26f, 0.64f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        fillGradient = null;
        borderGradient = null;
        innerBorderGradient = null;
        surfaceSheen = null;
        reflectionBloom = null;
        crownGradient = null;
        shaderLeft = Integer.MIN_VALUE;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        fillGradient = null;
        borderGradient = null;
        innerBorderGradient = null;
        surfaceSheen = null;
        reflectionBloom = null;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        stroke.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
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

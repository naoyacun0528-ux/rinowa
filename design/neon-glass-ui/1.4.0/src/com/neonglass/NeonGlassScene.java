package com.neonglass;

import android.graphics.Shader;

/**
 * What is behind the glass, handed over as the very shaders that drew it.
 *
 * <p>Refraction needs the colour behind every point it bends light from. The usual way to get
 * that on Android is to capture the view hierarchy into a bitmap, which costs a readback and an
 * upload every frame. This app never needs to: its backdrop is a flat colour with a few
 * gradients over it, and a gradient already knows its own colour at any coordinate.
 *
 * <p>So {@link LiquidBackdropView} composes the gradients it is about to draw into one shader
 * and leaves it here, and the glass evaluates that same shader at a displaced position. Nothing
 * is captured, and — because these are the same objects the backdrop draws with, not a copy of
 * their arithmetic — the glass cannot drift out of step with what is actually on screen.
 *
 * <p>The gradients are translucent, so what they return has to be composited over the flat
 * colour underneath them; that colour is kept here too.
 *
 * <p>One scene exists at a time because the app has one Activity and one backdrop. The values
 * are written and read on the main thread during drawing.
 */
public final class NeonGlassScene {
    private static final NeonGlassScene CURRENT = new NeonGlassScene();

    private Shader backdrop;
    private int baseColor;
    private float originX;
    private float originY;
    private float gridSpacing;
    private float gridOffset;
    private float gridWidth;
    private float gridBrightness;
    private long revision;

    /** Package-private rather than private so a test can hold a scene of its own. */
    NeonGlassScene() {
    }

    public static NeonGlassScene current() {
        return CURRENT;
    }

    /**
     * Hands over the composed gradients the backdrop draws with. Passing null withdraws the
     * scene, which is what stops the glass refracting a backdrop that is no longer on screen.
     */
    public void publish(Shader backdrop) {
        if (this.backdrop != backdrop) {
            this.backdrop = backdrop;
            revision++;
        }
    }

    /** The flat colour the gradients are drawn over, animated per game mode and theme. */
    public void setBaseColor(int color) {
        if (baseColor != color) {
            baseColor = color;
            revision++;
        }
    }

    /**
     * Where the backdrop sits in the window, so a pane elsewhere in the window can convert its
     * own position into the coordinates the gradients are drawn in.
     */
    public void setOrigin(float x, float y) {
        if (originX != x || originY != y) {
            originX = x;
            originY = y;
            revision++;
        }
    }

    /**
     * The playfield grid a round draws behind its panes, or nothing.
     *
     * <p>Kept apart from the backdrop shader because it belongs to the game view rather than to
     * the backdrop, and because it is the only thing on this screen with a hard edge. A pane
     * bending a straight line is the one moment the refraction can be seen rather than deduced.
     *
     * @param spacing   distance between lines in pixels, or 0 for no grid
     * @param brightness how much the line adds where it crosses
     */
    public void setGrid(float spacing, float offset, float width, float brightness) {
        if (gridSpacing == spacing && gridOffset == offset
                && gridWidth == width && gridBrightness == brightness) {
            return;
        }
        gridSpacing = spacing;
        gridOffset = offset;
        gridWidth = width;
        gridBrightness = brightness;
        revision++;
    }

    float gridSpacing() {
        return gridSpacing;
    }

    float gridOffset() {
        return gridOffset;
    }

    float gridWidth() {
        return gridWidth;
    }

    float gridBrightness() {
        return gridBrightness;
    }

    boolean isPublished() {
        return backdrop != null;
    }

    Shader backdrop() {
        return backdrop;
    }

    int baseColor() {
        return baseColor;
    }

    float originX() {
        return originX;
    }

    float originY() {
        return originY;
    }

    /**
     * Changes only when the scene actually moves, so a pane can tell whether its refraction has
     * gone stale. A still backdrop holds one value, which is what keeps the glass from
     * repainting itself while nothing is happening.
     *
     * <p>The gradients themselves move without changing this: they animate through their own
     * local matrices, and the backdrop reports that separately by republishing.
     */
    long revision() {
        return revision;
    }

    /** Called by the backdrop once per drawn frame while its gradients are in motion. */
    public void markMoved() {
        revision++;
    }
}

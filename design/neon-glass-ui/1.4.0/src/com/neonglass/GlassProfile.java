package com.neonglass;

/**
 * The shape of the glass edge, as pure maths.
 *
 * <p>A pane of glass bends what is behind it most at its rim and barely at all through its
 * middle, which is what makes it read as a solid object rather than a frosted sheet. The
 * bevel is described by a height profile over the band that runs inward from the edge; the
 * slope of that profile decides how far the background is displaced at each point.
 *
 * <p>The profile is a convex squircle, {@code y = (1 - (1 - x)^4)^(1/4)}. Compared with a
 * circular bevel it leaves the flat centre earlier and more smoothly, so the displacement
 * fades out instead of ending on a visible seam.
 *
 * <p>Kept free of Android types so the curve can be checked on the JVM: the shader that uses
 * it cannot be.
 */
final class GlassProfile {
    /** Refractive index of glass. Water is 1.33, glass roughly 1.5. */
    static final float GLASS_IOR = 1.5f;

    private GlassProfile() {
    }

    /**
     * Surface height across the bevel.
     *
     * @param depth 0 at the outer edge, 1 where the bevel meets the flat centre
     * @return 0 at the edge rising to 1 at the flat centre
     */
    static float height(float depth) {
        float d = clamp01(depth);
        float x = 1f - d;
        return (float) Math.pow(1.0 - Math.pow(x, 4.0), 0.25);
    }

    /**
     * Slope of the bevel, which is what actually bends the light.
     *
     * <p>Highest at the outer edge and zero once the surface is flat, so displacement is
     * concentrated in the rim and the centre of the pane stays undistorted.
     */
    static float slope(float depth) {
        float d = clamp01(depth);
        if (d >= 1f) return 0f;
        float delta = 0.002f;
        float lower = height(Math.max(0f, d - delta));
        float upper = height(Math.min(1f, d + delta));
        float span = Math.min(1f, d + delta) - Math.max(0f, d - delta);
        if (span <= 0f) return 0f;
        return (upper - lower) / span;
    }

    /**
     * How far the background is pushed at this point, as a fraction of the bevel width.
     *
     * @param depth     0 at the outer edge, 1 at the flat centre
     * @param strength  0 disables refraction, 1 is the full glass bend
     */
    static float displacement(float depth, float strength) {
        float bend = slope(depth) * (GLASS_IOR - 1f) * clamp01(strength);
        return Math.min(bend, 1f);
    }

    /**
     * Rim light along the bevel: brightest where the surface tilts most towards the light,
     * absent across the flat centre. A weighted highlight rather than a Fresnel term, which
     * is enough at the size a phone panel is actually seen.
     */
    static float specular(float depth, float alignment) {
        float rim = slope(clamp01(depth));
        float facing = clamp01(alignment);
        return clamp01(rim * facing * 1.35f);
    }

    /**
     * The bevel band, capped so it stays an edge.
     *
     * <p>A fifth of the shorter side at most. Wider than that and the two bevels meet in the
     * middle: every pixel of the surface is sloped, nothing is flat, and the pane reads as a
     * soft glow rather than a slab with an edge. The flat centre is what makes it glass.
     */
    static float bevelWidth(float shorterSidePx, float requestedPx) {
        float maximum = shorterSidePx * 0.2f;
        return Math.max(1f, Math.min(requestedPx, maximum));
    }

    /** Refraction is only worth its cost on surfaces large enough to read as a pane. */
    static boolean worthRefracting(float widthPx, float heightPx, float minimumSidePx) {
        return widthPx >= minimumSidePx && heightPx >= minimumSidePx;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}

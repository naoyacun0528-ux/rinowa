package com.neonglass;

/**
 * The rate the panel in front of the player is actually drawing at.
 *
 * <p>Every animation in this app advances by elapsed time, so it already runs correctly at any
 * rate. What is not automatic are the two moments where elapsed time is unknown or untrusted:
 * the very first frame, which has no previous frame to measure against, and a gap so short it
 * cannot be a real frame. Both were fixed at 60Hz assumptions, which made the first frame of
 * every animation a little long on a 120Hz phone.
 *
 * <p>Phones do not hold one rate either. An LTPO panel moves between modes while the app runs,
 * so the rate is sampled again as the app draws rather than read once at startup.
 *
 * <p>Kept free of Android types so the arithmetic can be checked on the JVM.
 */
final class DisplayRefreshRate {
    /** Used when the display has not reported a believable rate yet. */
    static final float FALLBACK_HZ = 60f;

    /** Below this a report is not a panel rate: a detached view answers 0. */
    private static final float SLOWEST_HZ = 24f;
    /** Above this a report is not a panel rate either, and would make frames vanishingly short. */
    private static final float FASTEST_HZ = 480f;

    /** Beyond this the app has stalled rather than drawn a frame, at any refresh rate. */
    private static final float STALL_SECONDS = 0.034f;

    /**
     * The shortest gap still treated as a frame: half a frame on the fastest panel this
     * recognises, so no real frame on any panel is ever clamped.
     *
     * <p>Deliberately not derived from the reported rate. A panel that has just changed mode
     * can report the rate it was running at a frame ago, and a floor computed from the slower
     * report would speed motion up for exactly as long as the report lagged. Its only job is
     * to reject a gap that cannot be a frame at all — a duplicated callback, or a clock that
     * went backwards.
     */
    static final float SHORTEST_FRAME_SECONDS = 1f / (FASTEST_HZ * 2f);

    private DisplayRefreshRate() {
    }

    /** Turns whatever the platform reported into a rate worth doing arithmetic with. */
    static float sanitize(float reportedHz) {
        if (Float.isNaN(reportedHz) || Float.isInfinite(reportedHz)) return FALLBACK_HZ;
        if (reportedHz < SLOWEST_HZ) return FALLBACK_HZ;
        return Math.min(reportedHz, FASTEST_HZ);
    }

    /** One frame at this rate: 60Hz gives 16.7ms, 120Hz gives 8.3ms, 144Hz gives 6.9ms. */
    static float frameSeconds(float hz) {
        return 1f / sanitize(hz);
    }

    /** The longest gap still treated as continuous motion; past it, animation would teleport. */
    static float longestFrameSeconds(float hz) {
        return Math.max(frameSeconds(hz) * 2f, STALL_SECONDS);
    }
}

package jp.echo.android.core.haptics

/**
 * The semantic vocabulary of the app's haptics.
 *
 * Screens never describe *how* something should feel — they describe *what happened*.
 * Translating meaning into a concrete waveform is this module's job, and its job alone.
 *
 * See docs/HAPTIC_DESIGN.md for the intent behind each token.
 */
enum class HapticToken {
    /** The selected item changed. Must be tiny — this fires while a finger is moving. */
    Selection,

    /** The screen changed. */
    Navigation,

    /** A light commitment: toggle, mark-as-read, draft saved. */
    SoftConfirm,

    /** A message left the device. Short, sharp, no tail. */
    Send,

    /** A point of no return was crossed — e.g. the reply-swipe became valid. Fires once. */
    Threshold,

    /** The finger came back below a threshold. Weaker than [Threshold]. Fires once. */
    ThresholdRelease,

    /** A reaction was committed. Slight bloom. */
    Reaction,

    /** An operation succeeded. Rising pair. */
    Success,

    /** Attention needed. Falling pair. */
    Warning,

    /** An operation failed. Not "strong" — congested and dull, so it reads as *blocked*. */
    Error,

    /** A destructive action was committed. Low, heavy, unmistakably different from everything else. */
    Destructive,
}

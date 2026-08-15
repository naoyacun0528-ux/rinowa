package com.neonglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The numbers that decide how NEON GLASS looks, in one place and adjustable while the app runs.
 *
 * <p>These were constants. Every change meant a build, an install and a screenshot, which is a
 * slow way to answer a question that is ultimately "does that look right to you". Here they are
 * values a slider can move, so the answer arrives while looking at the screen.
 *
 * <p>Kept in their own preferences file rather than the app's. Nothing here is game data, and a
 * tuning experiment must not be able to disturb records, XP or streaks.
 *
 * <p>Sliders work in whole numbers, so each value is stored as an integer and converted on the
 * way out. The ranges are wide enough to reach clearly wrong on both sides: a dial that cannot
 * overshoot cannot tell you where the good part ends.
 */
public final class NeonGlassTuning {
    /** Panels and buttons marked "strong" carry these values; quieter surfaces get a fraction. */
    private static final float QUIET_RATIO = 0.76f;
    /** Transmission differs less between the two than the light does. */
    private static final float QUIET_OPACITY_RATIO = 0.84f;

    private static final String FILE = "neon_glass_tuning";

    /**
     * The three that come first, in percent, where a hundred is what the design asks for.
     *
     * <p>Seven physical quantities is a workshop. Most people want to answer one question —
     * brighter or quieter — and the answer should not require knowing what a bevel is. These
     * three are scales over what the surface already does, so a hundred changes nothing and the
     * numbers below stay meaningful on their own.
     */
    static final int SHEEN_DEFAULT = 60;       // the sheen across the face, x0.60
    static final int BLOOM_DEFAULT = 60;       // the bloom towards the light, x0.60
    static final int GAME_DEFAULT = 100;       // everything, while a round is running

    static final int SPECULAR_DEFAULT = 95;    // 0.95
    static final int REFLECT_DEFAULT = 62;     // 0.62
    static final int BEVEL_DEFAULT = 42;       // radius x 0.42
    static final int REACH_DEFAULT = 340;      // bevel x 3.40
    static final int SPLIT_DEFAULT = 16;       // 0.16
    static final int OPACITY_DEFAULT = 88;     // 0.88
    /**
     * Settled on by looking at it. Thinner than the wash NEON GLASS used to paint, and the
     * text on a pane reads better for it: the old film lifted the whole surface towards grey,
     * which is the direction that closes the gap to white lettering rather than opening it.
     */
    static final int DENSITY_DEFAULT = 70;

    static final int SHEEN_MAX = 160;
    static final int BLOOM_MAX = 160;
    static final int GAME_MAX = 160;
    static final int SPECULAR_MAX = 250;
    static final int REFLECT_MAX = 200;
    static final int BEVEL_MAX = 120;
    static final int REACH_MAX = 800;
    static final int SPLIT_MAX = 60;
    static final int OPACITY_MAX = 100;
    static final int DENSITY_MAX = 150;

    private static final NeonGlassTuning CURRENT = new NeonGlassTuning();

    private SharedPreferences preferences;
    private int sheen = SHEEN_DEFAULT;
    private int bloom = BLOOM_DEFAULT;
    private int game = GAME_DEFAULT;
    private int specular = SPECULAR_DEFAULT;
    private int reflect = REFLECT_DEFAULT;
    private int bevel = BEVEL_DEFAULT;
    private int reach = REACH_DEFAULT;
    private int split = SPLIT_DEFAULT;
    private int opacity = OPACITY_DEFAULT;
    private int density = DENSITY_DEFAULT;
    private long revision;

    /** Package-private rather than private so a test can hold a set of its own. */
    NeonGlassTuning() {
    }

    public static NeonGlassTuning current() {
        return CURRENT;
    }

    /** Reads whatever was last settled on. Safe to call more than once. */
    public void load(Context context) {
        if (preferences != null) return;
        preferences = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        sheen = preferences.getInt("sheen", SHEEN_DEFAULT);
        bloom = preferences.getInt("bloom", BLOOM_DEFAULT);
        game = preferences.getInt("game", GAME_DEFAULT);
        specular = preferences.getInt("specular", SPECULAR_DEFAULT);
        reflect = preferences.getInt("reflect", REFLECT_DEFAULT);
        bevel = preferences.getInt("bevel", BEVEL_DEFAULT);
        reach = preferences.getInt("reach", REACH_DEFAULT);
        split = preferences.getInt("split", SPLIT_DEFAULT);
        opacity = preferences.getInt("opacity", OPACITY_DEFAULT);
        density = preferences.getInt("density", DENSITY_DEFAULT);
        revision++;
    }

    public void set(Dial dial, int value) {
        int clamped = Math.max(0, Math.min(dial.maximum, value));
        if (dial.read(this) == clamped) return;
        dial.write(this, clamped);
        revision++;
        if (preferences != null) preferences.edit().putInt(dial.key, clamped).apply();
    }

    public int get(Dial dial) {
        return dial.read(this);
    }

    /** Puts every dial back where this round started, so a bad experiment costs one tap. */
    public void reset() {
        for (Dial dial : Dial.values()) set(dial, dial.fallback);
    }

    /** Changes whenever a dial moves, so the panes know to repaint. */
    public long revision() {
        return revision;
    }

    /**
     * How much of the sheen across the face is painted, and how much of the bloom.
     *
     * <p>Both are scales over alpha rather than quantities of their own, so a hundred is the
     * full effect as designed and zero removes it without leaving anything half-drawn. The
     * game scale rides on top of whichever surface belongs to a round in progress: a reaction
     * game is not the place to be looking at the glass.
     */
    int sheenAlpha(int baseAlpha, boolean gameSurface) {
        return scaledAlpha(baseAlpha, sheen, gameSurface);
    }

    int bloomAlpha(int baseAlpha, boolean gameSurface) {
        return scaledAlpha(baseAlpha, bloom, gameSurface);
    }

    /** The game scale on its own, for effects that are not painted through an alpha. */
    float gameScale(boolean gameSurface) {
        return gameSurface ? game / 100f : 1f;
    }

    private int scaledAlpha(int baseAlpha, int percent, boolean gameSurface) {
        float amount = percent / 100f;
        if (gameSurface) amount *= game / 100f;
        return Math.max(0, Math.min(255, Math.round(baseAlpha * amount)));
    }

    float specular(boolean strong) {
        return scale(specular / 100f, strong, QUIET_RATIO);
    }

    float reflect(boolean strong) {
        return scale(reflect / 100f, strong, QUIET_RATIO);
    }

    /** The requested bevel width as a multiple of the surface's corner radius. */
    float bevelRatio() {
        return bevel / 100f;
    }

    /** How far the rim reaches for what it bends, as a multiple of the bevel width. */
    float reachRatio(boolean strong) {
        return scale(reach / 100f, strong, QUIET_RATIO);
    }

    float split() {
        return split / 100f;
    }

    float edgeOpacity(boolean strong) {
        return scale(opacity / 100f, strong, QUIET_OPACITY_RATIO);
    }

    /**
     * How heavily the material's own wash is painted over what the pane transmits.
     *
     * <p>Taking the colour out of the material only made the panes paler: a white film is still
     * a film. What actually lets more of the backdrop through is painting less of anything at
     * all. Below 1 the wash thins and the scene behind comes forward with its colour intact,
     * which is where a pane can be both coloured and genuinely see-through.
     */
    float density() {
        return density / 100f;
    }

    private static float scale(float value, boolean strong, float quietRatio) {
        return strong ? value : value * quietRatio;
    }

    /**
     * A whole look in one tap.
     *
     * <p>Seven dials is a workshop, not a setting. Most people want to pick a character for the
     * glass and be done, so each preset is a complete set of values with a name — and the dials
     * stay underneath for anyone who wants to keep going.
     */
    public enum Preset {
        STANDARD("標準", "設計どおりの釣り合い",
                60, 60, 70, 100, 95, 62, 42, 340, 16, 88),
        CLEAR("澄んだ", "素材を薄く、後ろをよく通す",
                34, 42, 45, 88, 88, 72, 38, 420, 20, 82),
        DEEP("深い", "厚みのある板。面も縁も強く光る",
                108, 118, 128, 115, 130, 84, 56, 300, 14, 96),
        QUIET("控えめ", "主張を抑えた静かなガラス",
                22, 28, 88, 78, 62, 40, 34, 260, 10, 74);

        public final String label;
        public final String detail;
        private final int[] values;

        Preset(String label, String detail, int sheen, int bloom, int density, int game,
               int specular, int reflect, int bevel,
               int reach, int split, int opacity) {
            this.label = label;
            this.detail = detail;
            this.values = new int[]{sheen, bloom, density, game,
                    specular, reflect, bevel, reach, split, opacity};
        }

        public void applyTo(NeonGlassTuning tuning) {
            Dial[] dials = Dial.values();
            for (int index = 0; index < dials.length && index < values.length; index++) {
                tuning.set(dials[index], values[index]);
            }
        }

        /** Whether every dial currently sits where this preset would put it. */
        public boolean matches(NeonGlassTuning tuning) {
            Dial[] dials = Dial.values();
            for (int index = 0; index < dials.length && index < values.length; index++) {
                if (tuning.get(dials[index]) != values[index]) return false;
            }
            return true;
        }
    }

    /**
     * One adjustable number: how to reach it, how far it goes, and what it is called.
     *
     * <p>The first three are the ones on the surface of the studio, in percent. Everything after
     * them is a physical quantity and lives behind "詳細を調整する".
     */
    public enum Dial {
        SHEEN("sheen", "面の光沢", "板の面を斜めに走る光の強さ",
                SHEEN_MAX, SHEEN_DEFAULT),
        BLOOM("bloom", "光のにじみ", "光源の側がどれだけ明るく広がるか",
                BLOOM_MAX, BLOOM_DEFAULT),
        DENSITY("density", "素材の濃さ", "下げるほど後ろが濃く出る。色は保ったまま",
                DENSITY_MAX, DENSITY_DEFAULT),
        GAME("game", "ゲーム中の強さ", "ラウンド中だけガラス全体にかかる倍率",
                GAME_MAX, GAME_DEFAULT),
        SPECULAR("specular", "縁の輝き", "光を受けた縁がどこまで白く飛ぶか",
                SPECULAR_MAX, SPECULAR_DEFAULT),
        REFLECT("reflect", "縁の反射", "背景のネオンがどれだけ縁に映るか",
                REFLECT_MAX, REFLECT_DEFAULT),
        BEVEL("bevel", "縁の太さ", "角の丸みに対するベベルの幅",
                BEVEL_MAX, BEVEL_DEFAULT),
        REACH("reach", "屈折の距離", "縁がどれだけ遠くから色を引き寄せるか",
                REACH_MAX, REACH_DEFAULT),
        SPLIT("split", "色収差", "赤と青が緑からどれだけ離れるか",
                SPLIT_MAX, SPLIT_DEFAULT),
        OPACITY("opacity", "縁の濃さ", "いちばん外側がどれだけ不透明か",
                OPACITY_MAX, OPACITY_DEFAULT);

        final String key;
        public final String label;
        public final String detail;
        public final int maximum;
        public final int fallback;

        Dial(String key, String label, String detail, int maximum, int fallback) {
            this.key = key;
            this.label = label;
            this.detail = detail;
            this.maximum = maximum;
            this.fallback = fallback;
        }

        /**
         * Whether this dial appears in GLASS STUDIO at all.
         *
         * <p>Four, and no fold. Ten sliders behind a "詳細を調整する" button is not a settings
         * screen, it is two settings screens stacked — and the panel has other things to show:
         * the design language above it and eight unlockable themes below. The six that are not
         * here are the shape of the bevel and what the rim does with it. They were settled by
         * looking at them, they are right, and leaving them adjustable only means leaving them
         * breakable.
         */
        public boolean inStudio() {
            return this == SHEEN || this == BLOOM || this == DENSITY || this == GAME;
        }

        int read(NeonGlassTuning tuning) {
            return switch (this) {
                case SHEEN -> tuning.sheen;
                case BLOOM -> tuning.bloom;
                case GAME -> tuning.game;
                case SPECULAR -> tuning.specular;
                case REFLECT -> tuning.reflect;
                case BEVEL -> tuning.bevel;
                case REACH -> tuning.reach;
                case SPLIT -> tuning.split;
                case OPACITY -> tuning.opacity;
                case DENSITY -> tuning.density;
            };
        }

        void write(NeonGlassTuning tuning, int value) {
            switch (this) {
                case SHEEN -> tuning.sheen = value;
                case BLOOM -> tuning.bloom = value;
                case GAME -> tuning.game = value;
                case SPECULAR -> tuning.specular = value;
                case REFLECT -> tuning.reflect = value;
                case BEVEL -> tuning.bevel = value;
                case REACH -> tuning.reach = value;
                case SPLIT -> tuning.split = value;
                case OPACITY -> tuning.opacity = value;
                case DENSITY -> tuning.density = value;
            }
        }

        /**
         * What the number means once it leaves the slider, for reading back a settled value.
         *
         * <p>The three on the surface read as a percentage with a word beside it, because a
         * hundred means "as designed" and a person deciding between brighter and quieter should
         * not have to hold a scale in their head to know which way they have gone.
         */
        public String describe(NeonGlassTuning tuning) {
            int raw = read(tuning);
            if (inStudio()) return raw + "%・" + strength(raw);
            return switch (this) {
                case BEVEL -> "半径 x " + (raw / 100f);
                case REACH -> "ベベル x " + (raw / 100f);
                default -> String.valueOf(raw / 100f);
            };
        }

        /** Where a percentage sits, in words, so the number is not the only thing to read. */
        static String strength(int percent) {
            if (percent < 45) return "控えめ";
            if (percent <= 115) return "標準";
            if (percent <= 140) return "強め";
            return "最大";
        }
    }
}

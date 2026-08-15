package com.neonglass;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

/**
 * NEON GLASS for lettering rather than for panels.
 *
 * <p>A pane is a shape the shader can measure; a glyph is not. So the material is applied the
 * only way text can carry it — as the paint the glyphs are filled with. Each digit then shows
 * the backdrop through itself, lit from above and deepening toward its foot, which is what a
 * character cut out of glass does.
 *
 * <p>It stays legible on purpose. The ink keeps most of its own colour and only lets a fraction
 * of the backdrop through: a countdown that has become hard to read has failed at the one job
 * it has, however much like glass it looks.
 */
public final class NeonGlassInk {
    /**
     * How much of what is behind the digit shows through it.
     *
     * <p>Held down deliberately. The backdrop here is nearly black, so every part of it that
     * arrives inside a glyph takes brightness away from a number the player has to read at a
     * glance. Enough to see the digit is made of something; not enough to dim it.
     */
    private static final float TRANSMIT = 0.18f;
    /** How bright the top of a glyph catches the light. */
    private static final float SPECULAR = 0.34f;

    private static final String SOURCE =
            "uniform shader uBackdrop;\n"
          + "uniform float4 uBase;\n"
          + "uniform float2 uOrigin;\n"
          + "uniform float uHeight;\n"
          + "uniform float4 uInk;\n"
          + "uniform float uTransmit;\n"
          + "uniform float uSpecular;\n"
          + "\n"
          + "half4 main(float2 p) {\n"
          // What is behind this part of the digit, composited the way the backdrop draws it.
          + "    half4 layered = uBackdrop.eval(p + uOrigin);\n"
          + "    half3 seen = half3(uBase.rgb) * (1.0 - layered.a) + layered.rgb;\n"
          + "\n"
          // Height through the glyph: bright at the crown, deeper at the foot. This is what
          // makes a letter read as a solid object rather than a flat stencil.
          + "    float v = clamp(p.y / max(uHeight, 1.0), 0.0, 1.0);\n"
          + "    half3 ink = half3(uInk.rgb) * half(0.88 + 0.42 * (1.0 - v));\n"
          + "    half3 lit = mix(ink, seen, half(uTransmit));\n"
          + "\n"
          // A highlight along the top, blown out at the very crown the way the panes' rims are.
          + "    float crown = 1.0 - v;\n"
          + "    lit = lit + half3(crown * crown * crown * uSpecular);\n"
          + "    return half4(lit, 1.0);\n"
          + "}\n";

    private NeonGlassInk() {
    }

    /**
     * Gives a text view the glass material. Falls back to a lit gradient where AGSL is not
     * available, which keeps the lettering looking like the same family of surface.
     */
    public static void apply(TextView view, int ink, boolean liquid) {
        if (!liquid) {
            view.getPaint().setShader(null);
            view.invalidate();
            return;
        }
        Shader material = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            material = refracting(view, ink);
        }
        if (material == null) material = lit(view, ink);
        view.getPaint().setShader(material);
        view.invalidate();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static Shader refracting(TextView view, int ink) {
        NeonGlassScene scene = NeonGlassScene.current();
        if (!scene.isPublished() || view.getHeight() <= 0) return null;
        try {
            RuntimeShader shader = new RuntimeShader(SOURCE);
            shader.setInputShader("uBackdrop", scene.backdrop());
            setColour(shader, "uBase", scene.baseColor());
            int[] location = new int[2];
            view.getLocationInWindow(location);
            shader.setFloatUniform("uOrigin",
                    location[0] - scene.originX(), location[1] - scene.originY());
            shader.setFloatUniform("uHeight", view.getHeight());
            setColour(shader, "uInk", ink);
            shader.setFloatUniform("uTransmit", TRANSMIT);
            shader.setFloatUniform("uSpecular", SPECULAR);
            return shader;
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** The same read without a shader: bright at the crown, deeper at the foot. */
    private static Shader lit(TextView view, int ink) {
        float height = Math.max(1f, view.getHeight());
        return new LinearGradient(0f, 0f, 0f, height,
                new int[]{blend(ink, Color.WHITE, 0.55f), ink, blend(ink, Color.BLACK, 0.30f)},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static void setColour(RuntimeShader shader, String name, int colour) {
        shader.setFloatUniform(name,
                Color.red(colour) / 255f, Color.green(colour) / 255f,
                Color.blue(colour) / 255f, Color.alpha(colour) / 255f);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}

package com.neonglass;

import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * The NEON GLASS pane: a rounded slab that bends the backdrop at its rim.
 *
 * <p>The shape is a signed distance field, so the distance from any pixel to the edge is known
 * exactly. That distance drives a bevel profile ({@link GlassProfile}): steep at the rim, flat
 * through the middle. The backdrop is sampled along the outward normal, offset by the slope of
 * that profile, which bends the view most at the edge and leaves the centre untouched. Text
 * sitting on the pane is drawn afterwards and is never displaced.
 *
 * <p>The backdrop arrives as a shader rather than a texture — the same gradients the backdrop
 * view draws with, handed over through {@link NeonGlassScene}. No capture, no readback, and no
 * second copy of the arithmetic that could fall out of step with what is on screen.
 *
 * <p>Requires AGSL, so Android 13. Older devices keep the painted NEON GLASS look.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class NeonGlassShader {
    /**
     * How square a corner is. 2 is a plain circular arc; higher holds the straight edge for
     * longer and then turns more smoothly, which is the continuous curvature Apple's surfaces
     * are cut with. Past about 5 the corner starts to read as boxy.
     */
    private static final float SQUIRCLE_EXPONENT = 3.6f;

    private static final String SOURCE =
            "uniform shader uBackdrop;\n"
          + "uniform float4 uBase;\n"
          + "uniform float2 uPane;\n"
          + "uniform float2 uOrigin;\n"
          + "uniform float uRadius;\n"
          + "uniform float uSquircle;\n"
          + "uniform float uBevel;\n"
          + "uniform float uReach;\n"
          + "uniform float uSplit;\n"
          + "uniform float uEdgeOpacity;\n"
          + "uniform float uSpecular;\n"
          + "uniform float uReflect;\n"
          + "uniform float uMirror;\n"
          + "uniform float2 uLight;\n"
          + "uniform float4 uTint;\n"
          + "uniform float uTintAmount;\n"
          + "uniform float4 uGrid;\n"
          + "\n"
          // Distance to the rounded rectangle. Negative inside, zero on the edge.
          + "float paneDistance(float2 p) {\n"
          + "    float2 halfPane = uPane * 0.5;\n"
          + "    float r = min(uRadius, min(halfPane.x, halfPane.y));\n"
          + "    float2 q = abs(p - halfPane) - halfPane + r;\n"
          + "    float2 outside = max(q, float2(0.0));\n"
          // A circular corner switches from straight edge to arc in one step, and the eye
          // catches that switch. A superellipse does not: its curvature arrives gradually.
          // That is the difference between a rounded rectangle and the shape Apple's glass is
          // cut to. The exponent only matters in a corner, where both parts are positive.
          + "    float corner = min(outside.x, outside.y) > 0.0\n"
          + "        ? pow(pow(outside.x, uSquircle) + pow(outside.y, uSquircle), 1.0 / uSquircle)\n"
          + "        : length(outside);\n"
          + "    return min(max(q.x, q.y), 0.0) + corner - r;\n"
          + "}\n"
          + "\n"
          // The backdrop as the backdrop itself draws it: translucent gradients over a flat
          // colour. Their result arrives premultiplied, so it composites straight onto the base.
          + "half3 backdropAt(float2 p) {\n"
          + "    half4 layered = uBackdrop.eval(p);\n"
          + "    half3 colour = half3(uBase.rgb) * (1.0 - layered.a) + layered.rgb;\n"
          // The playfield grid, when a round is drawing one. Everywhere else the backdrop is a
          // smooth gradient, and bending a smooth gradient only produces another smooth
          // gradient — which is why the refraction has been so hard to see at all. A straight
          // line is different: it visibly kinks where the glass bends it. This is the one
          // surface in the app with edges of its own, so it is the one place the bend can be
          // read rather than inferred.
          + "    if (uGrid.x > 1.0) {\n"
          + "        float2 cell = abs(fract((p + uGrid.y) / uGrid.x) - 0.5) * uGrid.x;\n"
          + "        float line = 1.0 - smoothstep(0.0, uGrid.z, min(cell.x, cell.y));\n"
          + "        colour = colour + half3(uGrid.w * line);\n"
          + "    }\n"
          + "    return colour;\n"
          + "}\n"
          + "\n"
          + "half4 main(float2 fragCoord) {\n"
          + "    float d = paneDistance(fragCoord);\n"
          + "    if (d > 0.5) return half4(0.0);\n"
          + "\n"
          // How deep into the pane this pixel sits: 0 on the rim, 1 past the bevel.
          + "    float bevel = max(uBevel, 1.0);\n"
          + "    float depth = clamp(-d / bevel, 0.0, 1.0);\n"
          + "\n"
          // Bevel slope, normalised to 0 across the flat centre and 1 at the rim. The curve is
          // the squircle's derivative, which rises steeply and only in the last part of the
          // band, so the bend stays in the edge instead of smearing across the surface.
          + "    float x = 1.0 - depth;\n"
          + "    float x3 = x * x * x;\n"
          + "    float base = max(1.0 - x * x3, 0.0001);\n"
          + "    float slope = min(x3 / pow(base, 0.75) / 6.0, 1.0);\n"
          + "\n"
          // Only the bevel is opaque. A pane of glass transmits its middle almost undeviated,
          // so the flat centre must stay out of the way and let through whatever is really
          // behind it — the app icon under the logo tile, the blurred scene under a panel.
          + "    float edge = 1.0 - depth;\n"
          + "    float opacity = uEdgeOpacity * edge * edge;\n"
          + "    if (opacity < 0.003) return half4(0.0);\n"
          + "\n"
          // Outward normal of the shape, from the gradient of the distance field.
          + "    float e = 1.0;\n"
          + "    float2 grad = float2(\n"
          + "        paneDistance(fragCoord + float2(e, 0.0)) - paneDistance(fragCoord - float2(e, 0.0)),\n"
          + "        paneDistance(fragCoord + float2(0.0, e)) - paneDistance(fragCoord - float2(0.0, e)));\n"
          + "    float glen = length(grad);\n"
          + "    float2 normal = glen > 0.0001 ? grad / glen : float2(0.0);\n"
          + "\n"
          // Bend the view outward along the normal, reaching furthest right at the rim. The
          // reach is a real distance, not a fraction of the bevel: what has to arrive at the
          // edge is a part of the backdrop far enough away to actually look different.
          + "    float2 origin = fragCoord + uOrigin;\n"
          + "    float2 push = normal * (slope * uReach);\n"
          // Glass does not bend every colour by the same amount: its refractive index rises
          // towards the blue end, so blue leaves the edge further from where it entered than
          // red does. Sampling the three channels at three distances along the same direction
          // is that dispersion, and it is what leaves the faint fringe of colour at a glass
          // edge. Blue therefore takes the longest reach and red the shortest — the other way
          // round would tint every edge backwards.
          //
          // Across the flat centre there is no bend to disperse, so one sample answers for all
          // three. The centre is most of the pane, so that is where the saving is.
          + "    half3 seen;\n"
          + "    half3 mirrored = half3(0.0);\n"
          + "    if (slope < 0.004) {\n"
          + "        seen = backdropAt(origin);\n"
          + "    } else {\n"
          + "        float2 split = push * uSplit;\n"
          + "        seen = half3(\n"
          + "            backdropAt(origin + push - split).r,\n"
          + "            backdropAt(origin + push).g,\n"
          + "            backdropAt(origin + push + split).b);\n"
          // Glass does not only transmit. A bevel tilted outward also acts as a mirror, and
          // what it mirrors is whatever lies on the far side of the pane — the opposite of the
          // direction it refracts from. On a screen this dark that reflection is most of what
          // makes glass look lit at all: the app's own neon has to appear ON the edge, not
          // merely through it.
          + "        mirrored = backdropAt(origin - normal * uMirror);\n"
          + "    }\n"
          + "\n"
          // The pane's own colour, kept light so the backdrop stays visible through it.
          + "    seen = mix(seen, half3(uTint.rgb), half(uTintAmount * uTint.a));\n"
          + "\n"
          // The reflection is strongest where the bevel is steepest and gone across the flat
          // centre, the way a real edge catches light at a grazing angle and a flat face does
          // not. Added rather than mixed: a reflection is light arriving, not light replaced.
          + "    float grazing = edge * edge * edge;\n"
          + "    seen = seen + mirrored * half(grazing * uReflect);\n"
          + "\n"
          // Rim light. Two lobes, not one. A single soft lobe can only ever be as bright as the
          // number in front of it, and a mid-grey edge on a near-black screen reads as dull
          // plastic. What makes an object look lit in the dark is not overall brightness but a
          // highlight that blows out: a broad arc for the shape of the edge, and a tight core
          // inside it that saturates to white. Peak here is deliberately over 1.
          + "    float band = edge * edge;\n"
          + "    band = band * band * edge;\n"
          + "    float facing = dot(normal, uLight);\n"
          + "    float key = clamp(facing, 0.0, 1.0);\n"
          + "    float broad = key * key * key;\n"
          + "    float glint = broad * broad * broad;\n"
          // The far side still catches the room, faintly. Without it the pane looks lit from
          // one side only, which is how a painted highlight looks and not how glass does.
          + "    float fill = clamp(-facing, 0.0, 1.0);\n"
          + "    fill = fill * fill * fill * 0.30;\n"
          + "    seen = seen + half3(band * (broad * 0.55 + glint * 1.9 + fill) * uSpecular);\n"
          + "\n"
          // Premultiplied, and the outline is left to the antialiased round rect the caller
          // draws this through. Smoothing it here as well would thin the edge twice and leave
          // a dark seam where the pane meets the backdrop.
          + "    return half4(seen * half(opacity), half(opacity));\n"
          + "}\n";

    private final RuntimeShader shader = new RuntimeShader(SOURCE);

    /** Positions the pane over the scene it samples. Returns the shader to paint with. */
    Shader configure(float paneWidth, float paneHeight, float originX, float originY,
                     float radiusPx, float bevelPx, float reachPx, float split,
                     float edgeOpacity, float specular, float reflect, float mirrorPx,
                     float lightX, float lightY, int tintColor, float tintAmount,
                     NeonGlassScene scene) {
        // Rebound every frame rather than cached: the backdrop animates its gradients through
        // their own local matrices, and this is what guarantees the glass sees today's light.
        shader.setInputShader("uBackdrop", scene.backdrop());
        setColour("uBase", scene.baseColor());
        shader.setFloatUniform("uPane", paneWidth, paneHeight);
        shader.setFloatUniform("uOrigin", originX, originY);
        shader.setFloatUniform("uRadius", radiusPx);
        shader.setFloatUniform("uSquircle", SQUIRCLE_EXPONENT);
        shader.setFloatUniform("uGrid", scene.gridSpacing(), scene.gridOffset(),
                scene.gridWidth(), scene.gridBrightness());
        shader.setFloatUniform("uBevel", bevelPx);
        shader.setFloatUniform("uReach", reachPx);
        shader.setFloatUniform("uSplit", split);
        shader.setFloatUniform("uEdgeOpacity", edgeOpacity);
        shader.setFloatUniform("uSpecular", specular);
        shader.setFloatUniform("uReflect", reflect);
        shader.setFloatUniform("uMirror", mirrorPx);
        shader.setFloatUniform("uLight", lightX, lightY);
        setColour("uTint", tintColor);
        shader.setFloatUniform("uTintAmount", tintAmount);
        return shader;
    }

    private void setColour(String name, int colour) {
        shader.setFloatUniform(name,
                ((colour >> 16) & 0xFF) / 255f,
                ((colour >> 8) & 0xFF) / 255f,
                (colour & 0xFF) / 255f,
                ((colour >>> 24) & 0xFF) / 255f);
    }
}

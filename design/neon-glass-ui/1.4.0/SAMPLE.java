package com.neonglass.sample;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neonglass.LiquidBackdropView;
import com.neonglass.LiquidGlassDrawable;
import com.neonglass.NeonGlassScene;
import com.neonglass.NeonGlassTuning;
import com.neonglass.NeonSurfaceController;

/**
 * The smallest thing that shows NEON GLASS working: a backdrop, and two panes standing on it.
 *
 * <p>Not part of the library — this is here to be read, copied from, and deleted. The four steps
 * in README.md are the four blocks below, in order.
 */
public final class GlassSampleActivity extends Activity {
    private static final int PRIMARY = Color.rgb(50, 224, 255);
    private static final int SECONDARY = Color.rgb(167, 255, 49);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // (0) The tuning is read once. QUIET is a good starting point for an app where people
        //     spend their time reading rather than reacting.
        NeonGlassTuning tuning = NeonGlassTuning.current();
        tuning.load(this);
        NeonGlassTuning.Preset.QUIET.applyTo(tuning);

        FrameLayout root = new FrameLayout(this);

        // (1) The backdrop goes underneath everything. It is what the glass bends.
        LiquidBackdropView backdrop = new LiquidBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // (2) The glass needs to know what colour sits under the backdrop's gradients.
        NeonGlassScene.current().setBaseColor(Color.rgb(6, 11, 26));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // (3) A pane. Anything with a background can be one.
        TextView panel = new TextView(this);
        panel.setText("NEON GLASS");
        panel.setTextColor(Color.WHITE);
        panel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        panel.setGravity(Gravity.CENTER);
        // strong = true: a pane that carries the screen. Use false for quieter surfaces.
        panel.setBackground(new LiquidGlassDrawable(PRIMARY, SECONDARY, dp(26), true));
        content.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));

        TextView button = new TextView(this);
        button.setText("送信");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setBackground(new LiquidGlassDrawable(PRIMARY, SECONDARY, dp(18), false));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        buttonParams.topMargin = dp(14);
        content.addView(button, buttonParams);

        // (4) Touch. The pane shrinks under a finger, its light leans towards the touch, and the
        //     backdrop ripples from the point. The supplier decides whether taps also buzz —
        //     wire it to the host app's own setting, or return false and never buzz.
        NeonSurfaceController surfaces = new NeonSurfaceController(
                this, () -> false, backdrop, PRIMARY, SECONDARY);
        surfaces.installPressMotion(panel, true, false);
        surfaces.installPressMotion(button, true, false);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

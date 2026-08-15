package com.neonglass;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;

/** Applies Neon surface treatments and their shared interaction motion. */
public final class NeonSurfaceController {
    private final Context context;
    /** Whether a tap should buzz. The host app owns that setting; this only asks. */
    private final java.util.function.BooleanSupplier hapticsEnabled;
    private final LiquidBackdropView liquidBackdrop;

    private int accentPrimary;
    private int accentSecondary;

    public NeonSurfaceController(Context context, java.util.function.BooleanSupplier hapticsEnabled,
                          LiquidBackdropView liquidBackdrop,
                          int accentPrimary, int accentSecondary) {
        this.context = context;
        this.hapticsEnabled = hapticsEnabled;
        this.liquidBackdrop = liquidBackdrop;
        setPalette(accentPrimary, accentSecondary);
    }

    public void setPalette(int accentPrimary, int accentSecondary) {
        this.accentPrimary = accentPrimary;
        this.accentSecondary = accentSecondary;
    }

    public void decorateTaggedSurfaces(View view, boolean liquid) {
        Object tag = view.getTag();
        if (tag instanceof String role) {
            boolean interactive = view.isClickable()
                    && (role.equals("action_surface") || role.equals("drawer_surface")
                    || role.equals("setting_surface"));
            if (liquid) {
                boolean strong = role.equals("action_surface") || role.equals("glass_panel")
                        || role.equals("drawer_panel");
                float radius = role.equals("glass_panel") || role.equals("drawer_panel")
                        ? dp(26) : role.equals("drawer_surface") ? dp(16)
                        : role.equals("gauge_surface") ? dp(15) : dp(18);
                Drawable glass = new LiquidGlassDrawable(accentPrimary, accentSecondary,
                        radius, strong);
                if (role.equals("glass_panel") || role.equals("drawer_panel")) {
                    view.setBackground(new LayerDrawable(new Drawable[]{
                            rounded(Color.argb(108, 3, 8, 24), Math.round(radius),
                                    withAlpha(accentPrimary, 38), dp(1)),
                            glass
                    }));
                } else {
                    view.setBackground(glass);
                }
                if (interactive) installPressMotion(view, true, false);
                else view.setOnTouchListener(null);
            } else {
                if (role.equals("drawer_panel") || role.equals("glass_panel")) {
                    view.setBackground(rounded(Color.rgb(7, 14, 32), dp(26),
                            withAlpha(accentPrimary, 100), dp(1)));
                } else if (role.equals("drawer_surface")) {
                    view.setBackground(rounded(Color.argb(205, 10, 22, 45), dp(16),
                            Color.argb(65, 111, 157, 197), dp(1)));
                } else if (role.equals("setting_surface")) {
                    view.setBackground(rounded(Color.argb(215, 10, 19, 39), dp(15),
                            Color.argb(70, 98, 135, 174), dp(1)));
                } else if (role.equals("action_surface")) {
                    view.setBackground(rounded(Color.argb(46, 50, 224, 255), dp(18),
                            withAlpha(accentPrimary, 145), dp(1)));
                } else if (role.equals("gauge_surface")) {
                    view.setBackground(rounded(Color.argb(196, 8, 15, 33), dp(15),
                            withAlpha(accentPrimary, 92), dp(1)));
                }
                if (interactive) installPressMotion(view, true, false);
                else view.setOnTouchListener(null);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                decorateTaggedSurfaces(group.getChildAt(i), liquid);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void installPressMotion(View view, boolean enabled, boolean tactile) {
        if (!enabled) {
            view.setOnTouchListener(null);
            view.animate().cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationZ(0f);
            return;
        }
        final float[] lastTouch = new float[2];
        final long[] lastTouchAt = new long[1];
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    lastTouchAt[0] = event.getEventTime();
                    target.animate().cancel();
                    target.animate().scaleX(0.95f).scaleY(0.95f).translationZ(dp(6))
                            .setDuration(75L).start();
                    if (liquidBackdrop != null) {
                        liquidBackdrop.pressAt(event.getRawX(), event.getRawY());
                    }
                    setGlassInteraction(target.getBackground(), event.getX(), event.getY(), true);
                    setGlassVelocity(target.getBackground(), 0f, 0f);
                    if (tactile) performSystemClick(target);
                    break;
                case MotionEvent.ACTION_MOVE:
                    long elapsed = Math.max(1L, event.getEventTime() - lastTouchAt[0]);
                    float vx = (event.getRawX() - lastTouch[0]) * 1000f / elapsed;
                    float vy = (event.getRawY() - lastTouch[1]) * 1000f / elapsed;
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    lastTouchAt[0] = event.getEventTime();
                    if (liquidBackdrop != null) {
                        liquidBackdrop.movePointer(event.getRawX(), event.getRawY());
                    }
                    setGlassInteraction(target.getBackground(), event.getX(), event.getY(), true);
                    setGlassVelocity(target.getBackground(), vx, vy);
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    target.animate().cancel();
                    target.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                            .setDuration(310L)
                            .setInterpolator(new OvershootInterpolator(1.05f)).start();
                    if (liquidBackdrop != null) liquidBackdrop.releasePointer();
                    setGlassInteraction(target.getBackground(), event.getX(), event.getY(), false);
                    setGlassVelocity(target.getBackground(), 0f, 0f);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && !tactile) {
                        performSystemClick(target);
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    public void performSystemClick(View view) {
        if (view != null && hapticsEnabled != null && hapticsEnabled.getAsBoolean()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    public void prepareExpressivePanel(View panel) {
        if (panel == null) return;
        panel.animate().cancel();
        panel.setTranslationY(dp(28));
        panel.setScaleX(0.955f);
        panel.setScaleY(0.955f);
        panel.setAlpha(0.70f);
    }

    public void animateExpressivePanelIn(View panel) {
        if (panel == null) return;
        panel.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(360L).setInterpolator(new OvershootInterpolator(0.62f)).start();
    }

    public void resetExpressivePanel(View panel) {
        if (panel == null) return;
        panel.animate().cancel();
        panel.setTranslationY(0f);
        panel.setScaleX(1f);
        panel.setScaleY(1f);
        panel.setAlpha(1f);
    }

    private void setGlassInteraction(Drawable drawable, float x, float y, boolean down) {
        if (drawable instanceof LiquidGlassDrawable glass) {
            glass.setInteraction(x, y, down);
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                setGlassInteraction(layers.getDrawable(i), x, y, down);
            }
        }
    }

    private void setGlassVelocity(Drawable drawable, float velocityX, float velocityY) {
        if (drawable instanceof LiquidGlassDrawable glass) {
            glass.setInteractionVelocity(velocityX, velocityY);
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                setGlassVelocity(layers.getDrawable(i), velocityX, velocityY);
            }
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private GradientDrawable rounded(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

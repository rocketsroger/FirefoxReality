package org.mozilla.vrbrowser.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

import org.mozilla.gecko.util.ThreadUtils;

public class AnimationHelper {
    public static final long FADE_ANIMATION_DURATION = 150;
    
    public static void fadeIn(View aView, long delay, final Runnable aCallback) {
        aView.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(0, 1);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.setDuration(FADE_ANIMATION_DURATION);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (aCallback != null) {
                    aCallback.run();
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        if (delay > 0) {
            animation.setStartOffset(delay);
        }
        aView.setAnimation(animation);
    }

    public static void fadeOut(final View aView, long delay, final Runnable aCallback) {
        aView.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(1, 0);
        animation.setInterpolator(new AccelerateInterpolator());
        animation.setDuration(FADE_ANIMATION_DURATION);
        if (delay > 0) {
            animation.setStartOffset(delay);
        }
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                aView.setVisibility(View.GONE);
                if (aCallback != null) {
                    aCallback.run();
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        aView.setAnimation(animation);
    }

    public static void animateViewPadding(View view, int paddingStart, int paddingEnd, int duration) {
        animateViewPadding(view, paddingStart, paddingEnd, duration, null);
    }

    public static void animateViewPadding(View view, int paddingStart, int paddingEnd, int duration, Runnable onAnimationEnd) {
        ValueAnimator animation = ValueAnimator.ofInt(paddingStart, paddingEnd);
        animation.setDuration(duration);
        animation.setInterpolator(new AccelerateDecelerateInterpolator());
        animation.addUpdateListener(valueAnimator -> {
            try {
                int newPadding = Integer.parseInt(valueAnimator.getAnimatedValue().toString());
                view.setPadding(newPadding, newPadding, newPadding, newPadding);
            } catch (NumberFormatException ex) {
                ex.printStackTrace();
            }
        });
        animation.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {

            }

            @Override
            public void onAnimationEnd(Animator animator) {
                if (onAnimationEnd != null) {
                    onAnimationEnd.run();
                }
            }

            @Override
            public void onAnimationCancel(Animator animator) {

            }

            @Override
            public void onAnimationRepeat(Animator animator) {

            }
        });
        animation.start();
    }

    public static void scaleIn(@NonNull View aView, long duration, long delay, final Runnable aCallback) {
        aView.setScaleX(0);
        aView.setScaleY(0);
        aView.animate().setStartDelay(delay).scaleX(1f).scaleY(1f).setDuration(duration).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (aCallback != null)
                    ThreadUtils.postToUiThread(aCallback);
            }
        }).setUpdateListener(animation -> aView.invalidate());
    }

    public static void scaleOut(@NonNull View aView, long duration, long delay, final Runnable aCallback) {
        aView.setScaleX(1);
        aView.setScaleY(1);
        aView.animate().setStartDelay(delay).scaleX(0f).scaleY(0f).setDuration(duration).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (aCallback != null)
                    ThreadUtils.postToUiThread(aCallback);
            }
        }).setUpdateListener(animation -> aView.invalidate());
    }

}

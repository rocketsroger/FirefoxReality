/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.audio.AudioEngine;
import org.mozilla.vrbrowser.browser.Media;
import org.mozilla.vrbrowser.browser.SessionChangeListener;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.engine.Session;
import org.mozilla.vrbrowser.search.suggestions.SuggestionsProvider;
import org.mozilla.vrbrowser.telemetry.TelemetryWrapper;
import org.mozilla.vrbrowser.ui.views.CustomUIButton;
import org.mozilla.vrbrowser.ui.views.NavigationURLBar;
import org.mozilla.vrbrowser.ui.views.UIButton;
import org.mozilla.vrbrowser.ui.views.UITextButton;
import org.mozilla.vrbrowser.ui.widgets.dialogs.VoiceSearchWidget;
import org.mozilla.vrbrowser.utils.AnimationHelper;
import org.mozilla.vrbrowser.utils.ServoUtils;
import org.mozilla.vrbrowser.utils.UIThreadExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class NavigationBarWidget extends UIWidget implements GeckoSession.NavigationDelegate,
        GeckoSession.ProgressDelegate, GeckoSession.ContentDelegate, WidgetManagerDelegate.WorldClickListener,
        WidgetManagerDelegate.UpdateListener, SessionChangeListener,
        NavigationURLBar.NavigationURLBarDelegate, VoiceSearchWidget.VoiceSearchDelegate,
        SharedPreferences.OnSharedPreferenceChangeListener, SuggestionsWidget.URLBarPopupDelegate,
        WindowWidget.BookmarksViewDelegate, WindowWidget.HistoryViewDelegate, TrayListener, WindowWidget.WindowListener {

    private AudioEngine mAudio;
    private UIButton mBackButton;
    private UIButton mForwardButton;
    private UIButton mReloadButton;
    private UIButton mHomeButton;
    private UIButton mServoButton;
    private NavigationURLBar mURLBar;
    private ViewGroup mNavigationContainer;
    private ViewGroup mFullScreenModeContainer;
    private ViewGroup mResizeModeContainer;
    private WindowWidget mAttachedWindow;
    private boolean mIsLoading;
    private boolean mIsInVRVideo;
    private boolean mAutoEnteredVRVideo;
    private Runnable mResizeBackHandler;
    private Runnable mFullScreenBackHandler;
    private Runnable mVRVideoBackHandler;
    private UIButton mResizeEnterButton;
    private UIButton mResizeExitButton;
    private UIButton mFullScreenExitButton;
    private UIButton mBrightnessButton;
    private UIButton mFullScreenResizeButton;
    private UIButton mProjectionButton;
    private UITextButton mPreset0;
    private UITextButton mPreset1;
    private UITextButton mPreset15;
    private UITextButton mPreset2;
    private UITextButton mPreset3;
    private ArrayList<CustomUIButton> mButtons;
    private VoiceSearchWidget mVoiceSearchWidget;
    private Context mAppContext;
    private SharedPreferences mPrefs;
    private SuggestionsWidget mPopup;
    private SuggestionsProvider mSuggestionsProvider;
    private VideoProjectionMenuWidget mProjectionMenu;
    private WidgetPlacement mProjectionMenuPlacement;
    private BrightnessMenuWidget mBrightnessWidget;
    private MediaControlsWidget mMediaControlsWidget;
    private Media mFullScreenMedia;
    private @VideoProjectionMenuWidget.VideoProjectionFlags Integer mAutoSelectedProjection;

    public NavigationBarWidget(Context aContext) {
        super(aContext);
        initialize(aContext);
    }

    public NavigationBarWidget(Context aContext, AttributeSet aAttrs) {
        super(aContext, aAttrs);
        initialize(aContext);
    }

    public NavigationBarWidget(Context aContext, AttributeSet aAttrs, int aDefStyle) {
        super(aContext, aAttrs, aDefStyle);
        initialize(aContext);
    }

    private void initialize(@NonNull Context aContext) {
        mAppContext = aContext.getApplicationContext();
        inflate(aContext, R.layout.navigation_bar, this);

        mAudio = AudioEngine.fromContext(aContext);
        mBackButton = findViewById(R.id.backButton);
        mForwardButton = findViewById(R.id.forwardButton);
        mReloadButton = findViewById(R.id.reloadButton);
        mHomeButton = findViewById(R.id.homeButton);
        mServoButton = findViewById(R.id.servoButton);
        mURLBar = findViewById(R.id.urlBar);
        mNavigationContainer = findViewById(R.id.navigationBarContainer);
        mFullScreenModeContainer = findViewById(R.id.fullScreenModeContainer);
        mResizeModeContainer = findViewById(R.id.resizeModeContainer);
        mFullScreenExitButton = findViewById(R.id.fullScreenExitButton);
        mBrightnessButton = findViewById(R.id.brightnessButton);
        mFullScreenResizeButton = findViewById(R.id.fullScreenResizeEnterButton);
        mProjectionButton = findViewById(R.id.projectionButton);

        mResizeBackHandler = () -> exitResizeMode(ResizeAction.RESTORE_SIZE);

        mFullScreenBackHandler = this::exitFullScreenMode;
        mVRVideoBackHandler = () -> {
            exitVRVideo();
            if (mAutoEnteredVRVideo) {
                exitFullScreenMode();
            }
        };

        mBackButton.setOnClickListener(v -> {
            v.requestFocusFromTouch();

            if (getSession().canGoBack()) {
                getSession().goBack();
            }

            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.BACK);
            }
        });

        mForwardButton.setOnClickListener(v -> {
            v.requestFocusFromTouch();
            getSession().goForward();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mReloadButton.setOnClickListener(v -> {
            v.requestFocusFromTouch();
            if (mIsLoading) {
                getSession().stop();
            } else {
                getSession().reload();
            }
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mHomeButton.setOnClickListener(v -> {
            v.requestFocusFromTouch();
            getSession().loadUri(getSession().getHomeUri());
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mServoButton.setOnClickListener(v -> {
            v.requestFocusFromTouch();
            getSession().toggleServo();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mResizeEnterButton = findViewById(R.id.resizeEnterButton);
        mResizeExitButton = findViewById(R.id.resizeExitButton);
        mPreset0 = findViewById(R.id.resizePreset0);
        mPreset1 = findViewById(R.id.resizePreset1);
        mPreset15 = findViewById(R.id.resizePreset15);
        mPreset2 = findViewById(R.id.resizePreset2);
        mPreset3 = findViewById(R.id.resizePreset3);

        mResizeEnterButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            enterResizeMode();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mResizeExitButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            exitResizeMode(ResizeAction.KEEP_SIZE);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mFullScreenResizeButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            enterResizeMode();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mFullScreenExitButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            exitFullScreenMode();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mProjectionButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            if (mAutoSelectedProjection != null) {
                enterVRVideo(mAutoSelectedProjection);
                return;
            }
            boolean wasVisible = mProjectionMenu.isVisible();
            closeFloatingMenus();

            if (!wasVisible) {
                mProjectionMenu.setVisible(true);
            }
        });

        mBrightnessButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
            boolean wasVisible = mBrightnessWidget.isVisible();
            closeFloatingMenus();
            if (!wasVisible) {
                float anchor = 0.5f + (float)mBrightnessButton.getMeasuredWidth() / (float)NavigationBarWidget.this.getMeasuredWidth();
                mBrightnessWidget.getPlacement().parentAnchorX = anchor;
                mBrightnessWidget.setVisible(true);
            }
        });


        mPreset0.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            setResizePreset(0.5f);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mPreset1.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            setResizePreset(1.0f);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mPreset15.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            setResizePreset(1.5f);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mPreset2.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            setResizePreset(2.0f);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mPreset3.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            setResizePreset(3.0f);
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
        });

        mButtons = new ArrayList<>();
        mButtons.addAll(Arrays.<CustomUIButton>asList(
                mBackButton, mForwardButton, mReloadButton, mHomeButton, mResizeEnterButton, mResizeExitButton,
                mServoButton, mPreset0, mPreset1, mPreset15, mPreset2, mPreset3));

        mURLBar.setDelegate(this);

        mWidgetManager.addUpdateListener(this);
        mWidgetManager.addWorldClickListener(this);

        mVoiceSearchWidget = createChild(VoiceSearchWidget.class, false);
        mVoiceSearchWidget.setDelegate(this);

        mSuggestionsProvider = new SuggestionsProvider(getContext());

        mPrefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mURLBar.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        mURLBar.onResume();
    }

    @Override
    public void releaseWidget() {
        mWidgetManager.removeUpdateListener(this);
        mWidgetManager.removeWorldClickListener(this);
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        
        if (mAttachedWindow != null && mAttachedWindow.isFullScreen()) {
            // Workaround for https://issuetracker.google.com/issues/37123764
            // exitFullScreenMode() may animate some views that are then released
            // so use a custom way to exit fullscreen here without triggering view updates.
            if (getSession().isInFullScreen()) {
                getSession().exitFullScreen();
            }
            mAttachedWindow.restoreBeforeFullscreenPlacement();
            mAttachedWindow.setIsFullScreen(false);
            mWidgetManager.popBackHandler(mFullScreenBackHandler);
        }

        detachFromWindow();

        mAttachedWindow = null;
        super.releaseWidget();
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        aPlacement.width = WidgetPlacement.dpDimension(getContext(), R.dimen.navigation_bar_width);
        aPlacement.worldWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width);
        aPlacement.height = WidgetPlacement.dpDimension(getContext(), R.dimen.navigation_bar_height);
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 1.0f;
        aPlacement.parentAnchorX = 0.5f;
        aPlacement.parentAnchorY = 0.0f;
        aPlacement.translationY = -35;
        aPlacement.opaque = false;
        aPlacement.cylinder = true;
    }

    @Override
    public void detachFromWindow() {
        if (mAttachedWindow != null && mAttachedWindow.isResizing()) {
            exitResizeMode(ResizeAction.RESTORE_SIZE);
        }
        if (mAttachedWindow != null && mAttachedWindow.isFullScreen()) {
            exitFullScreenMode();
        }

        if (getSession() != null) {
            cleanSession(getSession());
        }
        if (mAttachedWindow != null) {
            mAttachedWindow.removeBookmarksViewListener(this);
            mAttachedWindow.removeHistoryViewListener(this);
            mAttachedWindow.removeWindowListener(this);
        }
        mAttachedWindow = null;
    }

    @Override
    public void attachToWindow(@NonNull WindowWidget aWindow) {
        if (aWindow == mAttachedWindow) {
            return;
        }
        detachFromWindow();

        mWidgetPlacement.parentHandle = aWindow.getHandle();
        mAttachedWindow = aWindow;
        mAttachedWindow.addBookmarksViewListener(this);
        mAttachedWindow.addHistoryViewListener(this);
        mAttachedWindow.addWindowListener(this);

        if (mAttachedWindow != null) {
            mURLBar.setIsContentMode(mAttachedWindow.isBookmarksVisible() || mAttachedWindow.isHistoryVisible());
            mURLBar.setURL("");
            if (mAttachedWindow.isBookmarksVisible()) {
                mURLBar.setHint(R.string.url_bookmarks_title);
                mURLBar.setInsecureVisibility(View.GONE);

            } else if (mAttachedWindow.isHistoryVisible()) {
                mURLBar.setHint(R.string.url_history_title);
                mURLBar.setInsecureVisibility(View.GONE);

            } else {
                mURLBar.setURL(mAttachedWindow.getSession().getCurrentUri());
                mURLBar.setHint(R.string.search_placeholder);
                mURLBar.setInsecureVisibility(View.VISIBLE);
            }
        }

        clearFocus();

        if (getSession() != null) {
            setUpSession(getSession());
        }
        handleWindowResize();
    }

    private Session getSession() {
        if (mAttachedWindow != null) {
            return mAttachedWindow.getSession();
        }
        return null;
    }

    private void setUpSession(@NonNull Session aSession) {
        aSession.addSessionChangeListener(this);
        aSession.addNavigationListener(this);
        aSession.addProgressListener(this);
        aSession.addContentListener(this);
        mURLBar.setSession(getSession());
        updateServoButton();
        handleSessionState();
    }

    private void cleanSession(@NonNull Session aSession) {
        aSession.removeSessionChangeListener(this);
        aSession.removeNavigationListener(this);
        aSession.removeProgressListener(this);
        aSession.removeContentListener(this);
    }

    @Override
    public void onSessionChanged(@NonNull Session aOldSession, @NonNull Session aSession) {
        cleanSession(aOldSession);
        setUpSession(aSession);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    private void enterFullScreenMode() {
        if (mAttachedWindow.isFullScreen()) {
            return;
        }

        mWidgetManager.pushBackHandler(mFullScreenBackHandler);
        mAttachedWindow.setIsFullScreen(true);
        AnimationHelper.fadeIn(mFullScreenModeContainer, AnimationHelper.FADE_ANIMATION_DURATION, null);

        AnimationHelper.fadeOut(mNavigationContainer, 0, null);

        mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);

        mWidgetManager.setTrayVisible(false);

        if (mProjectionMenu == null) {
            mProjectionMenu = new VideoProjectionMenuWidget(getContext());
            mProjectionMenu.setParentWidget(this);
            mProjectionMenuPlacement = new WidgetPlacement(getContext());
            mWidgetManager.addWidget(mProjectionMenu);
            mProjectionMenu.setDelegate((projection)-> {
                if (mIsInVRVideo) {
                    // Reproject while reproducing VRVideo
                    mWidgetManager.showVRVideo(mAttachedWindow.getHandle(), projection);
                    closeFloatingMenus();
                } else {
                    enterVRVideo(projection);
                }
            });
        }
        if (mBrightnessWidget == null) {
            mBrightnessWidget = new BrightnessMenuWidget(getContext());
            mBrightnessWidget.setParentWidget(this);
            mWidgetManager.addWidget(mBrightnessWidget);
        }
        closeFloatingMenus();
        mWidgetManager.pushWorldBrightness(mBrightnessWidget, mBrightnessWidget.getSelectedBrightness());
    }

    private void exitFullScreenMode() {
        if (!mAttachedWindow.isFullScreen()) {
            mWidgetManager.setTrayVisible(true);
            return;
        }

        // We need to add a delay for the exitFullScreen() call to solve some viewport scaling issues,
        // See https://github.com/MozillaReality/FirefoxReality/issues/833 for more info.
        postDelayed(() -> {
            if (getSession().isInFullScreen()) {
                getSession().exitFullScreen();
            }
        }, 50);

        mWidgetManager.updateWidget(mAttachedWindow);

        mAttachedWindow.setIsFullScreen(false);
        mWidgetManager.popBackHandler(mFullScreenBackHandler);

        AnimationHelper.fadeIn(mNavigationContainer, AnimationHelper.FADE_ANIMATION_DURATION, null);

        mWidgetManager.popWorldBrightness(this);
        AnimationHelper.fadeOut(mFullScreenModeContainer, 0, null);

        mWidgetManager.setTrayVisible(true);
        closeFloatingMenus();
        mWidgetManager.popWorldBrightness(mBrightnessWidget);
    }

    private void enterResizeMode() {
        if (mAttachedWindow.isResizing()) {
            return;
        }
        mAttachedWindow.setIsResizing(true);
        mAttachedWindow.saveBeforeResizePlacement();
        startWidgetResize();
        AnimationHelper.fadeIn(mResizeModeContainer, AnimationHelper.FADE_ANIMATION_DURATION, null);
        if (mAttachedWindow.isFullScreen()) {
            AnimationHelper.fadeOut(mFullScreenModeContainer, 0, null);
        } else {
            AnimationHelper.fadeOut(mNavigationContainer, 0, null);
        }
        mWidgetManager.pushBackHandler(mResizeBackHandler);
        mWidgetManager.setTrayVisible(false);
        closeFloatingMenus();

        float maxScale = 3.0f;
        if (mAttachedWindow != null) {
            maxScale = mAttachedWindow.getMaxWindowScale();
        }

        mPreset3.setVisibility(maxScale >= 3.0f ? View.VISIBLE : View.GONE);
        mPreset2.setVisibility(maxScale >= 2.0f ? View.VISIBLE : View.GONE);
        mPreset15.setVisibility(maxScale == 1.5f ? View.VISIBLE: View.GONE);

        // Update visible presets
        UITextButton[] presets = { mPreset3, mPreset2, mPreset15, mPreset1};
        boolean fistVisible = true;
        for (UITextButton preset: presets) {
            if (fistVisible) {
                if (preset.getVisibility() != View.VISIBLE) {
                    continue;
                }
                fistVisible = false;
                preset.updateBackground(getContext().getDrawable(R.drawable.fullscreen_button_first),
                        getContext().getDrawable(R.drawable.fullscreen_button_private_first));
            } else {
                preset.updateBackground(getContext().getDrawable(R.drawable.fullscreen_button),
                        getContext().getDrawable(R.drawable.fullscreen_button_private));
            }
        }

        // Update preset styles
    }


    enum ResizeAction {
        KEEP_SIZE,
        RESTORE_SIZE
    }

    private void exitResizeMode(ResizeAction aResizeAction) {
        if (!mAttachedWindow.isResizing()) {
            return;
        }
        if (aResizeAction == ResizeAction.RESTORE_SIZE) {
            mAttachedWindow.restoreBeforeResizePlacement();
            mWidgetManager.updateWidget(mAttachedWindow);
            mWidgetManager.updateVisibleWidgets();
        }
        mAttachedWindow.setIsResizing(false);
        finishWidgetResize();
        if (mAttachedWindow.isFullScreen()) {
            AnimationHelper.fadeIn(mFullScreenModeContainer, AnimationHelper.FADE_ANIMATION_DURATION, null);
        } else {
            AnimationHelper.fadeIn(mNavigationContainer, AnimationHelper.FADE_ANIMATION_DURATION, null);
        }
        AnimationHelper.fadeOut(mResizeModeContainer, 0, () -> updateWidget());
        mWidgetManager.popBackHandler(mResizeBackHandler);
        mWidgetManager.setTrayVisible(!mAttachedWindow.isFullScreen());
        closeFloatingMenus();

        if (aResizeAction == ResizeAction.KEEP_SIZE) {
            TelemetryWrapper.windowsResizeEvent();
        }
    }

    private void enterVRVideo(@VideoProjectionMenuWidget.VideoProjectionFlags int aProjection) {
        if (mIsInVRVideo) {
            return;
        }
        mIsInVRVideo = true;
        mWidgetManager.pushBackHandler(mVRVideoBackHandler);
        mProjectionMenu.setSelectedProjection(aProjection);
        // Backup the placement because the same widget is reused in FullScreen & MediaControl menus
        mProjectionMenuPlacement.copyFrom(mProjectionMenu.getPlacement());

        mFullScreenMedia = getSession().getFullScreenVideo();

        this.setVisible(false);
        if (mFullScreenMedia != null && mFullScreenMedia.getWidth() > 0 && mFullScreenMedia.getHeight() > 0) {
            final boolean resetBorder = aProjection == VideoProjectionMenuWidget.VIDEO_PROJECTION_360 ||
                    aProjection == VideoProjectionMenuWidget.VIDEO_PROJECTION_360_STEREO;
            mAttachedWindow.enableVRVideoMode(mFullScreenMedia.getWidth(), mFullScreenMedia.getHeight(), resetBorder);
            // Handle video resize while in VR video playback
            mFullScreenMedia.setResizeDelegate((width, height) -> {
                mAttachedWindow.enableVRVideoMode(width, height, resetBorder);
            });
        }
        mAttachedWindow.setVisible(false);

        closeFloatingMenus();
        if (aProjection != VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE) {
            mWidgetManager.setControllersVisible(false);
        }

        if (mMediaControlsWidget == null) {
            mMediaControlsWidget = new MediaControlsWidget(getContext());
            mMediaControlsWidget.setParentWidget(mAttachedWindow.getHandle());
            mMediaControlsWidget.getPlacement().visible = false;
            mWidgetManager.addWidget(mMediaControlsWidget);
            mMediaControlsWidget.setBackHandler(mVRVideoBackHandler);
        }
        mMediaControlsWidget.setProjectionMenuWidget(mProjectionMenu);
        mMediaControlsWidget.setMedia(mFullScreenMedia);
        mMediaControlsWidget.setParentWidget(mAttachedWindow.getHandle());
        mMediaControlsWidget.setProjectionSelectorEnabled(true);
        mWidgetManager.updateWidget(mMediaControlsWidget);
        mWidgetManager.showVRVideo(mAttachedWindow.getHandle(), aProjection);
    }

    private void exitVRVideo() {
        if (!mIsInVRVideo) {
            return;
        }
        if (mFullScreenMedia != null) {
            mFullScreenMedia.setResizeDelegate(null);
        }
        mIsInVRVideo = false;
        mWidgetManager.popBackHandler(mVRVideoBackHandler);
        mWidgetManager.hideVRVideo();
        boolean composited = mProjectionMenu.getPlacement().composited;
        mProjectionMenu.getPlacement().copyFrom(mProjectionMenuPlacement);
        mProjectionMenu.getPlacement().composited = composited;
        mWidgetManager.updateWidget(mProjectionMenu);
        closeFloatingMenus();
        mWidgetManager.setControllersVisible(true);

        this.setVisible(true);
        mAttachedWindow.disableVRVideoMode();
        mAttachedWindow.setVisible(true);
        mMediaControlsWidget.setVisible(false);
    }

    private void setResizePreset(float aMultiplier) {
        final float aspect = SettingsStore.getInstance(getContext()).getWindowAspect();
        mAttachedWindow.resizeByMultiplier(aspect, aMultiplier);
    }

    public void showVoiceSearch() {
        mURLBar.showVoiceSearch(true);
    }

    public void updateServoButton() {
        // We show the Servo button if:
        // 1. the current session is using Servo. No matter what, we need the toggle button to go back to Gecko.
        // 2. Or, if the pref is enabled and the current url is white listed.
        boolean show = false;
        boolean isServoSession = false;
        if (getSession() != null){
            GeckoSession currentSession = getSession().getGeckoSession();
            if (currentSession != null) {
                String currentUri = getSession().getCurrentUri();
                boolean isPrefEnabled = SettingsStore.getInstance(mAppContext).isServoEnabled();
                boolean isUrlWhiteListed = ServoUtils.isUrlInServoWhiteList(mAppContext, currentUri);
                isServoSession = ServoUtils.isInstanceOfServoSession(currentSession);
                show = isServoSession || (isPrefEnabled && isUrlWhiteListed);
            }
            if (show) {
                mServoButton.setVisibility(View.VISIBLE);
                mServoButton.setImageResource(isServoSession ? R.drawable.ic_icon_gecko : R.drawable.ic_icon_servo);
            } else {
                mServoButton.setVisibility(View.GONE);
            }
        }
    }

    private void closeFloatingMenus() {
        if (mProjectionMenu != null) {
            mProjectionMenu.setVisible(false);
        }
        if (mBrightnessWidget != null) {
            mBrightnessWidget.setVisible(false);
        }
    }

    private void handleSessionState() {
        if (getSession() != null) {
            boolean isPrivateMode = getSession().isPrivateMode();

            mURLBar.setPrivateMode(isPrivateMode);
            for (CustomUIButton button : mButtons) {
                button.setPrivateMode(isPrivateMode);
            }
        }
    }

    @Override
    public void onLocationChange(GeckoSession session, String url) {
        if (mURLBar != null && !mAttachedWindow.isBookmarksVisible() && !mAttachedWindow.isHistoryVisible()) {
            Log.d(LOGTAG, "Got location change");
            mURLBar.setURL(url);
            mURLBar.setHint(R.string.search_placeholder);
            mReloadButton.setEnabled(true);
        }
        updateServoButton();
    }

    @Override
    public void onCanGoBack(GeckoSession aSession, boolean canGoBack) {
        if (mBackButton != null) {
            boolean enableBackButton = getSession().canGoBack();

            Log.d(LOGTAG, "Got onCanGoBack: " + (enableBackButton ? "true" : "false"));
            mBackButton.setEnabled(enableBackButton);
            mBackButton.setHovered(false);
            mBackButton.setClickable(enableBackButton);
        }
    }

    @Override
    public void onCanGoForward(GeckoSession aSession, boolean canGoForward) {
        if (mForwardButton != null) {
            Log.d(LOGTAG, "Got onCanGoForward: " + (canGoForward ? "true" : "false"));
            mForwardButton.setEnabled(canGoForward);
            mForwardButton.setHovered(false);
            mForwardButton.setClickable(canGoForward);
        }
    }

    @Override
    public @Nullable GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession aSession, @NonNull LoadRequest aRequest) {
        final GeckoResult<AllowOrDeny> result = new GeckoResult<>();

        Uri uri = Uri.parse(aRequest.uri);
        if ("file".equalsIgnoreCase(uri.getScheme()) &&
                !mWidgetManager.isPermissionGranted(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            mWidgetManager.requestPermission(
                    aRequest.uri,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    new GeckoSession.PermissionDelegate.Callback() {
                        @Override
                        public void grant() {
                            result.complete(AllowOrDeny.ALLOW);
                        }

                        @Override
                        public void reject() {
                            result.complete(AllowOrDeny.DENY);
                        }
                    });
            return result;
        }

        result.complete(AllowOrDeny.ALLOW);
        return result;
    }

    // Progress Listener
    @Override
    public void onPageStart(GeckoSession aSession, String aUri) {
        if (mURLBar != null) {
            Log.d(LOGTAG, "Got onPageStart");
            mURLBar.setURL(aUri);
            mURLBar.setHint(R.string.search_placeholder);
        }
        mIsLoading = true;
        mURLBar.setIsLoading(true);
        if (mReloadButton != null) {
            mReloadButton.setImageResource(R.drawable.ic_icon_exit);
            mReloadButton.setTooltip(getResources().getString(R.string.stop_tooltip));
        }
    }

    @Override
    public void onPageStop(GeckoSession aSession, boolean b) {
        mIsLoading = false;
        mURLBar.setIsLoading(false);
        if (mReloadButton != null) {
            mReloadButton.setImageResource(R.drawable.ic_icon_reload);
            mReloadButton.setTooltip(getResources().getString(R.string.refresh_tooltip));
        }
    }

    @Override
    public void onSecurityChange(GeckoSession geckoSession, SecurityInformation securityInformation) {
        if (mURLBar != null) {
            boolean isSecure = securityInformation.isSecure;
            mURLBar.setIsInsecure(!isSecure);
        }
    }

    // Content delegate

    @Override
    public void onFullScreen(GeckoSession session, boolean aFullScreen) {
        if (aFullScreen) {
            if (!mAttachedWindow.isFullScreen()) {
                enterFullScreenMode();
            }
            if (mAttachedWindow.isResizing()) {
                exitResizeMode(ResizeAction.KEEP_SIZE);
            }
            AtomicBoolean autoEnter = new AtomicBoolean(false);
            mAutoSelectedProjection = VideoProjectionMenuWidget.getAutomaticProjection(getSession().getCurrentUri(), autoEnter);
            if (mAutoSelectedProjection != null && autoEnter.get()) {
                mAutoEnteredVRVideo = true;
                postDelayed(() -> enterVRVideo(mAutoSelectedProjection), 300);
            } else {
                mAutoEnteredVRVideo = false;
            }
        } else {
            if (mIsInVRVideo) {
                exitVRVideo();
            }
            exitFullScreenMode();
        }
    }

    // WidgetManagerDelegate.UpdateListener
    @Override
    public void onWidgetUpdate(Widget aWidget) {
        if (aWidget == mAttachedWindow && !mAttachedWindow.isResizing()) {
            handleWindowResize();
        }
    }

    private void handleWindowResize() {
        // Browser window may have been resized, adjust the navigation bar
        float targetWidth = mAttachedWindow.getPlacement().worldWidth;
        float defaultWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width);
        float maxWidth = defaultWidth * 1.5f;
        float minWidth = defaultWidth * 0.5f;
        targetWidth = Math.max(targetWidth, minWidth);
        targetWidth = Math.min(targetWidth, maxWidth);

        float ratio = targetWidth / defaultWidth;
        mWidgetPlacement.worldWidth = targetWidth;
        mWidgetPlacement.width = (int) (WidgetPlacement.dpDimension(getContext(), R.dimen.navigation_bar_width) * ratio);
        mWidgetManager.updateWidget(this);
        postInvalidate();
    }

    // Session.SessionChangeListener

    @Override
    public void onCurrentSessionChange(GeckoSession aOldSession, GeckoSession aSession) {
        handleSessionState();

        boolean isFullScreen = getSession().isInFullScreen();
        if (isFullScreen && !mAttachedWindow.isFullScreen()) {
            enterFullScreenMode();
        } else if (!isFullScreen && mAttachedWindow.isFullScreen()) {
            exitVRVideo();
            exitFullScreenMode();
        }
    }

    // NavigationURLBarDelegate

    @Override
    public void OnVoiceSearchClicked() {
        if (mVoiceSearchWidget.isVisible()) {
            mVoiceSearchWidget.hide(REMOVE_WIDGET);

        } else {
            mVoiceSearchWidget.getPlacement().parentHandle = mAttachedWindow.getHandle();
            mVoiceSearchWidget.show(REQUEST_FOCUS);
        }
    }


    @Override
    public void OnShowSearchPopup() {
        if (mPopup == null) {
            mPopup = createChild(SuggestionsWidget.class);
            mPopup.setURLBarPopupDelegate(this);
        }

        final String text = mURLBar.getText().trim();
        final String originalText = mURLBar.getOriginalText().trim();
        if (originalText.length() <= 0) {
            mPopup.hide(UIWidget.KEEP_WIDGET);
            return;
        }

        mSuggestionsProvider.setText(text);
        mSuggestionsProvider.setFilterText(originalText);
        mSuggestionsProvider.getSuggestions()
                .whenCompleteAsync((items, ex) -> {
                    mPopup.updateItems(items);
                    mPopup.setHighlightedText(originalText);

                    if (!mPopup.isVisible()) {
                        mPopup.updatePlacement((int)WidgetPlacement.convertPixelsToDp(getContext(), mURLBar.getWidth()));
                        mPopup.show(CLEAR_FOCUS);
                    }

                }, new UIThreadExecutor()).exceptionally(th -> {
                    Log.d(LOGTAG, "Error getting suggestions: " + th.getLocalizedMessage());
                    return null;
        });
    }

    @Override
    public void onHideSearchPopup() {
        if (mPopup != null) {
            mPopup.hide(UIWidget.KEEP_WIDGET);
        }
    }

    // VoiceSearch Delegate

    @Override
    public void OnVoiceSearchResult(String transcription, float confidance) {
        mURLBar.handleURLEdit(transcription);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == mAppContext.getString(R.string.settings_key_servo)) {
            updateServoButton();

        } else if (key == mAppContext.getString(R.string.settings_key_user_agent_version)) {
            mURLBar.setUAMode(SettingsStore.getInstance(getContext()).getUaMode());
        }
    }

    // WorldClickListener
    @Override
    public void onWorldClick() {
        if (mIsInVRVideo && mMediaControlsWidget != null) {
            mMediaControlsWidget.setVisible(!mMediaControlsWidget.isVisible());
            if (mProjectionMenu.getSelectedProjection() != VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE) {
                if (mMediaControlsWidget.isVisible()) {
                    // Reorient the MediaControl UI when the users clicks to show it.
                    // So you can look at any point of the 180/360 video and the UI always shows in front of you.
                    mWidgetManager.resetUIYaw();
                }
            }

            if (mMediaControlsWidget.isVisible()) {
                mWidgetManager.setControllersVisible(true);
            } else if (mProjectionMenu.getSelectedProjection() != VideoProjectionMenuWidget.VIDEO_PROJECTION_3D_SIDE_BY_SIDE) {
                mWidgetManager.setControllersVisible(false);
            }
        }
        closeFloatingMenus();
    }

    // URLBarPopupWidgetDelegate

    @Override
    public void OnItemClicked(SuggestionsWidget.SuggestionItem item) {
        mURLBar.handleURLEdit(item.url);
    }

    // BookmarksViewListener

    @Override
    public void onBookmarksShown(WindowWidget aWindow) {
        if (mAttachedWindow == aWindow) {
            mURLBar.setURL("");
            mURLBar.setHint(R.string.url_bookmarks_title);
            mURLBar.setIsContentMode(true);
        }
    }

    @Override
    public void onBookmarksHidden(WindowWidget aWindow) {
        if (mAttachedWindow == aWindow) {
            mURLBar.setIsContentMode(false);
            mURLBar.setURL(getSession().getCurrentUri());
            mURLBar.setHint(R.string.search_placeholder);
        }
    }

    // HistoryViewListener

    @Override
    public void onHistoryViewShown(WindowWidget aWindow) {
        if (mAttachedWindow == aWindow) {
            mURLBar.setURL("");
            mURLBar.setHint(R.string.url_history_title);
            mURLBar.setIsContentMode(true);
        }
    }

    @Override
    public void onHistoryViewHidden(WindowWidget aWindow) {
        if (mAttachedWindow == aWindow) {
            mURLBar.setIsContentMode(false);
            mURLBar.setURL(getSession().getCurrentUri());
            mURLBar.setHint(R.string.search_placeholder);
        }
    }

    // TrayListener

    @Override
    public void onBookmarksClicked() {
        if (mAttachedWindow.isResizing()) {
            exitResizeMode(ResizeAction.RESTORE_SIZE);

        } else if (mAttachedWindow.isFullScreen()) {
            exitFullScreenMode();

        } else if (mIsInVRVideo) {
            exitVRVideo();
        }
    }

    @Override
    public void onPrivateBrowsingClicked() {

    }

    @Override
    public void onHistoryClicked() {
        if (mAttachedWindow.isResizing()) {
            exitResizeMode(ResizeAction.RESTORE_SIZE);

        } else if (mAttachedWindow.isFullScreen()) {
            exitFullScreenMode();

        } else if (mIsInVRVideo) {
            exitVRVideo();
        }
    }

    private void finishWidgetResize() {
        mWidgetManager.finishWidgetResize(mAttachedWindow);
    }

    private void startWidgetResize() {
        if (mAttachedWindow != null) {
            Pair<Float, Float> maxSize = mAttachedWindow.getSizeForScale(mAttachedWindow.getMaxWindowScale());
            Pair<Float, Float> minSize = mAttachedWindow.getSizeForScale(0.5f);
            mWidgetManager.startWidgetResize(mAttachedWindow, maxSize.first, 4.5f, minSize.first, minSize.second);
        }
    }

    private void updateWidget() {
        onWidgetUpdate(mAttachedWindow);
    }

}

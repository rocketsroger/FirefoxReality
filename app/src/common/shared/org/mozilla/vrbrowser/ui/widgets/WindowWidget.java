/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import org.jetbrains.annotations.NotNull;
import org.mozilla.gecko.util.ThreadUtils;
import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.PanZoomController;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.browser.HistoryStore;
import org.mozilla.vrbrowser.browser.SessionChangeListener;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.VideoAvailabilityListener;
import org.mozilla.vrbrowser.browser.engine.Session;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.telemetry.TelemetryWrapper;
import org.mozilla.vrbrowser.ui.callbacks.BookmarksCallback;
import org.mozilla.vrbrowser.ui.callbacks.HistoryCallback;
import org.mozilla.vrbrowser.ui.callbacks.LibraryItemContextMenuClickCallback;
import org.mozilla.vrbrowser.ui.views.BookmarksView;
import org.mozilla.vrbrowser.ui.views.HistoryView;
import org.mozilla.vrbrowser.ui.views.LibraryItemContextMenu;
import org.mozilla.vrbrowser.ui.widgets.dialogs.BaseAppDialogWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.ClearCacheDialogWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.ContextMenuWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.LibraryItemContextMenuWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.MaxWindowsWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.MessageDialogWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.AlertPromptWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.AuthPromptWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.ChoicePromptWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.ConfirmPromptWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.PromptWidget;
import org.mozilla.vrbrowser.ui.widgets.prompts.TextPromptWidget;
import org.mozilla.vrbrowser.utils.ViewUtils;
import org.mozilla.vrbrowser.utils.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;

import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.storage.PageObservation;
import mozilla.components.concept.storage.VisitInfo;
import mozilla.components.concept.storage.VisitType;

import static org.mozilla.vrbrowser.utils.ServoUtils.isInstanceOfServoSession;

public class WindowWidget extends UIWidget implements SessionChangeListener,
        GeckoSession.ContentDelegate, GeckoSession.PromptDelegate,
        GeckoSession.NavigationDelegate, VideoAvailabilityListener,
        GeckoSession.HistoryDelegate, GeckoSession.ProgressDelegate,
        TabsWidget.TabDelegate {

    public interface HistoryViewDelegate {
        default void onHistoryViewShown(WindowWidget aWindow) {}
        default void onHistoryViewHidden(WindowWidget aWindow) {}
    }

    public interface BookmarksViewDelegate {
        default void onBookmarksShown(WindowWidget aWindow) {}
        default void onBookmarksHidden(WindowWidget aWindow) {}
    }

    private GeckoDisplay mDisplay;
    private Surface mSurface;
    private int mWidth;
    private int mHeight;
    private int mHandle;
    private WidgetPlacement mWidgetPlacement;
    private TopBarWidget mTopBar;
    private TitleBarWidget mTitleBar;
    private WidgetManagerDelegate mWidgetManager;
    private ChoicePromptWidget mChoicePrompt;
    private AlertPromptWidget mAlertPrompt;
    private MaxWindowsWidget mMaxWindowsDialog;
    private ConfirmPromptWidget mConfirmPrompt;
    private TextPromptWidget mTextPrompt;
    private AuthPromptWidget mAuthPrompt;
    private NoInternetWidget mNoInternetToast;
    private MessageDialogWidget mAppDialog;
    private ClearCacheDialogWidget mClearCacheDialog;
    private ContextMenuWidget mContextMenu;
    private LibraryItemContextMenuWidget mLibraryItemContextMenu;
    private int mWidthBackup;
    private int mHeightBackup;
    private int mBorderWidth;
    private Runnable mFirstDrawCallback;
    private boolean mIsInVRVideoMode;
    private View mView;
    private Point mLastMouseClickPos;
    private Session mSession;
    private int mWindowId;
    private BookmarksView mBookmarksView;
    private HistoryView mHistoryView;
    private ArrayList<BookmarksViewDelegate> mBookmarksViewListeners;
    private ArrayList<HistoryViewDelegate> mHistoryViewListeners;
    private Windows.WindowPlacement mWindowPlacement = Windows.WindowPlacement.FRONT;
    private Windows.WindowPlacement mWindowPlacementBeforeFullscreen = Windows.WindowPlacement.FRONT;
    private float mMaxWindowScale = 3;
    private boolean mIsRestored = false;
    private ArrayList<WindowListener> mListeners;
    boolean mActive = false;
    boolean mHovered = false;
    boolean mClickedAfterFocus = false;
    boolean mIsBookmarksVisible = false;
    boolean mIsHistoryVisible = false;
    private WidgetPlacement mPlacementBeforeFullscreen;
    private WidgetPlacement mPlacementBeforeResize;
    private boolean mIsResizing;
    private boolean mIsFullScreen;
    private boolean mAfterFirstPaint;
    private TabsWidget mTabsWidget;

    public interface WindowListener {
        default void onFocusRequest(@NonNull WindowWidget aWindow) {}
        default void onBorderChanged(@NonNull WindowWidget aWindow) {}
        default void onTabSelect(@NonNull WindowWidget aWindow, Session aTab) {}
        default void onTabAdd(@NonNull WindowWidget aWindow) {}
        default void onTabsClose(@NonNull WindowWidget aWindow, ArrayList<Session> aTabs) {}
        default void onSessionChanged(@NonNull Session aOldSession, @NonNull Session aSession) {}
    }

    public WindowWidget(Context aContext, int windowId, boolean privateMode)  {
        super(aContext);
        mWindowId = windowId;
        mSession = SessionStore.get().createSession(privateMode);
        initialize(aContext);
    }

    public WindowWidget(Context aContext, int windowId, Session aSession)  {
        super(aContext);
        mWindowId = windowId;
        mSession = aSession;
        initialize(aContext);
    }

    private void initialize(Context aContext) {
        mWidgetManager = (WidgetManagerDelegate) aContext;
        mBorderWidth = SettingsStore.getInstance(aContext).getTransparentBorderWidth();
        mListeners = new ArrayList<>();

        setupListeners(mSession);

        mBookmarksView  = new BookmarksView(aContext);
        mBookmarksView.setBookmarksCallback(mBookmarksCallback);
        mBookmarksViewListeners = new ArrayList<>();

        mHistoryView = new HistoryView(aContext);
        mHistoryView.setHistoryCallback(mHistoryCallback);
        mHistoryViewListeners = new ArrayList<>();

        mHandle = ((WidgetManagerDelegate)aContext).newWidgetHandle();
        mWidgetPlacement = new WidgetPlacement(aContext);
        mPlacementBeforeFullscreen = new WidgetPlacement(aContext);
        mPlacementBeforeResize = new WidgetPlacement(aContext);
        mIsResizing = false;
        mIsFullScreen = false;
        initializeWidgetPlacement(mWidgetPlacement);
        if (mSession.isPrivateMode()) {
            mWidgetPlacement.clearColor = ViewUtils.ARGBtoRGBA(getContext().getColor(R.color.window_private_clear_color));
        } else {
            mWidgetPlacement.clearColor = ViewUtils.ARGBtoRGBA(getContext().getColor(R.color.window_blank_clear_color));
        }

        mTopBar = new TopBarWidget(aContext);
        mTopBar.attachToWindow(this);
        mLastMouseClickPos = new Point(0, 0);

        mTitleBar = new TitleBarWidget(aContext);
        mTitleBar.attachToWindow(this);

        setFocusable(true);

        TelemetryWrapper.openWindowEvent(mWindowId);

        if (mSession.getGeckoSession() != null) {
            onCurrentSessionChange(null, mSession.getGeckoSession());
        }
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        int windowWidth = SettingsStore.getInstance(getContext()).getWindowWidth();
        aPlacement.width = windowWidth + mBorderWidth * 2;
        aPlacement.height = SettingsStore.getInstance(getContext()).getWindowHeight() + mBorderWidth * 2;
        aPlacement.worldWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width) *
                                (float)windowWidth / (float)SettingsStore.WINDOW_WIDTH_DEFAULT;
        aPlacement.density = 1.0f;
        aPlacement.visible = true;
        aPlacement.cylinder = true;
        aPlacement.textureScale = 1.0f;
        aPlacement.name = "Window";
        // Check Windows.placeWindow method for remaining placement set-up
    }

    void setupListeners(Session aSession) {
        aSession.setPromptDelegate(this);
        aSession.addSessionChangeListener(this);
        aSession.addContentListener(this);
        aSession.addVideoAvailabilityListener(this);
        aSession.addNavigationListener(this);
        aSession.addProgressListener(this);
        aSession.setHistoryDelegate(this);
    }

    void cleanListeners(Session aSession) {
        aSession.setPromptDelegate(null);
        aSession.removeSessionChangeListener(this);
        aSession.removeContentListener(this);
        aSession.removeVideoAvailabilityListener(this);
        aSession.removeNavigationListener(this);
        aSession.removeProgressListener(this);
        aSession.setHistoryDelegate(null);
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        if (!mWidgetPlacement.visible) {
            mWidgetPlacement.visible = true;
        }

        mWidgetManager.updateWidget(this);

        setFocusableInTouchMode(false);
        if (aShowFlags == REQUEST_FOCUS) {
            requestFocusFromTouch();

        } else {
            clearFocus();
        }

        mSession.setActive(true);
    }

    @Override
    public void hide(@HideFlags int aHideFlag) {
        if (mWidgetPlacement.visible) {
            mWidgetPlacement.visible = false;
        }

        mWidgetManager.updateWidget(this);

        clearFocus();

        mSession.setActive(false);
    }

    @Override
    protected void onDismiss() {
        if (isBookmarksVisible()) {
            hideBookmarks();

        } else if (isHistoryVisible()) {
            hideHistory();

        } else {
            if (mSession.canGoBack()) {
                mSession.goBack();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mSession.setActive(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isVisible() || mIsInVRVideoMode) {
            mSession.setActive(true);
        }
    }

    public void close() {
        TelemetryWrapper.closeWindowEvent(mWindowId);

        releaseWidget();
        mBookmarksView.onDestroy();
        mHistoryView.onDestroy();
        SessionStore.get().destroySession(mSession);
        if (mTopBar != null) {
            mWidgetManager.removeWidget(mTopBar);
        }
        if (mTitleBar != null) {
            mWidgetManager.removeWidget(mTitleBar);
        }
        if (mTabsWidget != null) {
            if (!mTabsWidget.isReleased()) {
                mTabsWidget.releaseWidget();
            }
            mTabsWidget = null;
        }
        mListeners.clear();
    }

    public void loadHomeIfNotRestored() {
        if (!mIsRestored) {
            loadHome();
        }
    }

    public void loadHome() {
        if (mSession.isPrivateMode()) {
            mSession.loadPrivateBrowsingPage();

        } else {
            mSession.loadUri(SettingsStore.getInstance(getContext()).getHomepage());
        }
    }

    protected void setRestored(boolean restored) {
        mIsRestored = restored;
    }

    private void setView(View view, boolean switchSurface) {
        if (switchSurface) {
            pauseCompositor();
        }

        mView = view;
        removeView(view);
        mView.setVisibility(VISIBLE);
        addView(mView);

        if (switchSurface) {
            mWidgetPlacement.density = getContext().getResources().getDisplayMetrics().density;
            if (mTexture != null && mSurface != null && mRenderer == null) {
                // Create the UI Renderer for the current surface.
                // Surface must be released when switching back to WebView surface or the browser
                // will not render it correctly. See release code in unsetView().
                mRenderer = new UISurfaceTextureRenderer(mSurface, mWidgetPlacement.textureWidth(), mWidgetPlacement.textureHeight());
            }
            mWidgetManager.updateWidget(this);
            mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);
            mWidgetManager.pushBackHandler(mBackHandler);
            setWillNotDraw(false);
            postInvalidate();
        }
    }

    private void unsetView(View view, boolean switchSurface) {
        if (mView != null && mView == view) {
            mView = null;
            removeView(view);
            view.setVisibility(GONE);

            if (switchSurface) {
                setWillNotDraw(true);
                if (mTexture != null) {
                    // Surface must be recreated here when not using layers.
                    // When using layers the new Surface is received via the setSurface() method.
                    if (mRenderer != null) {
                        mRenderer.release();
                        mRenderer = null;
                    }
                    mSurface = new Surface(mTexture);
                }
                mWidgetPlacement.density = 1.0f;
                mWidgetManager.updateWidget(this);
                mWidgetManager.popWorldBrightness(this);
                mWidgetManager.popBackHandler(mBackHandler);
            }
        }
    }

    public boolean isBookmarksVisible() {
        return (mView != null && mView == mBookmarksView);
    }

    public boolean isHistoryVisible() {
        return (mView != null && mView == mHistoryView);
    }

    public void addBookmarksViewListener(@NonNull BookmarksViewDelegate listener) {
        mBookmarksViewListeners.add(listener);
    }

    public void removeBookmarksViewListener(@NonNull BookmarksViewDelegate listener) {
        mBookmarksViewListeners.remove(listener);
    }

    public void addHistoryViewListener(@NonNull HistoryViewDelegate listener) {
        mHistoryViewListeners.add(listener);
    }

    public void removeHistoryViewListener(@NonNull HistoryViewDelegate listener) {
        mHistoryViewListeners.remove(listener);
    }

    public void switchBookmarks() {
        if (isHistoryVisible()) {
            hideHistory(false);
            showBookmarks(false);

        } else if (isBookmarksVisible()) {
            hideBookmarks();

        } else {
            showBookmarks();
        }
    }

    public void showBookmarks() {
        showBookmarks(true);
    }

    public void showBookmarks(boolean switchSurface) {
        if (mView == null) {
            setView(mBookmarksView, switchSurface);
            for (BookmarksViewDelegate listener : mBookmarksViewListeners) {
                listener.onBookmarksShown(this);
            }
            mIsBookmarksVisible = true;
        }

        updateTitleBar();
    }

    public void hideBookmarks() {
        hideBookmarks(true);
    }

    public void hideBookmarks(boolean switchSurface) {
        if (mView != null) {
            unsetView(mBookmarksView, switchSurface);
            for (BookmarksViewDelegate listener : mBookmarksViewListeners) {
                listener.onBookmarksHidden(this);
            }
            mIsBookmarksVisible = false;
        }
    }

    public void switchHistory() {
        if (isBookmarksVisible()) {
            hideBookmarks(false);
            showHistory(false);

        } else if (isHistoryVisible()) {
            hideHistory();

        } else {
            showHistory();
        }
    }

    public void showHistory() {
        showHistory(true);
    }

    public void showHistory(boolean switchSurface) {
        if (mView == null) {
            setView(mHistoryView, switchSurface);
            for (HistoryViewDelegate listener : mHistoryViewListeners) {
                listener.onHistoryViewShown(this);
            }
            mIsHistoryVisible = true;
        }
    }

    public void hideHistory() {
        hideHistory(true);
    }

    public void hideHistory(boolean switchSurface) {
        if (mView != null) {
            unsetView(mHistoryView, switchSurface);
            for (HistoryViewDelegate listener : mHistoryViewListeners) {
                listener.onHistoryViewHidden(this);
            }
            mIsHistoryVisible = false;
        }
    }

    public void pauseCompositor() {
        if (mDisplay == null) {
            return;
        }

        mDisplay.surfaceDestroyed();
    }

    public void resumeCompositor() {
        if (mDisplay == null) {
            return;
        }
        if (mSurface == null) {
            return;
        }

        callSurfaceChanged();
    }

    public void enableVRVideoMode(int aVideoWidth, int aVideoHeight, boolean aResetBorder) {
        if (!mIsInVRVideoMode) {
            mWidthBackup = mWidth;
            mHeightBackup = mHeight;
            mIsInVRVideoMode = true;
        }
        boolean borderChanged = aResetBorder && mBorderWidth > 0;
        if (aVideoWidth == mWidth && aVideoHeight == mHeight && !borderChanged) {
            return;
        }
        if (aResetBorder) {
            mBorderWidth = 0;
        }
        mWidgetPlacement.width = aVideoWidth + mBorderWidth * 2;
        mWidgetPlacement.height = aVideoHeight + mBorderWidth * 2;
        mWidgetManager.updateWidget(this);
    }

    public void disableVRVideoMode() {
        if (!mIsInVRVideoMode || mWidthBackup == 0 || mHeightBackup == 0) {
            return;
        }
        mIsInVRVideoMode = false;
        int border = SettingsStore.getInstance(getContext()).getTransparentBorderWidth();
        if (mWidthBackup == mWidth && mHeightBackup == mHeight && border == mBorderWidth) {
            return;
        }
        mBorderWidth = border;
        mWidgetPlacement.width = mWidthBackup;
        mWidgetPlacement.height = mHeightBackup;
        mWidgetManager.updateWidget(this);
    }

    public void setWindowPlacement(@NonNull Windows.WindowPlacement aPlacement) {
        if (mActive) {
            TelemetryWrapper.activePlacementEvent(mWindowPlacement.getValue(), false);
        }

        mWindowPlacement = aPlacement;

        if (mActive) {
            TelemetryWrapper.activePlacementEvent(mWindowPlacement.getValue(), true);
        }
    }

    public @NonNull Windows.WindowPlacement getmWindowPlacementBeforeFullscreen() {
        return mWindowPlacementBeforeFullscreen;
    }

    public @NonNull Windows.WindowPlacement getWindowPlacement() {
        return mWindowPlacement;
    }

    @Override
    public void resizeByMultiplier(float aspect, float multiplier) {
        Pair<Float, Float> targetSize = getSizeForScale(multiplier, aspect);
        handleResizeEvent(targetSize.first, targetSize.second);
    }

    public float getCurrentScale() {
        float currentAspect = getCurrentAspect();
        float currentWorldHeight = mWidgetPlacement.worldWidth / currentAspect;
        float currentArea = mWidgetPlacement.worldWidth * currentWorldHeight;
        float defaultWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width);
        float defaultHeight = defaultWidth / SettingsStore.getInstance(getContext()).getWindowAspect();
        float defaultArea = defaultWidth * defaultHeight;
        return currentArea / defaultArea;
    }

    public float getCurrentAspect() {
        return (float) mWidgetPlacement.width / (float) mWidgetPlacement.height;
    }

    public int getBorderWidth() {
        return mBorderWidth;
    }

    public void setActiveWindow(boolean active) {
        mActive = active;
        if (active) {
            SessionStore.get().setActiveStore(mSession);
            GeckoSession session = mSession.getGeckoSession();
            if (session != null) {
                session.getTextInput().setView(this);
            }
        } else {
            updateTitleBar();
        }

        hideContextMenus();

        TelemetryWrapper.activePlacementEvent(mWindowPlacement.getValue(), mActive);
        updateBorder();
    }

    private void hideContextMenus() {
        if (mContextMenu != null && mContextMenu.isVisible()) {
            mContextMenu.hide(REMOVE_WIDGET);
        }
        if (mLibraryItemContextMenu != null && mLibraryItemContextMenu.isVisible()) {
            mLibraryItemContextMenu.hide(REMOVE_WIDGET);
        }
    }

    private void updateTitleBar() {
        if (isBookmarksVisible()) {
            updateTitleBarUrl(getResources().getString(R.string.url_bookmarks_title));

        } else if (isHistoryVisible()) {
                updateTitleBarUrl(getResources().getString(R.string.url_history_title));

        } else {
            updateTitleBarUrl(mSession.getCurrentUri());
        }
    }

    private void updateTitleBarUrl(String url) {
        if (mTitleBar != null && url != null) {
            mTitleBar.setIsInsecure(!mSession.isSecure());
            if (url.startsWith("data") && mSession.isPrivateMode()) {
                mTitleBar.setInsecureVisibility(GONE);
                mTitleBar.setURL(getResources().getString(R.string.private_browsing_title));

            } else if (url.equals(mSession.getHomeUri())) {
                mTitleBar.setInsecureVisibility(GONE);
                mTitleBar.setURL(getResources().getString(R.string.url_home_title, getResources().getString(R.string.app_name)));

            } else if (url.equals(getResources().getString(R.string.url_bookmarks_title)) ||
                    url.equals(getResources().getString(R.string.url_history_title))) {
                mTitleBar.setInsecureVisibility(GONE);
                mTitleBar.setURL(url);

            } else if (url.equals(getResources().getString(R.string.about_blank))) {
                mTitleBar.setInsecureVisibility(GONE);
                mTitleBar.setURL("");

            } else {
                mTitleBar.setURL(url);
            }
        }
    }

    public Session getSession() {
        return mSession;
    }

    public TopBarWidget getTopBar() {
        return mTopBar;
    }

    public void setTopBar(TopBarWidget aWidget) {
        if (mTopBar != aWidget) {
            mTopBar = aWidget;
            mTopBar.attachToWindow(this);
        }
    }

    public TitleBarWidget getTitleBar() {
        return mTitleBar;
    }

    @Override
    public void setSurfaceTexture(SurfaceTexture aTexture, final int aWidth, final int aHeight, Runnable aFirstDrawCallback) {
        mFirstDrawCallback = aFirstDrawCallback;
        if (mView != null) {
            super.setSurfaceTexture(aTexture, aWidth, aHeight, aFirstDrawCallback);

        } else {
            GeckoSession session = mSession.getGeckoSession();
            if (session == null) {
                return;
            }
            if (aTexture == null) {
                setWillNotDraw(true);
                return;
            }
            mWidth = aWidth;
            mHeight = aHeight;
            mTexture = aTexture;
            aTexture.setDefaultBufferSize(aWidth, aHeight);
            mSurface = new Surface(aTexture);
            if (mDisplay == null) {
                Log.e("VRB", "makelele acquireDisplay1");
                mDisplay = session.acquireDisplay();
            } else {
                Log.e(LOGTAG, "GeckoDisplay was not null in BrowserWidget.setSurfaceTexture()");
            }
            callSurfaceChanged();
        }
    }

    @Override
    public void setSurface(Surface aSurface, final int aWidth, final int aHeight, Runnable aFirstDrawCallback) {
        if (mView != null) {
            super.setSurface(aSurface, aWidth, aHeight, aFirstDrawCallback);

        } else {
            GeckoSession session = mSession.getGeckoSession();
            if (session == null) {
                return;
            }
            mWidth = aWidth;
            mHeight = aHeight;
            mSurface = aSurface;
            mFirstDrawCallback = aFirstDrawCallback;
            if (mDisplay == null) {
                Log.e("VRB", "makelele acquireDisplay1");
                mDisplay = session.acquireDisplay();
            } else {
                Log.e(LOGTAG, "GeckoDisplay was not null in BrowserWidget.setSurfaceTexture()");
            }
            if (mSurface != null) {
                callSurfaceChanged();
            } else {
                mDisplay.surfaceDestroyed();
            }
        }
    }

    private void callSurfaceChanged() {
        if (mDisplay != null) {
            mDisplay.surfaceChanged(mSurface, mBorderWidth, mBorderWidth, mWidth - mBorderWidth * 2, mHeight - mBorderWidth * 2);
        }
        if (mSession != null) {
            mSession.updateLastUse();
        }
    }

    @Override
    public void resizeSurface(final int aWidth, final int aHeight) {
        if (mView != null) {
            super.resizeSurface(aWidth, aHeight);
        }

        mWidth = aWidth;
        mHeight = aHeight;
        if (mTexture != null) {
            mTexture.setDefaultBufferSize(aWidth, aHeight);
        }

        if (mSurface != null && mView == null) {
            callSurfaceChanged();
        }
    }

    @Override
    public int getHandle() {
        return mHandle;
    }

    @Override
    public WidgetPlacement getPlacement() {
        return mWidgetPlacement;
    }

    @Override
    public void handleTouchEvent(MotionEvent aEvent) {
        mLastMouseClickPos = new Point((int)aEvent.getX(), (int)aEvent.getY());
        if (aEvent.getAction() == MotionEvent.ACTION_DOWN) {
            if (!mActive) {
                mClickedAfterFocus = true;
                updateBorder();
                // Focus this window
                for (WindowListener listener: mListeners) {
                    listener.onFocusRequest(this);
                }
                // Return to discard first click after focus
                return;
            }
        } else if (aEvent.getAction() == MotionEvent.ACTION_UP || aEvent.getAction() == MotionEvent.ACTION_CANCEL) {
            mClickedAfterFocus = false;
            updateBorder();
        }

        if (!mActive) {
            // Do not send touch events to not focused windows.
            return;
        }

        if (mView != null) {
            super.handleTouchEvent(aEvent);

        } else {
            if (aEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                requestFocus();
                requestFocusFromTouch();
            }
            GeckoSession session = mSession.getGeckoSession();
            if (session != null) {
                session.getPanZoomController().onTouchEvent(aEvent);
            }
        }
    }

    @Override
    public void handleHoverEvent(MotionEvent aEvent) {
        if (aEvent.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            mHovered = true;
            updateBorder();
        } else if (aEvent.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
            mHovered = false;
            updateBorder();
        }

        if (!mActive) {
            // Do not send touch events to not focused windows.
            return;
        }

        if (mView != null) {
            super.handleHoverEvent(aEvent);

        } else {
            GeckoSession session = mSession.getGeckoSession();
            if (session != null) {
                session.getPanZoomController().onMotionEvent(aEvent);
            }
        }
    }

    protected void updateBorder() {
        int color = 0;
        if (!mActive && !mClickedAfterFocus && mHovered) {
            color = ViewUtils.ARGBtoRGBA(getContext().getColor(R.color.window_border_hover));
        } else if (mClickedAfterFocus) {
            color = ViewUtils.ARGBtoRGBA(getContext().getColor(R.color.window_border_click));
        }
        if (mWidgetPlacement.borderColor != color) {
            mWidgetPlacement.borderColor = color;
            mWidgetManager.updateWidget(this);
            for (WindowListener listener: mListeners) {
                listener.onBorderChanged(this);
            }
        }
    }

    public void saveBeforeFullscreenPlacement() {
        mWindowPlacementBeforeFullscreen = mWindowPlacement;
        mPlacementBeforeFullscreen.copyFrom(mWidgetPlacement);
    }

    public void restoreBeforeFullscreenPlacement() {
        mWindowPlacement = mWindowPlacementBeforeFullscreen;
        mWidgetPlacement.copyFrom(mPlacementBeforeFullscreen);
    }

    public WidgetPlacement getBeforeFullscreenPlacement() {
        return mPlacementBeforeFullscreen;
    }

    public void saveBeforeResizePlacement() {
        mPlacementBeforeResize.copyFrom(mWidgetPlacement);
    }

    public void restoreBeforeResizePlacement() {
        mWidgetPlacement.copyFrom(mPlacementBeforeResize);
    }

    public WidgetPlacement getBeforeResizePlacement() {
        return mPlacementBeforeResize;
    }

    public void setIsResizing(boolean isResizing) {
        mIsResizing = isResizing;
    }

    public boolean isResizing() {
        return mIsResizing;
    }

    public void setIsFullScreen(boolean isFullScreen) {
        mIsFullScreen = isFullScreen;
    }

    public boolean isFullScreen() {
        return mIsFullScreen;
    }

    public void addWindowListener(WindowListener aListener) {
       if (!mListeners.contains(aListener)) {
           mListeners.add(aListener);
       }
    }

    public void removeWindowListener(WindowListener aListener) {
        mListeners.remove(aListener);
    }

    @Override
    public void handleResizeEvent(float aWorldWidth, float aWorldHeight) {
        int width = getWindowWidth(aWorldWidth);
        float aspect = aWorldWidth / aWorldHeight;
        int height = (int) Math.floor((float)width / aspect);
        mWidgetPlacement.width = width + mBorderWidth * 2;
        mWidgetPlacement.height = height + mBorderWidth * 2;
        mWidgetPlacement.worldWidth = aWorldWidth;
        mWidgetManager.updateWidget(this);
        mWidgetManager.updateVisibleWidgets();
    }

    @Override
    public void releaseWidget() {
        cleanListeners(mSession);
        GeckoSession session = mSession.getGeckoSession();
        if (mDisplay != null) {
            mDisplay.surfaceDestroyed();
            if (session != null) {
                session.releaseDisplay(mDisplay);
            }
            mDisplay = null;
        }
        if (session != null) {
            session.getTextInput().setView(null);
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mTexture != null && mRenderer == null) {
            // Custom SurfaceTexture used for GeckoView
            mTexture.release();
            mTexture = null;
        }
        super.releaseWidget();
    }


    @Override
    public void setComposited(final boolean aIsComposited) {
        mWidgetPlacement.composited = aIsComposited;
        if (!aIsComposited) {
            mAfterFirstPaint = false;
        }
    }

    public void setFirstDrawCallback(Runnable aRunnable) {
        mFirstDrawCallback = aRunnable;
    }

    @Override
    public boolean isComposited() {
        return mWidgetPlacement.composited;
    }

    @Override
    public boolean isVisible() {
        return mWidgetPlacement.visible;
    }

    @Override
    public boolean isLayer() {
        return mSurface != null && mTexture == null;
    }

    @Override
    public void setVisible(boolean aVisible) {
        if (mWidgetPlacement.visible == aVisible) {
            return;
        }
        if (!mIsInVRVideoMode) {
            mSession.setActive(aVisible);
        }
        mWidgetPlacement.visible = aVisible;
        if (!aVisible) {
            if (mIsBookmarksVisible || mIsHistoryVisible) {
                mWidgetManager.popWorldBrightness(this);
            }

        } else {
            if (mIsBookmarksVisible || mIsHistoryVisible) {
                mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);
            }
        }
        mIsBookmarksVisible = isBookmarksVisible();
        mIsHistoryVisible = isHistoryVisible();
        mWidgetManager.updateWidget(this);
        if (!aVisible) {
            clearFocus();
        }
    }

    @Override
    public void draw(Canvas aCanvas) {
        if (mView != null) {
            super.draw(aCanvas);
        }
    }

    public void setSession(@NonNull Session aSession) {
        if (mSession != aSession) {
            Session oldSession = mSession;
            if (oldSession != null) {
                cleanListeners(oldSession);
            }

            mSession = aSession;
            setupListeners(mSession);
            if (oldSession != null) {
                onCurrentSessionChange(oldSession.getGeckoSession(), aSession.getGeckoSession());
            } else {
                onCurrentSessionChange(null, aSession.getGeckoSession());
            }
            for (WindowListener listener: mListeners) {
                listener.onSessionChanged(oldSession, aSession);
            }
            if (mSession.getBitmap() == null) {
                captureImage();
            }
        }
    }

    public void releaseDisplay(GeckoSession aSession) {
        if (aSession != null && mDisplay != null) {
            Log.d(LOGTAG, "Detach from previous session: " + aSession.hashCode());
            aSession.getTextInput().setView(null);
            mDisplay.surfaceDestroyed();
            aSession.releaseDisplay(mDisplay);
            mDisplay = null;
        }
    }

    // Session.GeckoSessionChange
    @Override
    public void onCurrentSessionChange(GeckoSession aOldSession, GeckoSession aSession) {
        Log.d(LOGTAG, "onCurrentSessionChange: " + this.toString());

        releaseDisplay(aOldSession);
        mWidgetManager.setIsServoSession(isInstanceOfServoSession(aSession));

        Log.e("VRB", "makelele acquireDisplay3");
        mDisplay = aSession.acquireDisplay();
        Log.d(LOGTAG, "surfaceChanged: " + aSession.hashCode());
        callSurfaceChanged();
        aSession.getTextInput().setView(this);

        boolean isPrivateMode  = aSession.getSettings().getUsePrivateMode();
        if (isPrivateMode) {
            setPrivateBrowsingEnabled(true);
        } else {
            setPrivateBrowsingEnabled(false);
        }
    }

    public void showTabsMenu() {
        if (mTabsWidget == null) {
            mTabsWidget = new TabsWidget(getContext(), mSession.isPrivateMode());
            mTabsWidget.getPlacement().parentHandle = mHandle;
            mTabsWidget.setTabDelegate(this);
        }
        if (mTabsWidget.isVisible()) {
            mTabsWidget.onDismiss();
        } else {
            mTabsWidget.show(REQUEST_FOCUS);
        }
    }

    // View
    @Override
    public InputConnection onCreateInputConnection(final EditorInfo outAttrs) {
        Log.d(LOGTAG, "BrowserWidget onCreateInputConnection");
        GeckoSession session = mSession.getGeckoSession();
        if (session == null) {
            return null;
        }
        return session.getTextInput().onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return mSession.isInputActive();
    }


    @Override
    public boolean onKeyPreIme(int aKeyCode, KeyEvent aEvent) {
        if (super.onKeyPreIme(aKeyCode, aEvent)) {
            return true;
        }
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getTextInput().onKeyPreIme(aKeyCode, aEvent);
    }

    @Override
    public boolean onKeyUp(int aKeyCode, KeyEvent aEvent) {
        if (super.onKeyUp(aKeyCode, aEvent)) {
            return true;
        }
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getTextInput().onKeyUp(aKeyCode, aEvent);
    }

    @Override
    public boolean onKeyDown(int aKeyCode, KeyEvent aEvent) {
        if (super.onKeyDown(aKeyCode, aEvent)) {
            return true;
        }
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getTextInput().onKeyDown(aKeyCode, aEvent);
    }

    @Override
    public boolean onKeyLongPress(int aKeyCode, KeyEvent aEvent) {
        if (super.onKeyLongPress(aKeyCode, aEvent)) {
            return true;
        }
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getTextInput().onKeyLongPress(aKeyCode, aEvent);
    }

    @Override
    public boolean onKeyMultiple(int aKeyCode, int repeatCount, KeyEvent aEvent) {
        if (super.onKeyMultiple(aKeyCode, repeatCount, aEvent)) {
            return true;
        }
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getTextInput().onKeyMultiple(aKeyCode, repeatCount, aEvent);
    }
    
    @Override
    protected void onFocusChanged(boolean aGainFocus, int aDirection, Rect aPreviouslyFocusedRect) {
        super.onFocusChanged(aGainFocus, aDirection, aPreviouslyFocusedRect);
        Log.d(LOGTAG, "BrowserWidget onFocusChanged: " + (aGainFocus ? "true" : "false"));
    }

    @Override
    public boolean onTouchEvent(MotionEvent aEvent) {
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getPanZoomController().onTouchEvent(aEvent) == PanZoomController.INPUT_RESULT_HANDLED;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent aEvent) {
        GeckoSession session = mSession.getGeckoSession();
        return (session != null) && session.getPanZoomController().onMotionEvent(aEvent) == PanZoomController.INPUT_RESULT_HANDLED;
    }

    private void setPrivateBrowsingEnabled(boolean isEnabled) {
    }

    public void setNoInternetToastVisible(boolean aVisible) {
        if (mNoInternetToast == null) {
            mNoInternetToast = new NoInternetWidget(getContext());
            mNoInternetToast.mWidgetPlacement.parentHandle = getHandle();
            mNoInternetToast.mWidgetPlacement.parentAnchorY = 0.0f;
            mNoInternetToast.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        }
        if (aVisible && !mNoInternetToast.isVisible()) {
            mNoInternetToast.show(REQUEST_FOCUS);
        } else if (!aVisible && mNoInternetToast.isVisible()) {
            mNoInternetToast.hide(REMOVE_WIDGET);
        }
    }

    public void showAlert(String title, @NonNull String msg, @NonNull PromptWidget.PromptDelegate callback) {
        mAlertPrompt = new AlertPromptWidget(getContext());
        mAlertPrompt.mWidgetPlacement.parentHandle = getHandle();
        mAlertPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mAlertPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mAlertPrompt.setTitle(title);
        mAlertPrompt.setMessage(msg);
        mAlertPrompt.setPromptDelegate(callback);
        mAlertPrompt.show(REQUEST_FOCUS);
    }

    public void showButtonPrompt(String title, @NonNull String msg, @NonNull String[] btnMsg, @NonNull ConfirmPromptWidget.ConfirmPromptDelegate callback) {
        mConfirmPrompt = new ConfirmPromptWidget(getContext());
        mConfirmPrompt.mWidgetPlacement.parentHandle = getHandle();
        mConfirmPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mConfirmPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mConfirmPrompt.setTitle(title);
        mConfirmPrompt.setMessage(msg);
        mConfirmPrompt.setButtons(btnMsg);
        mConfirmPrompt.setPromptDelegate(callback);
        mConfirmPrompt.show(REQUEST_FOCUS);
    }

    public void showAppDialog(@NonNull String title, @NonNull @StringRes int  description, @NonNull  @StringRes int [] btnMsg,
                              @NonNull BaseAppDialogWidget.Delegate buttonsCallback, @NonNull MessageDialogWidget.Delegate messageCallback) {
        mAppDialog = new MessageDialogWidget(getContext());
        mAppDialog.mWidgetPlacement.parentHandle = getHandle();
        mAppDialog.mWidgetPlacement.parentAnchorY = 0.0f;
        mAppDialog.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mAppDialog.setTitle(title);
        mAppDialog.setMessage(description);
        mAppDialog.setButtons(btnMsg);
        mAppDialog.setButtonsDelegate(buttonsCallback);
        mAppDialog.setMessageDelegate(messageCallback);
        mAppDialog.show(REQUEST_FOCUS);
    }

    public void showClearCacheDialog() {
        mClearCacheDialog = new ClearCacheDialogWidget(getContext());
        mClearCacheDialog.mWidgetPlacement.parentHandle = getHandle();
        mClearCacheDialog.mWidgetPlacement.parentAnchorY = 0.0f;
        mClearCacheDialog.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mClearCacheDialog.setTitle(R.string.history_clear);
        mClearCacheDialog.setButtons(new int[] {
                R.string.history_clear_cancel,
                R.string.history_clear_now
        });
        mClearCacheDialog.setButtonsDelegate((index) -> {
            if (index == BaseAppDialogWidget.LEFT) {
                mClearCacheDialog.hide(REMOVE_WIDGET);

            } else {
                Calendar date = new GregorianCalendar();
                date.set(Calendar.HOUR_OF_DAY, 0);
                date.set(Calendar.MINUTE, 0);
                date.set(Calendar.SECOND, 0);
                date.set(Calendar.MILLISECOND, 0);

                long currentTime = System.currentTimeMillis();
                long todayLimit = date.getTimeInMillis();
                long yesterdayLimit = todayLimit - SystemUtils.ONE_DAY_MILLIS;
                long oneWeekLimit = todayLimit - SystemUtils.ONE_WEEK_MILLIS;

                HistoryStore store = SessionStore.get().getHistoryStore();
                switch (mClearCacheDialog.getSelectedRange()) {
                    case ClearCacheDialogWidget.TODAY:
                        store.deleteVisitsBetween(todayLimit, currentTime);
                        break;
                    case ClearCacheDialogWidget.YESTERDAY:
                        store.deleteVisitsBetween(yesterdayLimit, currentTime);
                        break;
                    case ClearCacheDialogWidget.LAST_WEEK:
                        store.deleteVisitsBetween(oneWeekLimit, currentTime);
                        break;
                    case ClearCacheDialogWidget.EVERYTHING:
                        store.deleteEverything();
                        break;
                }
                SessionStore.get().purgeSessionHistory();
            }
        });
        mClearCacheDialog.show(REQUEST_FOCUS);
    }

    public void showMaxWindowsDialog(int maxDialogs) {
        mMaxWindowsDialog = new MaxWindowsWidget(getContext());
        mMaxWindowsDialog.mWidgetPlacement.parentAnchorY = 0.0f;
        mMaxWindowsDialog.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mMaxWindowsDialog.mWidgetPlacement.parentHandle = getHandle();
        mMaxWindowsDialog.setMessage(getContext().getString(R.string.max_windows_msg, String.valueOf(maxDialogs)));
        mMaxWindowsDialog.show(REQUEST_FOCUS);
    }

    public void setMaxWindowScale(float aScale) {
        if (mMaxWindowScale != aScale) {
            mMaxWindowScale = aScale;

            Pair<Float, Float> maxSize = getSizeForScale(aScale);

            if (mWidgetPlacement.worldWidth > maxSize.first) {
                float currentAspect = (float) mWidgetPlacement.width / (float) mWidgetPlacement.height;
                mWidgetPlacement.worldWidth = maxSize.first;
                mWidgetPlacement.width = getWindowWidth(maxSize.first);
                mWidgetPlacement.height = (int) Math.ceil((float)mWidgetPlacement.width / currentAspect);
            }
        }
    }

    public float getMaxWindowScale() {
        return mMaxWindowScale;
    }

    public @NonNull Pair<Float, Float> getSizeForScale(float aScale) {
        return getSizeForScale(aScale, SettingsStore.getInstance(getContext()).getWindowAspect());
    }

    public @NonNull Pair<Float, Float> getSizeForScale(float aScale, float aAspect) {
        float worldWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width) *
                    (float)SettingsStore.getInstance(getContext()).getWindowWidth() / (float)SettingsStore.WINDOW_WIDTH_DEFAULT;
        float worldHeight = worldWidth / aAspect;
        float area = worldWidth * worldHeight * aScale;
        float targetWidth = (float) Math.sqrt(area * aAspect);
        float targetHeight = targetWidth / aAspect;
        return Pair.create(targetWidth, targetHeight);
    }

    private int getWindowWidth(float aWorldWidth) {
        return (int) Math.floor(SettingsStore.WINDOW_WIDTH_DEFAULT * aWorldWidth / WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width));
    }

    private void showLibraryItemContextMenu(@NotNull View view, LibraryItemContextMenu.LibraryContextMenuItem item, boolean isLastVisibleItem) {
        view.requestFocusFromTouch();

        hideContextMenus();

        float ratio = WidgetPlacement.viewToWidgetRatio(getContext(), WindowWidget.this);

        Rect offsetViewBounds = new Rect();
        getDrawingRect(offsetViewBounds);
        offsetDescendantRectToMyCoords(view, offsetViewBounds);

        mLibraryItemContextMenu = new LibraryItemContextMenuWidget(getContext());
        mLibraryItemContextMenu.setItem(item);
        mLibraryItemContextMenu.mWidgetPlacement.parentHandle = getHandle();

        PointF position;
        if (isLastVisibleItem) {
            mLibraryItemContextMenu.mWidgetPlacement.anchorY = 0.0f;
            position = new PointF(
                    (offsetViewBounds.left + view.getWidth()) * ratio,
                    -(offsetViewBounds.top) * ratio);

        } else {
            mLibraryItemContextMenu.mWidgetPlacement.anchorY = 1.0f;
            position = new PointF(
                    (offsetViewBounds.left + view.getWidth()) * ratio,
                    -(offsetViewBounds.top + view.getHeight()) * ratio);
        }

        mLibraryItemContextMenu.setPosition(position);
        mLibraryItemContextMenu.setHistoryContextMenuItemCallback((new LibraryItemContextMenuClickCallback() {
            @Override
            public void onOpenInNewWindowClick(LibraryItemContextMenu.LibraryContextMenuItem item) {
                mWidgetManager.openNewWindow(item.getUrl());
                hideContextMenus();
            }

            @Override
            public void onAddToBookmarks(LibraryItemContextMenu.LibraryContextMenuItem item) {
                SessionStore.get().getBookmarkStore().addBookmark(item.getUrl(), item.getTitle());
                hideContextMenus();
            }

            @Override
            public void onRemoveFromBookmarks(LibraryItemContextMenu.LibraryContextMenuItem item) {
                SessionStore.get().getBookmarkStore().deleteBookmarkByURL(item.getUrl());
                hideContextMenus();
            }
        }));
        mLibraryItemContextMenu.show(REQUEST_FOCUS);
    }

    private BookmarksCallback mBookmarksCallback = new BookmarksCallback() {

        @Override
        public void onClearBookmarks(View view) {
            // Not used ATM
        }

        @Override
        public void onShowContextMenu(@NonNull View view, @NotNull BookmarkNode item, boolean isLastVisibleItem) {
            showLibraryItemContextMenu(
                    view,
                    new LibraryItemContextMenu.LibraryContextMenuItem(
                            item.getUrl(),
                            item.getTitle(),
                            LibraryItemContextMenu.LibraryItemType.BOOKMARKS),
                    isLastVisibleItem);
        }
    };

    private HistoryCallback mHistoryCallback = new HistoryCallback() {
        @Override
        public void onClearHistory(@NonNull View view) {
            view.requestFocusFromTouch();
            showClearCacheDialog();
        }

        @Override
        public void onShowContextMenu(@NonNull View view, @NonNull VisitInfo item, boolean isLastVisibleItem) {
            showLibraryItemContextMenu(
                    view,
                    new LibraryItemContextMenu.LibraryContextMenuItem(
                            item.getUrl(),
                            item.getTitle(),
                            LibraryItemContextMenu.LibraryItemType.HISTORY),
                    isLastVisibleItem);
        }
    };

    // PromptDelegate

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession geckoSession, @NonNull AlertPrompt alertPrompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();

        mAlertPrompt = new AlertPromptWidget(getContext());
        mAlertPrompt.mWidgetPlacement.parentHandle = getHandle();
        mAlertPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mAlertPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mAlertPrompt.setTitle(alertPrompt.title);
        mAlertPrompt.setMessage(alertPrompt.message);
        mAlertPrompt.setPromptDelegate(() -> result.complete(alertPrompt.dismiss()));
        mAlertPrompt.show(REQUEST_FOCUS);

        return result;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession geckoSession, @NonNull ButtonPrompt buttonPrompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();

        mConfirmPrompt = new ConfirmPromptWidget(getContext());
        mConfirmPrompt.mWidgetPlacement.parentHandle = getHandle();
        mConfirmPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mConfirmPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mConfirmPrompt.setTitle(buttonPrompt.title);
        mConfirmPrompt.setMessage(buttonPrompt.message);
        mConfirmPrompt.setButtons(new String[] {
                getResources().getText(R.string.ok_button).toString(),
                getResources().getText(R.string.cancel_button).toString()
        });
        mConfirmPrompt.setPromptDelegate(new ConfirmPromptWidget.ConfirmPromptDelegate() {
            @Override
            public void confirm(int index) {
                result.complete(buttonPrompt.confirm(index));
            }

            @Override
            public void dismiss() {
                result.complete(buttonPrompt.dismiss());
            }
        });
        mConfirmPrompt.show(REQUEST_FOCUS);

        return result;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession geckoSession, @NonNull TextPrompt textPrompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();

        mTextPrompt = new TextPromptWidget(getContext());
        mTextPrompt.mWidgetPlacement.parentHandle = getHandle();
        mTextPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mTextPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mTextPrompt.setTitle(textPrompt.title);
        mTextPrompt.setMessage(textPrompt.message);
        mTextPrompt.setDefaultText(textPrompt.defaultValue);
        mTextPrompt.setPromptDelegate(new TextPromptWidget.TextPromptDelegate() {
            @Override
            public void confirm(String message) {
                result.complete(textPrompt.confirm(message));
            }

            @Override
            public void dismiss() {
                result.complete(textPrompt.dismiss());
            }
        });
        mTextPrompt.show(REQUEST_FOCUS);

        return result;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession geckoSession, @NonNull AuthPrompt authPrompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();

        mAuthPrompt = new AuthPromptWidget(getContext());
        mAuthPrompt.mWidgetPlacement.parentHandle = getHandle();
        mAuthPrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mAuthPrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mAuthPrompt.setTitle(authPrompt.title);
        mAuthPrompt.setMessage(authPrompt.message);
        mAuthPrompt.setAuthOptions(authPrompt.authOptions);
        mAuthPrompt.setPromptDelegate(new AuthPromptWidget.AuthPromptDelegate() {
            @Override
            public void dismiss() {
                result.complete(authPrompt.dismiss());
            }

            @Override
            public void confirm(String password) {
                result.complete(authPrompt.confirm(password));
            }

            @Override
            public void confirm(String username, String password) {
                result.complete(authPrompt.confirm(username, password));
            }
        });
        mAuthPrompt.show(REQUEST_FOCUS);

        return result;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(@NonNull GeckoSession geckoSession, @NonNull ChoicePrompt choicePrompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();

        mChoicePrompt = new ChoicePromptWidget(getContext());
        mChoicePrompt.mWidgetPlacement.parentHandle = getHandle();
        mChoicePrompt.mWidgetPlacement.parentAnchorY = 0.0f;
        mChoicePrompt.mWidgetPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.base_app_dialog_y_distance);
        mChoicePrompt.setTitle(choicePrompt.title);
        mChoicePrompt.setMessage(choicePrompt.message);
        mChoicePrompt.setChoices(choicePrompt.choices);
        mChoicePrompt.setMenuType(choicePrompt.type);
        mChoicePrompt.setPromptDelegate(new ChoicePromptWidget.ChoicePromptDelegate() {
            @Override
            public void confirm(String[] choices) {
                result.complete(choicePrompt.confirm(choices));
            }

            @Override
            public void dismiss() {

            }
        });
        mChoicePrompt.show(REQUEST_FOCUS);

        return result;
    }

    // GeckoSession.ContentDelegate

    @Override
    public void onContextMenu(GeckoSession session, int screenX, int screenY, ContextElement element) {
        TelemetryWrapper.longPressContextMenuEvent();

        hideContextMenus();

        mContextMenu = new ContextMenuWidget(getContext());
        mContextMenu.mWidgetPlacement.parentHandle = getHandle();
        mContextMenu.setContextElement(mLastMouseClickPos, element);
        mContextMenu.show(REQUEST_FOCUS);
    }

    @Override
    public void onFirstComposite(@NonNull GeckoSession session) {
        if (!mAfterFirstPaint) {
            return;
        }
        if (mFirstDrawCallback != null) {
            ThreadUtils.postToUiThread(mFirstDrawCallback);
            mFirstDrawCallback = null;
        }
    }

    @Override
    public void onFirstContentfulPaint(@NonNull GeckoSession session) {
        if (mAfterFirstPaint) {
            return;
        }
        if (mFirstDrawCallback != null) {
            ThreadUtils.postToUiThread(mFirstDrawCallback);
            mFirstDrawCallback = null;
            mAfterFirstPaint = true;
        }
    }

    // VideoAvailabilityListener

    @Override
    public void onVideoAvailabilityChanged(boolean aVideosAvailable) {
        mWidgetManager.setCPULevel(aVideosAvailable ?
                WidgetManagerDelegate.CPU_LEVEL_HIGH :
                WidgetManagerDelegate.CPU_LEVEL_NORMAL);

        if (mTitleBar != null) {
            mTitleBar.mediaAvailabilityChanged(aVideosAvailable);
        }
    }

    // GeckoSession.NavigationDelegate
    @Override
    public void onPageStop(@NonNull GeckoSession aSession, boolean b) {
        captureImage();
    }

    public void captureImage() {
        if (mDisplay == null || !mSession.getGeckoSession().isOpen()) {
            return;
        }

        mDisplay.capturePixels().then(bitmap -> {
            if (bitmap != null) {
                mSession.setBitmap(bitmap, mSession.getGeckoSession());
            }
            return null;
        });
    }

    @Override
    public void onLocationChange(@NonNull GeckoSession session, @Nullable String url) {
        if (isBookmarksVisible()) {
            hideBookmarks();

        } else if (isHistoryVisible()) {
            hideHistory();
        }

        updateTitleBarUrl(url);
    }

    // GeckoSession.HistoryDelegate

    @Override
    public void onHistoryStateChange(@NonNull GeckoSession geckoSession, @NonNull HistoryList historyList) {
        for (HistoryItem item : historyList) {
            SessionStore.get().getHistoryStore().recordObservation(item.getUri(), new PageObservation(item.getTitle()));
        }
    }

    @Nullable
    @Override
    public GeckoResult<Boolean> onVisited(@NonNull GeckoSession geckoSession, @NonNull String url, @Nullable String lastVisitedURL, int flags) {
        if (mSession.isPrivateMode() ||
                (flags & VISIT_TOP_LEVEL) == 0 ||
                (flags & VISIT_UNRECOVERABLE_ERROR) != 0) {
            return GeckoResult.fromValue(false);
        }

        boolean isReload = lastVisitedURL != null && lastVisitedURL.equals(url);

        VisitType visitType;
        if (isReload) {
            visitType = VisitType.RELOAD;
        } else {
            if ((flags & VISIT_REDIRECT_SOURCE_PERMANENT) != 0) {
                visitType = VisitType.REDIRECT_PERMANENT;
            } else if ((flags & VISIT_REDIRECT_SOURCE) != 0) {
                visitType = VisitType.REDIRECT_TEMPORARY;
            } else {
                visitType = VisitType.LINK;
            }
        }

        SessionStore.get().getHistoryStore().deleteVisitsFor(url).thenAcceptAsync(result -> {
            SessionStore.get().getHistoryStore().recordVisit(url, visitType);
        });
        return GeckoResult.fromValue(true);
    }

    @UiThread
    @Nullable
    public GeckoResult<boolean[]> getVisited(@NonNull GeckoSession geckoSession, @NonNull String[] urls) {
        if (mSession.isPrivateMode()) {
            return GeckoResult.fromValue(new boolean[]{});
        }

        GeckoResult<boolean[]> result = new GeckoResult<>();

        SessionStore.get().getHistoryStore().getVisited(Arrays.asList(urls)).thenAcceptAsync(list -> {
            final boolean[] primitives = new boolean[list.size()];
            int index = 0;
            for (Boolean object : list) {
                primitives[index++] = object;
            }
            result.complete(primitives);
        });

        return result;
    }

    // GeckoSession.ProgressDelegate

    @Override
    public void onSecurityChange(GeckoSession geckoSession, SecurityInformation securityInformation) {
        if (mTitleBar != null) {
            mTitleBar.setIsInsecure(!securityInformation.isSecure);
        }
    }

    // TabsWidget.TabDelegate

    @Override
    public void onTabSelect(Session aTab) {
        for (WindowListener listener: mListeners) {
            listener.onTabSelect(this, aTab);
        }
    }

    @Override
    public void onTabAdd() {
        for (WindowListener listener: mListeners) {
            listener.onTabAdd(this);
        }
    }

    @Override
    public void onTabsClose(ArrayList<Session> aTabs) {
        for (WindowListener listener : mListeners) {
            listener.onTabsClose(this, aTabs);
        }
    }
}

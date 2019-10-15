/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.widgets.settings;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.vrbrowser.BuildConfig;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.VRBrowserApplication;
import org.mozilla.vrbrowser.audio.AudioEngine;
import org.mozilla.vrbrowser.browser.Services;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.browser.engine.SessionStack;
import org.mozilla.vrbrowser.ui.views.HoneycombButton;
import org.mozilla.vrbrowser.ui.widgets.UIWidget;
import org.mozilla.vrbrowser.ui.widgets.WidgetManagerDelegate;
import org.mozilla.vrbrowser.ui.widgets.WidgetPlacement;
import org.mozilla.vrbrowser.ui.widgets.dialogs.RestartDialogWidget;
import org.mozilla.vrbrowser.ui.widgets.dialogs.UIDialog;
import org.mozilla.vrbrowser.ui.widgets.prompts.AlertPromptWidget;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import mozilla.components.service.fxa.manager.FxaAccountManager;

public class SettingsWidget extends UIDialog implements WidgetManagerDelegate.WorldClickListener, SettingsView.Delegate {
    private AudioEngine mAudio;
    private SettingsView mCurrentView;
    private TextView mBuildText;
    private ViewGroup mMainLayout;
    private int mViewMarginH;
    private int mViewMarginV;
    private int mRestartDialogHandle = -1;
    private int mAlertDialogHandle = -1;

    private AccountsHelper accountHelper = new AccountsHelper(this);

    class VersionGestureListener extends GestureDetector.SimpleOnGestureListener {

        private boolean mIsHash;

        @Override
        public boolean onDown (MotionEvent e) {
            mBuildText.setText(mIsHash ? versionCodeToDate(BuildConfig.VERSION_CODE) : BuildConfig.GIT_HASH);

            mIsHash = !mIsHash;

            return true;
        }
    }

    public SettingsWidget(Context aContext) {
        super(aContext);
        initialize(aContext);
    }

    public SettingsWidget(Context aContext, AttributeSet aAttrs) {
        super(aContext, aAttrs);
        initialize(aContext);
    }

    public SettingsWidget(Context aContext, AttributeSet aAttrs, int aDefStyle) {
        super(aContext, aAttrs, aDefStyle);
        initialize(aContext);
    }

    private void initialize(Context aContext) {
        inflate(aContext, R.layout.settings, this);

        mWidgetManager.addWorldClickListener(this);
        mMainLayout = findViewById(R.id.optionsLayout);

        ImageButton cancelButton = findViewById(R.id.backButton);
        cancelButton.setOnClickListener(v -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            onDismiss();
        });

        LinearLayout reportIssue = findViewById(R.id.reportIssueLayout);
        reportIssue.setOnClickListener(v -> {
            onSettingsReportClick();
            onDismiss();
        });

        HoneycombButton languageButton = findViewById(R.id.languageButton);
        languageButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            onLanguageOptionsClick();
        });

        HoneycombButton privacyButton = findViewById(R.id.privacyButton);
        privacyButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            onSettingsPrivacyClick();
        });

        HoneycombButton displayButton = findViewById(R.id.displayButton);
        displayButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            onDisplayOptionsClick();
        });

        HoneycombButton environmentButton = findViewById(R.id.environmentButton);
        environmentButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            showEnvironmentOptionsDialog();
        });

        TextView versionText = findViewById(R.id.versionText);
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            versionText.setText(String.format(getResources().getString(R.string.settings_version), pInfo.versionName));

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        mBuildText = findViewById(R.id.buildText);
        mBuildText.setText(versionCodeToDate(BuildConfig.VERSION_CODE));

        ViewGroup settingsMasthead = findViewById(R.id.settingsMasthead);
        final GestureDetector gd = new GestureDetector(getContext(), new VersionGestureListener());
        settingsMasthead.setOnTouchListener((view, motionEvent) -> {
            if (gd.onTouchEvent(motionEvent)) {
                return true;
            }
            return view.performClick();
        });

        HoneycombButton reportButton = findViewById(R.id.helpButton);
        reportButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }
            SessionStore.get().getActiveStore().loadUri(getContext().getString(R.string.help_url));
            onDismiss();
        });

        HoneycombButton fxaButton = findViewById(R.id.fxaButton);
        fxaButton.setOnClickListener(view ->
                onTurnOnSyncClick()
        );

        updateCurrentAccountState();

        // Monitor account state changes.
        getServices().getAccountManager().register(accountHelper.getAccountObserver());

        HoneycombButton developerOptionsButton = findViewById(R.id.developerOptionsButton);
        developerOptionsButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            onDeveloperOptionsClick();
        });

        HoneycombButton controllerOptionsButton = findViewById(R.id.controllerOptionsButton);
        controllerOptionsButton.setOnClickListener(view -> {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            showControllerOptionsDialog();
        });

        mAudio = AudioEngine.fromContext(aContext);

        mViewMarginH = mWidgetPlacement.width - WidgetPlacement.dpDimension(getContext(), R.dimen.options_width);
        mViewMarginH = WidgetPlacement.convertDpToPixel(getContext(), mViewMarginH);
        mViewMarginV = mWidgetPlacement.height - WidgetPlacement.dpDimension(getContext(), R.dimen.options_height);
        mViewMarginV = WidgetPlacement.convertDpToPixel(getContext(), mViewMarginV);
    }

    @Override
    public void releaseWidget() {
        mWidgetManager.removeWorldClickListener(this);

        super.releaseWidget();
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        aPlacement.visible = false;
        aPlacement.width =  WidgetPlacement.dpDimension(getContext(), R.dimen.settings_width);
        aPlacement.height = WidgetPlacement.dpDimension(getContext(), R.dimen.settings_height);
        aPlacement.parentAnchorX = 0.5f;
        aPlacement.parentAnchorY = 0.0f;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.translationY = WidgetPlacement.unitFromMeters(getContext(), R.dimen.settings_world_y) -
                                  WidgetPlacement.unitFromMeters(getContext(), R.dimen.window_world_y);
        aPlacement.translationZ = WidgetPlacement.unitFromMeters(getContext(), R.dimen.settings_world_z) -
                                  WidgetPlacement.unitFromMeters(getContext(), R.dimen.window_world_z);
    }

    private void onSettingsPrivacyClick() {
        showView(new PrivacyOptionsView(getContext(), mWidgetManager));
    }

    private void onSettingsReportClick() {
        SessionStack sessionStack = SessionStore.get().getActiveStore();
        String url = sessionStack.getCurrentUri();

        try {
            if (url == null) {
                // In case the user had no active sessions when reporting, just leave the URL field empty.
                url = "";
            } else if (url.startsWith("jar:") || url.startsWith("resource:") || url.startsWith("about:") || url.startsWith("data:")) {
                url = "";
            } else if (sessionStack.isHomeUri(url)) {
                // Use the original URL (without any hash).
                url = sessionStack.getHomeUri();
            }

            url = URLEncoder.encode(url, "UTF-8");

        } catch (UnsupportedEncodingException e) {
            Log.e(LOGTAG, "Cannot encode URL");
        }

        sessionStack.newSessionWithUrl(getContext().getString(R.string.private_report_url, url));

        onDismiss();
    }

    private void onTurnOnSyncClick() {
        FxaAccountManager manager = getServices().getAccountManager();
        // If we're already logged-in, and not in a "need to reconnect" state, logout.
        if (manager.authenticatedAccount() != null && !manager.accountNeedsReauth()) {
            manager.logoutAsync();
            return;
        }

        // Otherwise, obtain an authentication URL and load it in the gecko session.
        // Recovering from "need to reconnect" state is treated the same as just logging in.
        CompletableFuture<String> futureUrl = accountHelper.authUrlAsync();
        if (futureUrl == null) {
            Log.w(LOGTAG, "Got a 'null' futureUrl");
            return;
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                String url = futureUrl.get();
                if (url == null) {
                    Log.w(LOGTAG, "Got a 'null' url after resolving futureUrl");
                    return;
                }
                Log.i(LOGTAG, "Got an auth url: " + url);

                // Actually process the url on the main thread.
                new Handler(Looper.getMainLooper()).post(() -> {
                    Log.i(LOGTAG, "Loading url...");
                    SessionStore.get().getActiveStore().loadUri(url);
                    hide(REMOVE_WIDGET);
                });
            } catch (ExecutionException | InterruptedException e) {
                Log.e(LOGTAG, "Error obtaining auth url", e);
            }
        });
    }

    // TODO we can also set profile display name, email and authentication problem states.
    void updateCurrentAccountState() {
        HoneycombButton fxaButton = findViewById(R.id.fxaButton);
        FxaAccountManager manager = getServices().getAccountManager();
        if (manager.authenticatedAccount() != null) {
            if (manager.accountNeedsReauth()) {
                ((TextView) fxaButton.findViewById(R.id.settings_button_text)).setText(R.string.settings_accounts_reconnect);
            } else {
                ((TextView) fxaButton.findViewById(R.id.settings_button_text)).setText(R.string.settings_accounts_sign_out);
            }
        } else {
            ((TextView) fxaButton.findViewById(R.id.settings_button_text)).setText(R.string.settings_accounts_sign_in);
        }
    }

    private void onDeveloperOptionsClick() {
        showDeveloperOptionsDialog();
    }

    private void onLanguageOptionsClick() {
        showLanguageOptionsDialog();
    }

    private void onDisplayOptionsClick() {
        showDisplayOptionsDialog();
    }

    /**
     * The version code is composed like: yDDDHHmm
     *  * y   = Double digit year, with 16 substracted: 2017 -> 17 -> 1
     *  * DDD = Day of the year, pad with zeros if needed: September 6th -> 249
     *  * HH  = Hour in day (00-23)
     *  * mm  = Minute in hour
     *
     * For September 6th, 2017, 9:41 am this will generate the versionCode: 12490941 (1-249-09-41).
     *
     * For local debug builds we use a fixed versionCode to not mess with the caching mechanism of the build
     * system. The fixed local build number is 1.
     *
     * @param aVersionCode Application version code minus the leading architecture digit.
     * @return String The converted date in the format yyyy-MM-dd
     */
    private String versionCodeToDate(final int aVersionCode) {
        String versionCode = Integer.toString(aVersionCode);

        String formatted;
        try {
            int year = Integer.parseInt(versionCode.substring(0, 1)) + 2016;
            int dayOfYear = Integer.parseInt(versionCode.substring(1, 4));

            GregorianCalendar cal = (GregorianCalendar)GregorianCalendar.getInstance();
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.DAY_OF_YEAR, dayOfYear);

            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            formatted = format.format(cal.getTime());

        } catch (StringIndexOutOfBoundsException e) {
            formatted = getContext().getString(R.string.settings_version_developer);
        }

        return formatted;
    }

    public void showView(SettingsView aView) {
        if (mCurrentView != null) {
            mCurrentView.onHidden();
            this.removeView(mCurrentView);
        }
        mCurrentView = aView;
        if (mCurrentView != null) {
            Point viewDimensions = mCurrentView.getDimensions();
            mViewMarginH = mWidgetPlacement.width - viewDimensions.x;
            mViewMarginH = WidgetPlacement.convertDpToPixel(getContext(), mViewMarginH);
            mViewMarginV = mWidgetPlacement.height - viewDimensions.y;
            mViewMarginV = WidgetPlacement.convertDpToPixel(getContext(), mViewMarginV);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.leftMargin = params.rightMargin = mViewMarginH / 2;
            params.topMargin = params.bottomMargin = mViewMarginV / 2;
            this.addView(mCurrentView, params);
            mCurrentView.setDelegate(this);
            mCurrentView.onShown();
            mMainLayout.setVisibility(View.GONE);
        } else {
            mMainLayout.setVisibility(View.VISIBLE);
        }
    }

    private void showDeveloperOptionsDialog() {
        showView(new DeveloperOptionsView(getContext(), mWidgetManager));
    }

    private void showControllerOptionsDialog() {
        showView(new ControllerOptionsView(getContext(), mWidgetManager));
    }

    private void showLanguageOptionsDialog() {
        LanguageOptionsView view = new LanguageOptionsView(getContext(), mWidgetManager);
        view.setDelegate(this);
        showView(view);
    }

    private void showDisplayOptionsDialog() {
        showView(new DisplayOptionsView(getContext(), mWidgetManager));
    }

    private void showEnvironmentOptionsDialog() {
        showView(new EnvironmentOptionsView(getContext(), mWidgetManager));
    }

    // WindowManagerDelegate.FocusChangeListener
    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (mCurrentView != null) {
            mCurrentView.onGlobalFocusChanged(oldFocus, newFocus);
        } else if (oldFocus == this && isVisible()) {
            onDismiss();
        }
    }

    @Override
    public void show(@ShowFlags int aShowFlags) {
        super.show(aShowFlags);

        mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        super.hide(aHideFlags);

        mWidgetManager.popWorldBrightness(this);
    }

    // WidgetManagerDelegate.WorldClickListener
    @Override
    public void onWorldClick() {
        onDismiss();
    }

    // SettingsView.Delegate
    @Override
    public void onDismiss() {
        if (mCurrentView != null) {
            if (!mCurrentView.isEditing()) {
                if (isLanguagesSubView(mCurrentView)) {
                    showLanguageOptionsDialog();
                } else {
                    showView(null);
                }
            }
        } else {
            super.onDismiss();
        }
    }

    @Override
    public void exitWholeSettings() {
        showView(null);
        hide(UIWidget.REMOVE_WIDGET);
    }

    @Override
    public void showRestartDialog() {
        hide(UIWidget.REMOVE_WIDGET);

        UIWidget widget = getChild(mRestartDialogHandle);
        if (widget == null) {
            widget = createChild(RestartDialogWidget.class, false);
            mRestartDialogHandle = widget.getHandle();
            widget.setDelegate(() -> show(REQUEST_FOCUS));
        }

        widget.show(REQUEST_FOCUS);
    }

    @Override
    public void showAlert(String aTitle, String aMessage) {
        hide(UIWidget.KEEP_WIDGET);

        AlertPromptWidget widget = getChild(mAlertDialogHandle);
        if (widget == null) {
            widget = createChild(AlertPromptWidget.class, false);
            mAlertDialogHandle = widget.getHandle();
            widget.setDelegate(() -> show(REQUEST_FOCUS));
        }
        widget.getPlacement().translationZ = 0;
        widget.getPlacement().parentHandle = mHandle;
        widget.setTitle(aTitle);
        widget.setMessage(aMessage);

        widget.show(REQUEST_FOCUS);
    }

    private boolean isLanguagesSubView(View view) {
        if (view instanceof DisplayLanguageOptionsView ||
                view instanceof ContentLanguageOptionsView ||
                view instanceof  VoiceSearchLanguageOptionsView) {
            return true;
        }

        return false;
    }

    private Services getServices() {
        return ((VRBrowserApplication) getContext().getApplicationContext()).getServices();
    }
}

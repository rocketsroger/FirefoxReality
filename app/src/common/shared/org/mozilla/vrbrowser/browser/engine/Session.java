/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.browser.engine;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaElement;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.browser.Media;
import org.mozilla.vrbrowser.browser.SessionChangeListener;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.UserAgentOverride;
import org.mozilla.vrbrowser.browser.VideoAvailabilityListener;
import org.mozilla.vrbrowser.geolocation.GeolocationData;
import org.mozilla.vrbrowser.telemetry.TelemetryWrapper;
import org.mozilla.vrbrowser.utils.InternalPages;
import org.mozilla.vrbrowser.utils.SystemUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mozilla.vrbrowser.utils.ServoUtils.createServoSession;
import static org.mozilla.vrbrowser.utils.ServoUtils.isInstanceOfServoSession;
import static org.mozilla.vrbrowser.utils.ServoUtils.isServoAvailable;

public class Session implements ContentBlocking.Delegate, GeckoSession.NavigationDelegate,
        GeckoSession.ProgressDelegate, GeckoSession.ContentDelegate, GeckoSession.TextInputDelegate,
        GeckoSession.PromptDelegate, GeckoSession.MediaDelegate, GeckoSession.HistoryDelegate, GeckoSession.PermissionDelegate,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String LOGTAG = SystemUtils.createLogtag(Session.class);

    private transient LinkedList<GeckoSession.NavigationDelegate> mNavigationListeners;
    private transient LinkedList<GeckoSession.ProgressDelegate> mProgressListeners;
    private transient LinkedList<GeckoSession.ContentDelegate> mContentListeners;
    private transient LinkedList<SessionChangeListener> mSessionChangeListeners;
    private transient LinkedList<GeckoSession.TextInputDelegate> mTextInputListeners;
    private transient LinkedList<VideoAvailabilityListener> mVideoAvailabilityListeners;
    private transient LinkedList<BitmapChangedListener> mBitmapChangedListeners;
    private transient UserAgentOverride mUserAgentOverride;

    private SessionState mState;
    private transient GeckoSession.PermissionDelegate mPermissionDelegate;
    private transient GeckoSession.PromptDelegate mPromptDelegate;
    private transient GeckoSession.HistoryDelegate mHistoryDelegate;
    private transient Context mContext;
    private transient SharedPreferences mPrefs;
    private transient GeckoRuntime mRuntime;
    private boolean mUsePrivateMode;
    private transient byte[] mPrivatePage;

    public interface BitmapChangedListener {
        void onBitmapChanged(Bitmap aBitmap);
    }

    protected Session(Context aContext, GeckoRuntime aRuntime, boolean aUsePrivateMode) {
        this(aContext, aRuntime, aUsePrivateMode, null);
    }

    protected Session(Context aContext, GeckoRuntime aRuntime, boolean aUsePrivateMode,
                      @Nullable SessionSettings aSettings) {
        mContext = aContext;
        mRuntime = aRuntime;
        mUsePrivateMode = aUsePrivateMode;
        initialize();
        if (aSettings != null) {
            mState = createSession(aSettings);
        } else {
            mState = createSession();
        }

        setupSessionListeners(mState.mSession);
    }

    protected Session(Context aContext, GeckoRuntime aRuntime, SessionState aRestoreState) {
        mContext = aContext;
        mRuntime = aRuntime;
        mUsePrivateMode = false;
        initialize();
        mState = aRestoreState;
        restore();
        setupSessionListeners(mState.mSession);
    }

    private void initialize() {
        mNavigationListeners = new LinkedList<>();
        mProgressListeners = new LinkedList<>();
        mContentListeners = new LinkedList<>();
        mSessionChangeListeners = new LinkedList<>();
        mTextInputListeners = new LinkedList<>();
        mVideoAvailabilityListeners = new LinkedList<>();
        mBitmapChangedListeners = new LinkedList<>();

        if (mPrefs != null) {
            mPrefs.registerOnSharedPreferenceChangeListener(this);
        }

        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        InternalPages.PageResources pageResources = InternalPages.PageResources.create(R.raw.private_mode, R.raw.private_style);
        mPrivatePage = InternalPages.createAboutPage(mContext, pageResources);

        if (mUserAgentOverride == null) {
            mUserAgentOverride = new UserAgentOverride();
            mUserAgentOverride.loadOverridesFromAssets((Activity)mContext, mContext.getString(R.string.user_agent_override_file));
        }
    }

    protected void shutdown() {
        if (mState.mSession != null) {
            if (mState.mSession.isOpen()) {
                mState.mSession.close();
            }
            mState.mSession = null;
        }

        mNavigationListeners.clear();
        mProgressListeners.clear();
        mContentListeners.clear();
        mSessionChangeListeners.clear();
        mTextInputListeners.clear();
        mVideoAvailabilityListeners.clear();
        mBitmapChangedListeners.clear();

        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    private void dumpAllState() {
        for (GeckoSession.NavigationDelegate listener: mNavigationListeners) {
            dumpState(listener);
        }
        for (GeckoSession.ProgressDelegate listener: mProgressListeners) {
            dumpState(listener);
        }
        for (GeckoSession.ContentDelegate listener: mContentListeners) {
            dumpState(listener);
        }
    }

    private void dumpState(GeckoSession.NavigationDelegate aListener) {
        if (mState.mSession != null) {
            aListener.onCanGoBack(mState.mSession, mState.mCanGoBack);
            aListener.onCanGoForward(mState.mSession, mState.mCanGoForward);
            aListener.onLocationChange(mState.mSession, mState.mUri);
        }
    }

    private void dumpState(GeckoSession.ProgressDelegate aListener) {
        if (mState.mIsLoading) {
            aListener.onPageStart(mState.mSession, mState.mUri);
        } else {
            aListener.onPageStop(mState.mSession, true);
        }

        if (mState.mSecurityInformation != null) {
            aListener.onSecurityChange(mState.mSession, mState.mSecurityInformation);
        }
    }

    private void dumpState(GeckoSession.ContentDelegate aListener) {
        aListener.onTitleChange(mState.mSession, mState.mTitle);
    }

    public void setPermissionDelegate(GeckoSession.PermissionDelegate aDelegate) {
        mPermissionDelegate = aDelegate;
    }

    public void setPromptDelegate(GeckoSession.PromptDelegate aDelegate) {
        mPromptDelegate = aDelegate;
    }

    public void setHistoryDelegate(GeckoSession.HistoryDelegate aDelegate) {
        mHistoryDelegate = aDelegate;
    }

    public void addNavigationListener(GeckoSession.NavigationDelegate aListener) {
        mNavigationListeners.add(aListener);
        dumpState(aListener);
    }

    public void removeNavigationListener(GeckoSession.NavigationDelegate aListener) {
        mNavigationListeners.remove(aListener);
    }

    public void addProgressListener(GeckoSession.ProgressDelegate aListener) {
        mProgressListeners.add(aListener);
        dumpState(aListener);
    }

    public void removeProgressListener(GeckoSession.ProgressDelegate aListener) {
        mProgressListeners.remove(aListener);
    }

    public void addContentListener(GeckoSession.ContentDelegate aListener) {
        mContentListeners.add(aListener);
        dumpState(aListener);
    }

    public void removeContentListener(GeckoSession.ContentDelegate aListener) {
        mContentListeners.remove(aListener);
    }

    public void addSessionChangeListener(SessionChangeListener aListener) {
        mSessionChangeListeners.add(aListener);
    }

    public void removeSessionChangeListener(SessionChangeListener aListener) {
        mSessionChangeListeners.remove(aListener);
    }

    public void addTextInputListener(GeckoSession.TextInputDelegate aListener) {
        mTextInputListeners.add(aListener);
    }

    public void removeTextInputListener(GeckoSession.TextInputDelegate aListener) {
        mTextInputListeners.remove(aListener);
    }

    public void addVideoAvailabilityListener(VideoAvailabilityListener aListener) {
        mVideoAvailabilityListeners.add(aListener);
    }

    public void removeVideoAvailabilityListener(VideoAvailabilityListener aListener) {
        mVideoAvailabilityListeners.remove(aListener);
    }

    public void addBitmapChangedListener(BitmapChangedListener aListener) {
        mBitmapChangedListeners.add(aListener);
    }

    public void removeBitmapChangedListener(BitmapChangedListener aListener) {
        mBitmapChangedListeners.remove(aListener);
    }

    private void setupSessionListeners(GeckoSession aSession) {
        aSession.setNavigationDelegate(this);
        aSession.setProgressDelegate(this);
        aSession.setContentDelegate(this);
        aSession.getTextInput().setDelegate(this);
        aSession.setPermissionDelegate(this);
        aSession.setPromptDelegate(this);
        aSession.setContentBlockingDelegate(this);
        aSession.setMediaDelegate(this);
        aSession.setHistoryDelegate(this);
    }

    private void cleanSessionListeners(GeckoSession aSession) {
        aSession.setContentDelegate(null);
        aSession.setNavigationDelegate(null);
        aSession.setProgressDelegate(null);
        aSession.getTextInput().setDelegate(null);
        aSession.setPromptDelegate(null);
        aSession.setPermissionDelegate(null);
        aSession.setContentBlockingDelegate(null);
        aSession.setMediaDelegate(null);
        aSession.setHistoryDelegate(null);
    }

    private void restore() {
        SessionSettings settings = mState.mSettings;
        if (settings == null) {
            settings = new SessionSettings.Builder()
                    .withDefaultSettings(mContext)
                    .build();
        }

        mState.mSession = createGeckoSession(settings);
        if (!mState.mSession.isOpen()) {
            mState.mSession.open(mRuntime);
        }
        
        if (mState.mSessionState != null) {
            mState.mSession.restoreState(mState.mSessionState);
        }

        for (SessionChangeListener listener: mSessionChangeListeners) {
            listener.onNewSession(mState.mSession);
        }

        if (mUsePrivateMode) {
            loadPrivateBrowsingPage();
        } else if(mState.mSessionState == null || mState.mUri.equals(mContext.getResources().getString(R.string.about_blank)) ||
                (mState.mSessionState != null && mState.mSessionState.size() == 0)) {
            loadHomePage();
        }

        dumpAllState();
    }

    private SessionState createSession() {
        SessionSettings settings = new SessionSettings.Builder()
                .withDefaultSettings(mContext)
                .build();

        return createSession(settings);
    }

    private SessionState createSession(@NonNull SessionSettings aSettings) {
        SessionState state = new SessionState();
        state.mSettings = aSettings;
        state.mSession = createGeckoSession(aSettings);

        if (!state.mSession.isOpen()) {
            state.mSession.open(mRuntime);
        }

        for (SessionChangeListener listener: mSessionChangeListeners) {
            listener.onNewSession(state.mSession);
        }

        return state;
    }

    private GeckoSession createGeckoSession(@NonNull SessionSettings aSettings) {
        GeckoSessionSettings geckoSettings = new GeckoSessionSettings.Builder()
                .useMultiprocess(aSettings.isMultiprocessEnabled())
                .usePrivateMode(mUsePrivateMode)
                .useTrackingProtection(aSettings.isTrackingProtectionEnabled())
                .build();

        GeckoSession session;
        if (aSettings.isServoEnabled() && isServoAvailable()) {
            session = createServoSession(mContext);
        } else {
            session = new GeckoSession(geckoSettings);
        }

        session.getSettings().setSuspendMediaWhenInactive(aSettings.isSuspendMediaWhenInactiveEnabled());
        session.getSettings().setUserAgentMode(aSettings.getUserAgentMode());
        session.getSettings().setUserAgentOverride(aSettings.getUserAgentOverride());

        return session;
    }

    private void recreateSession() {
        SessionState previous = mState;

        mState = createSession(previous.mSettings);
        if (previous.mSessionState != null)
            mState.mSession.restoreState(previous.mSessionState);
        if (previous.mSession != null) {
            closeSession(previous.mSession);
        }

        for (SessionChangeListener listener : mSessionChangeListeners) {
            listener.onCurrentSessionChange(previous.mSession, mState.mSession);
        }
    }

    private void closeSession(@NonNull GeckoSession aSession) {
        cleanSessionListeners(aSession);
        for (SessionChangeListener listener : mSessionChangeListeners) {
            listener.onRemoveSession(aSession);
        }
        aSession.setActive(false);
        aSession.stop();
        aSession.close();
    }

    public void setBitmap(Bitmap aBitmap, GeckoSession aSession) {
        if (aSession == mState.mSession) {
            mState.mBitmap = aBitmap;
            for (BitmapChangedListener listener: mBitmapChangedListeners) {
                listener.onBitmapChanged(mState.mBitmap);
            }
        }
    }

    public @Nullable Bitmap getBitmap() {
        return mState.mBitmap;
    }

    public void purgeHistory() {
        if (mState.mSession != null) {
            mState.mSession.purgeHistory();
        }
    }

    public void setRegion(String aRegion) {
        Log.d(LOGTAG, "Session setRegion: " + aRegion);
        mState.mRegion = aRegion != null ? aRegion.toLowerCase() : "worldwide";

        // There is a region initialize and the home is already loaded
        if (mState.mSession != null && isHomeUri(getCurrentUri())) {
            mState.mSession.loadUri("javascript:window.location.replace('" + getHomeUri() + "');");
        }
    }

    public String getHomeUri() {
        String homepage = SettingsStore.getInstance(mContext).getHomepage();
        if (homepage.equals(mContext.getString(R.string.homepage_url)) && mState.mRegion != null) {
            homepage = homepage + "?region=" + mState.mRegion;
        }
        return homepage;
    }

    public Boolean isHomeUri(String aUri) {
        return aUri != null && aUri.toLowerCase().startsWith(
          SettingsStore.getInstance(mContext).getHomepage()
        );
    }

    public String getCurrentUri() {
        return mState.mUri;
    }

    public String getCurrentTitle() {
        return mState.mTitle;
    }

    public boolean isSecure() {
        return mState.mSecurityInformation != null && mState.mSecurityInformation.isSecure;
    }

    public Media getFullScreenVideo() {
        for (Media media: mState.mMediaElements) {
            if (media.isFullscreen()) {
                return media;
            }
        }
        if (mState.mMediaElements.size() > 0) {
            return mState.mMediaElements.get(mState.mMediaElements.size() - 1);
        }

        return null;
    }

    public boolean isInputActive() {
        return mState.mIsInputActive;
    }

    public boolean canGoBack() {
        return mState.mCanGoBack || isInFullScreen();
    }

    public void goBack() {
        if (isInFullScreen()) {
            exitFullScreen();
        } else if (mState.mCanGoBack && mState.mSession != null) {
            mState.mSession.goBack();
        }
    }

    public void goForward() {
        if (mState.mCanGoForward && mState.mSession != null) {
            mState.mSession.goForward();
        }
    }

    public void setActive(boolean aActive) {
        if (mState.mSession != null) {
            mState.mSession.setActive(aActive);
        }
    }

    public void reload() {
        if (mState.mSession != null) {
            mState.mSession.reload();
        }
    }

    public void stop() {
        if (mState.mSession != null) {
            mState.mSession.stop();
        }
    }

    public void loadUri(String aUri) {
        if (aUri == null) {
            aUri = getHomeUri();
        }
        if (mState.mSession != null) {
            Log.d(LOGTAG, "Loading URI: " + aUri);
            mState.mSession.loadUri(aUri);
        }
    }

    public void loadHomePage() {
        loadUri(getHomeUri());
    }

    public void loadPrivateBrowsingPage() {
        if (mState.mSession != null) {
            mState.mSession.loadData(mPrivatePage, "text/html");
        }
    }

    public void toggleServo() {
        if (mState.mSession == null) {
            return;
        }

        Log.v("servo", "toggleServo");
        SessionState previous = mState;
        String uri = getCurrentUri();

        SessionSettings settings = new SessionSettings.Builder()
                .withDefaultSettings(mContext)
                .withServo(!isInstanceOfServoSession(mState.mSession))
                .build();

        mState = createSession(settings);
        closeSession(previous.mSession);
        loadUri(uri);
    }

    public boolean isInFullScreen() {
        return mState.mFullScreen;
    }

    public void exitFullScreen() {
        if (mState.mSession != null) {
            mState.mSession.exitFullScreen();
        }
    }

    public GeckoSession getGeckoSession() {
        return mState.mSession;
    }

    public boolean isPrivateMode() {
        if (mState.mSession != null) {
            return mState.mSession.getSettings().getUsePrivateMode();
        }
        return false;
    }

    // Session Settings

    protected void setServo(final boolean enabled) {
        mState.mSettings.setServoEnabled(enabled);
        if (mState.mSession != null && isInstanceOfServoSession(mState.mSession) != enabled) {
           toggleServo();
        }
    }

    public int getUaMode() {
        return mState.mSession.getSettings().getUserAgentMode();
    }

    private static final String M_PREFIX = "m.";
    private static final String MOBILE_PREFIX = "mobile.";

    private String checkForMobileSite(String aUri) {
        if (aUri == null) {
            return null;
        }
        String result = null;
        URI uri;
        try {
            uri = new URI(aUri);
        } catch (URISyntaxException | NullPointerException e) {
            Log.d(LOGTAG, "Error parsing URL: " + aUri + " " + e.getMessage());
            return null;
        }
        String authority = uri.getAuthority();
        if (authority == null) {
            return null;
        }
        authority = authority.toLowerCase();
        String foundPrefix = null;
        if (authority.startsWith(M_PREFIX)) {
            foundPrefix= M_PREFIX;
        } else if (authority.startsWith(MOBILE_PREFIX)) {
            foundPrefix = MOBILE_PREFIX;
        }
        if (foundPrefix != null) {
            try {
                uri = new URI(uri.getScheme(), authority.substring(foundPrefix.length()), uri.getPath(), uri.getQuery(), uri.getFragment());
                result = uri.toString();
            } catch (URISyntaxException | NullPointerException e) {
                Log.d(LOGTAG, "Error dropping mobile prefix from: " + aUri + " " + e.getMessage());
            }
        }
        return result;
    }

    public void setUaMode(int mode) {
        if (mState.mSession == null) {
            return;
        }
        mState.mSettings.setUserAgentMode(mode);
        mState.mSession.getSettings().setUserAgentMode(mode);
        String overrideUri = null;
        if (mode == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP) {
            mState.mSettings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
            overrideUri = checkForMobileSite(mState.mUri);
        } else {
            mState.mSettings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        }
        mState.mSession.getSettings().setViewportMode(mState.mSettings.getViewportMode());
        mState.mSession.loadUri(overrideUri != null ? overrideUri : mState.mUri, GeckoSession.LOAD_FLAGS_BYPASS_CACHE | GeckoSession.LOAD_FLAGS_REPLACE_HISTORY);
    }

    protected void setMultiprocess(final boolean aEnabled) {
        if (mState.mSettings.isMultiprocessEnabled() != aEnabled) {
            mState.mSettings.setMultiprocessEnabled(aEnabled);
            recreateSession();
        }
    }

    protected void setTrackingProtection(final boolean aEnabled) {
        if (mState.mSettings.isTrackingProtectionEnabled() != aEnabled) {
            mState.mSettings.setTrackingProtectionEnabled(aEnabled);
            recreateSession();
        }
    }

    public void clearCache(final long clearFlags) {
        if (mRuntime != null) {
            // Per GeckoView Docs:
            // Note: Any open session may re-accumulate previously cleared data.
            // To ensure that no persistent data is left behind, you need to close all sessions prior to clearing data.
            // https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/StorageController.html#clearData-long-
            if (mState.mSession != null) {
                mState.mSession.stop();
                mState.mSession.close();
            }

            mRuntime.getStorageController().clearData(clearFlags).then(aVoid -> {
                recreateSession();
                return null;
            });
        }
    }

    public void updateLastUse() {
        mState.mLastUse = System.currentTimeMillis();
    }

    public long getLastUse() {
        return mState.mLastUse;
    }

    public @NonNull SessionState getSessionState() {
        return mState;
    }

    // NavigationDelegate

    @Override
    public void onLocationChange(@NonNull GeckoSession aSession, String aUri) {
        if (mState.mSession != aSession) {
            return;
        }

        mState.mPreviousUri = mState.mUri;
        mState.mUri = aUri;

        for (GeckoSession.NavigationDelegate listener : mNavigationListeners) {
            listener.onLocationChange(aSession, aUri);
        }

        // The homepage finishes loading after the region has been updated
        if (mState.mRegion != null && aUri.equalsIgnoreCase(SettingsStore.getInstance(mContext).getHomepage())) {
            aSession.loadUri("javascript:window.location.replace('" + getHomeUri() + "');");
        }
    }

    @Override
    public void onCanGoBack(@NonNull GeckoSession aSession, boolean aCanGoBack) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onCanGoBack: " + (aCanGoBack ? "true" : "false"));
        mState.mCanGoBack = aCanGoBack;

        for (GeckoSession.NavigationDelegate listener : mNavigationListeners) {
            listener.onCanGoBack(aSession, aCanGoBack);
        }
    }

    @Override
    public void onCanGoForward(@NonNull GeckoSession aSession, boolean aCanGoForward) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onCanGoForward: " + (aCanGoForward ? "true" : "false"));
        mState.mCanGoForward = aCanGoForward;

        for (GeckoSession.NavigationDelegate listener : mNavigationListeners) {
            listener.onCanGoForward(aSession, aCanGoForward);
        }
    }

    @Override
    public @Nullable GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession aSession, @NonNull LoadRequest aRequest) {
        String uri = aRequest.uri;

        Log.d(LOGTAG, "onLoadRequest: " + uri);

        String uriOverride = SessionUtils.checkYoutubeOverride(uri);
        if (uriOverride != null) {
            aSession.loadUri(uriOverride);
            return GeckoResult.DENY;
        }

        if (aSession == mState.mSession) {
            Log.d(LOGTAG, "Testing for UA override");

            final String userAgentOverride = mUserAgentOverride.lookupOverride(uri);
            aSession.getSettings().setUserAgentOverride(userAgentOverride);
            mState.mSettings.setUserAgentOverride(userAgentOverride);
        }

        if (mContext.getString(R.string.about_private_browsing).equalsIgnoreCase(uri)) {
            return GeckoResult.DENY;
        }

        if (mNavigationListeners.size() == 0) {
            return GeckoResult.ALLOW;
        }

        final GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        AtomicInteger count = new AtomicInteger(0);
        AtomicBoolean allowed = new AtomicBoolean(false);
        for (GeckoSession.NavigationDelegate listener: mNavigationListeners) {
            GeckoResult<AllowOrDeny> listenerResult = listener.onLoadRequest(aSession, aRequest);
            if (listenerResult != null) {
                listenerResult.then(value -> {
                    if (AllowOrDeny.ALLOW.equals(value)) {
                        allowed.set(true);
                    }
                    if (count.getAndIncrement() == mNavigationListeners.size() - 1) {
                        result.complete(allowed.get() ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
                    }

                    return null;
                });

            } else {
                allowed.set(true);
                if (count.getAndIncrement() == mNavigationListeners.size() - 1) {
                    result.complete(allowed.get() ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
                }
            }
        }

        return result;
    }

    @Override
    public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession aSession, @NonNull String aUri) {
        Log.d(LOGTAG, "Session onNewSession: " + aUri);

        Session session = SessionStore.get().createSession(mUsePrivateMode, mState.mSettings);
        return GeckoResult.fromValue(session.getGeckoSession());
    }

    @Override
    public GeckoResult<String> onLoadError(@NonNull GeckoSession session, String uri,  @NonNull WebRequestError error) {
        Log.d(LOGTAG, "Session onLoadError: " + uri);

        return GeckoResult.fromValue(InternalPages.createErrorPageDataURI(mContext, uri, error.category, error.code));
    }

    // Progress Listener

    @Override
    public void onPageStart(@NonNull GeckoSession aSession, @NonNull String aUri) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onPageStart");
        mState.mIsLoading = true;
        TelemetryWrapper.startPageLoadTime();

        for (GeckoSession.ProgressDelegate listener : mProgressListeners) {
            listener.onPageStart(aSession, aUri);
        }
    }

    @Override
    public void onPageStop(@NonNull GeckoSession aSession, boolean b) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onPageStop");
        mState.mIsLoading = false;
        if (!SessionUtils.isLocalizedContent(mState.mUri)) {
            TelemetryWrapper.uploadPageLoadToHistogram(mState.mUri);
        }

        for (GeckoSession.ProgressDelegate listener : mProgressListeners) {
            listener.onPageStop(aSession, b);
        }
    }

    @Override
    public void onSecurityChange(@NonNull GeckoSession aSession, @NonNull SecurityInformation aInformation) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onPageStop");
        mState.mSecurityInformation = aInformation;

        for (GeckoSession.ProgressDelegate listener : mProgressListeners) {
            listener.onSecurityChange(aSession, aInformation);
        }
    }

    @Override
    public void onSessionStateChange(@NonNull GeckoSession aSession,
                                     @NonNull GeckoSession.SessionState aSessionState) {
        if (mState.mSession == aSession) {
            mState.mSessionState = aSessionState;
        }
    }

    // Content Delegate

    @Override
    public void onTitleChange(@NonNull GeckoSession aSession, String aTitle) {
        if (mState.mSession != aSession) {
            return;
        }

        mState.mTitle = aTitle;

        for (GeckoSession.ContentDelegate listener : mContentListeners) {
            listener.onTitleChange(aSession, aTitle);
        }
    }

    @Override
    public void onCloseRequest(@NonNull GeckoSession aSession) {

    }

    @Override
    public void onFullScreen(@NonNull GeckoSession aSession, boolean aFullScreen) {
        if (mState.mSession != aSession) {
            return;
        }
        Log.d(LOGTAG, "Session onFullScreen");
        mState.mFullScreen = aFullScreen;

        for (GeckoSession.ContentDelegate listener : mContentListeners) {
            listener.onFullScreen(aSession, aFullScreen);
        }
    }

    @Override
    public void onContextMenu(@NonNull GeckoSession session, int screenX, int screenY, @NonNull ContextElement element) {
        if (mState.mSession == session) {
            for (GeckoSession.ContentDelegate listener : mContentListeners) {
                listener.onContextMenu(session, screenX, screenY, element);
            }
        }
    }

    @Override
    public void onCrash(@NonNull GeckoSession session) {
        Log.e(LOGTAG,"Child crashed. Creating new session");
        recreateSession();
        loadUri(getHomeUri());
    }

    @Override
    public void onFirstComposite(@NonNull GeckoSession aSession) {
        if (mState.mSession == aSession) {
            for (GeckoSession.ContentDelegate listener : mContentListeners) {
                listener.onFirstComposite(aSession);
            }
        }
    }

    @Override
    public void onFirstContentfulPaint(@NonNull GeckoSession aSession) {
        if (mState.mSession == aSession) {
            for (GeckoSession.ContentDelegate listener : mContentListeners) {
                listener.onFirstContentfulPaint(aSession);
            }
        }
    }

    // TextInput Delegate

    @Override
    public void restartInput(@NonNull GeckoSession aSession, int reason) {
        if (mState.mSession == aSession) {
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.restartInput(aSession, reason);
            }
        }
    }

    @Override
    public void showSoftInput(@NonNull GeckoSession aSession) {
        if (mState.mSession == aSession) {
            mState.mIsInputActive = true;
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.showSoftInput(aSession);
            }
        }
    }

    @Override
    public void hideSoftInput(@NonNull GeckoSession aSession) {
        if (mState.mSession == aSession) {
            mState.mIsInputActive = false;
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.hideSoftInput(aSession);
            }
        }
    }

    @Override
    public void updateSelection(@NonNull GeckoSession aSession, int selStart, int selEnd, int compositionStart, int compositionEnd) {
        if (mState.mSession == aSession) {
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.updateSelection(aSession, selStart, selEnd, compositionStart, compositionEnd);
            }
        }
    }

    @Override
    public void updateExtractedText(@NonNull GeckoSession aSession, @NonNull ExtractedTextRequest request, @NonNull ExtractedText text) {
        if (mState.mSession == aSession) {
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.updateExtractedText(aSession, request, text);
            }
        }
    }

    @Override
    public void updateCursorAnchorInfo(@NonNull GeckoSession aSession, @NonNull CursorAnchorInfo info) {
        if (mState.mSession == aSession) {
            for (GeckoSession.TextInputDelegate listener : mTextInputListeners) {
                listener.updateCursorAnchorInfo(aSession, info);
            }
        }
    }

    @Override
    public void onContentBlocked(@NonNull final GeckoSession session, @NonNull final ContentBlocking.BlockEvent event) {
        if ((event.getAntiTrackingCategory() & ContentBlocking.AntiTracking.AD) != 0) {
          Log.i(LOGTAG, "Blocking Ad: " + event.uri);
        }

        if ((event.getAntiTrackingCategory() & ContentBlocking.AntiTracking.ANALYTIC) != 0) {
            Log.i(LOGTAG, "Blocking Analytic: " + event.uri);
        }

        if ((event.getAntiTrackingCategory() & ContentBlocking.AntiTracking.CONTENT) != 0) {
            Log.i(LOGTAG, "Blocking Content: " + event.uri);
        }

        if ((event.getAntiTrackingCategory() & ContentBlocking.AntiTracking.SOCIAL) != 0) {
            Log.i(LOGTAG, "Blocking Social: " + event.uri);
        }
    }

    // PromptDelegate

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession aSession, @NonNull AlertPrompt alertPrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onAlertPrompt(aSession, alertPrompt);
        }
        return GeckoResult.fromValue(alertPrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession aSession, @NonNull ButtonPrompt buttonPrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onButtonPrompt(aSession, buttonPrompt);
        }
        return GeckoResult.fromValue(buttonPrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession aSession, @NonNull TextPrompt textPrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onTextPrompt(aSession, textPrompt);
        }
        return GeckoResult.fromValue(textPrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession aSession, @NonNull AuthPrompt authPrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onAuthPrompt(aSession, authPrompt);
        }
        return GeckoResult.fromValue(authPrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(@NonNull GeckoSession aSession, @NonNull ChoicePrompt choicePrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onChoicePrompt(aSession, choicePrompt);
        }
        return GeckoResult.fromValue(choicePrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onColorPrompt(@NonNull GeckoSession aSession, @NonNull ColorPrompt colorPrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onColorPrompt(aSession, colorPrompt);
        }
        return GeckoResult.fromValue(colorPrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onDateTimePrompt(@NonNull GeckoSession aSession, @NonNull DateTimePrompt dateTimePrompt) {
        if (mState.mSession == aSession && mPromptDelegate != null) {
            return mPromptDelegate.onDateTimePrompt(aSession, dateTimePrompt);
        }
        return GeckoResult.fromValue(dateTimePrompt.dismiss());
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession aSession, @NonNull FilePrompt filePrompt) {
        if (mPromptDelegate != null) {
            return mPromptDelegate.onFilePrompt(aSession, filePrompt);
        }
        return GeckoResult.fromValue(filePrompt.dismiss());
    }

    // MediaDelegate

    @Override
    public void onMediaAdd(@NonNull GeckoSession aSession, @NonNull MediaElement element) {
        if (mState.mSession != aSession) {
            return;
        }
        Media media = new Media(element);
        mState.mMediaElements.add(media);

        if (mState.mMediaElements.size() == 1) {
            for (VideoAvailabilityListener listener: mVideoAvailabilityListeners) {
                listener.onVideoAvailabilityChanged(true);
            }
        }
    }

    @Override
    public void onMediaRemove(@NonNull GeckoSession aSession, @NonNull MediaElement element) {
        if (mState.mSession != aSession) {
            return;
        }
        for (int i = 0; i < mState.mMediaElements.size(); ++i) {
            Media media = mState.mMediaElements.get(i);
            if (media.getMediaElement() == element) {
                media.unload();
                mState.mMediaElements.remove(i);
                if (mState.mMediaElements.size() == 0) {
                    for (VideoAvailabilityListener listener: mVideoAvailabilityListeners) {
                        listener.onVideoAvailabilityChanged(false);
                    }
                }
                return;
            }
        }
    }

    // HistoryDelegate

    @Override
    public void onHistoryStateChange(@NonNull GeckoSession aSession, @NonNull GeckoSession.HistoryDelegate.HistoryList historyList) {
        if (mState.mSession == aSession && mHistoryDelegate != null) {
            mHistoryDelegate.onHistoryStateChange(aSession, historyList);
        }
    }

    @Nullable
    @Override
    public GeckoResult<Boolean> onVisited(@NonNull GeckoSession aSession, @NonNull String url, @Nullable String lastVisitedURL, int flags) {
        if (mState.mSession == aSession && mHistoryDelegate != null) {
            return mHistoryDelegate.onVisited(aSession, url, lastVisitedURL, flags);
        }

        return GeckoResult.fromValue(false);
    }

    @UiThread
    @Nullable
    public GeckoResult<boolean[]> getVisited(@NonNull GeckoSession aSession, @NonNull String[] urls) {
        if (mState.mSession == aSession && mHistoryDelegate != null) {
            return mHistoryDelegate.getVisited(aSession, urls);
        }

        return GeckoResult.fromValue(new boolean[]{});
    }

    // PermissionDelegate
    @Override
    public void onAndroidPermissionsRequest(@NonNull GeckoSession aSession, @Nullable String[] strings, @NonNull Callback callback) {
        if (mState.mSession == aSession && mPermissionDelegate != null) {
            mPermissionDelegate.onAndroidPermissionsRequest(aSession, strings, callback);
        }
    }

    @Override
    public void onContentPermissionRequest(@NonNull GeckoSession aSession, @Nullable String s, int i, @NonNull Callback callback) {
        if (mState.mSession == aSession && mPermissionDelegate != null) {
            mPermissionDelegate.onContentPermissionRequest(aSession, s, i, callback);
        }
    }

    @Override
    public void onMediaPermissionRequest(@NonNull GeckoSession aSession, @NonNull String s, @Nullable MediaSource[] mediaSources, @Nullable MediaSource[] mediaSources1, @NonNull MediaCallback mediaCallback) {
        if (mState.mSession == aSession && mPermissionDelegate != null) {
            mPermissionDelegate.onMediaPermissionRequest(aSession, s, mediaSources, mediaSources1, mediaCallback);
        }
    }


    // SharedPreferences.OnSharedPreferenceChangeListener

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (mContext != null) {
            if (key.equals(mContext.getString(R.string.settings_key_geolocation_data))) {
                GeolocationData data = GeolocationData.parse(sharedPreferences.getString(key, null));
                setRegion(data.getCountryCode());
            }
        }
    }
}

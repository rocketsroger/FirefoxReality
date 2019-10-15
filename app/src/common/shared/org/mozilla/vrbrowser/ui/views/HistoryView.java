/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.views;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.VRBrowserApplication;
import org.mozilla.vrbrowser.browser.Accounts;
import org.mozilla.vrbrowser.browser.HistoryStore;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.engine.SessionStack;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.databinding.HistoryBinding;
import org.mozilla.vrbrowser.ui.adapters.HistoryAdapter;
import org.mozilla.vrbrowser.ui.callbacks.HistoryCallback;
import org.mozilla.vrbrowser.ui.callbacks.HistoryItemCallback;
import org.mozilla.vrbrowser.utils.SystemUtils;
import org.mozilla.vrbrowser.utils.UIThreadExecutor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.stream.Collectors;

import mozilla.components.concept.storage.VisitInfo;
import mozilla.components.concept.storage.VisitType;
import mozilla.components.concept.sync.AccountObserver;
import mozilla.components.concept.sync.AuthType;
import mozilla.components.concept.sync.OAuthAccount;
import mozilla.components.concept.sync.Profile;
import mozilla.components.service.fxa.SyncEngine;
import mozilla.components.service.fxa.sync.SyncReason;
import mozilla.components.service.fxa.sync.SyncStatusObserver;

public class HistoryView extends FrameLayout implements HistoryStore.HistoryListener {

    private static final String LOGTAG = SystemUtils.createLogtag(HistoryView.class);

    private HistoryBinding mBinding;
    private ObjectAnimator mSyncingAnimation;
    private Accounts mAccounts;
    private HistoryAdapter mHistoryAdapter;
    private boolean mIgnoreNextListener;
    private ArrayList<HistoryCallback> mHistoryViewListeners;

    public HistoryView(Context aContext) {
        super(aContext);
        initialize(aContext);
    }

    public HistoryView(Context aContext, AttributeSet aAttrs) {
        super(aContext, aAttrs);
        initialize(aContext);
    }

    public HistoryView(Context aContext, AttributeSet aAttrs, int aDefStyle) {
        super(aContext, aAttrs, aDefStyle);
        initialize(aContext);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initialize(Context aContext) {
        LayoutInflater inflater = LayoutInflater.from(aContext);

        mHistoryViewListeners = new ArrayList<>();

        // Inflate this data binding layout
        mBinding = DataBindingUtil.inflate(inflater, R.layout.history, this, true);
        mBinding.setCallback(mHistoryCallback);
        mHistoryAdapter = new HistoryAdapter(mHistoryItemCallback, aContext);
        mBinding.historyList.setAdapter(mHistoryAdapter);
        mBinding.historyList.setOnTouchListener((v, event) -> {
            v.requestFocusFromTouch();
            return false;
        });
        mBinding.historyList.setHasFixedSize(true);
        mBinding.historyList.setItemViewCacheSize(20);
        mBinding.historyList.setDrawingCacheEnabled(true);
        mBinding.historyList.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        mBinding.setIsLoading(true);

        Drawable[] drawables = mBinding.syncButton.getCompoundDrawables();
        mSyncingAnimation = ObjectAnimator.ofInt(drawables[0], "level", 0, 10000);
        mSyncingAnimation.setRepeatCount(ObjectAnimator.INFINITE);

        mAccounts = ((VRBrowserApplication)getContext().getApplicationContext()).getAccounts();
        mAccounts.addAccountListener(mAccountListener);
        mAccounts.addSyncListener(mSyncListener);

        mBinding.setIsSignedIn(mAccounts.isSignedIn());
        mBinding.setIsSyncEnabled(mAccounts.isEngineEnabled(SyncEngine.History.INSTANCE));

        updateHistory();
        SessionStore.get().getHistoryStore().addListener(this);

        setVisibility(GONE);

        setOnTouchListener((v, event) -> {
            v.requestFocusFromTouch();
            return false;
        });
    }

    public void onDestroy() {
        SessionStore.get().getHistoryStore().removeListener(this);
        mAccounts.removeAccountListener(mAccountListener);
        mAccounts.removeSyncListener(mSyncListener);
    }

    private final HistoryItemCallback mHistoryItemCallback = new HistoryItemCallback() {
        @Override
        public void onClick(View view, VisitInfo item) {
            mBinding.historyList.requestFocusFromTouch();

            SessionStack sessionStack = SessionStore.get().getActiveStore();
            sessionStack.loadUri(item.getUrl());
        }

        @Override
        public void onDelete(View view, VisitInfo item) {
            mBinding.historyList.requestFocusFromTouch();

            mIgnoreNextListener = true;
            SessionStore.get().getHistoryStore().deleteHistory(item.getUrl(), item.getVisitTime());
            mHistoryAdapter.removeItem(item);
            if (mHistoryAdapter.itemCount() == 0) {
                mBinding.setIsEmpty(true);
                mBinding.setIsLoading(false);
                mBinding.executePendingBindings();
            }
        }

        @Override
        public void onMore(View view, VisitInfo item) {
            mBinding.historyList.requestFocusFromTouch();

            int rowPosition = mHistoryAdapter.getItemPosition(item.getVisitTime());
            RecyclerView.ViewHolder row = mBinding.historyList.findViewHolderForLayoutPosition(rowPosition);
            boolean isLastVisibleItem = false;
            if (mBinding.historyList.getLayoutManager() instanceof LinearLayoutManager) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) mBinding.historyList.getLayoutManager();
                int lastVisibleItem = layoutManager.findLastCompletelyVisibleItemPosition();
                if (rowPosition == layoutManager.findLastVisibleItemPosition() && rowPosition != lastVisibleItem) {
                    isLastVisibleItem = true;
                }
            }

            mBinding.getCallback().onShowContextMenu(
                    row.itemView,
                    item,
                    isLastVisibleItem);
        }
    };

    private HistoryCallback mHistoryCallback = new HistoryCallback() {
        @Override
        public void onClearHistory(@NonNull View view) {
            mHistoryViewListeners.forEach((listener) -> listener.onClearHistory(view));
        }

        @Override
        public void onSyncHistory(@NonNull View view) {
            mAccounts.syncNowAsync(SyncReason.User.INSTANCE, false);
        }

        @Override
        public void onFxALogin(@NonNull View view) {
            mAccounts.getAuthenticationUrlAsync().thenAcceptAsync((url) -> {
                if (url != null) {
                    mAccounts.setLoginOrigin(Accounts.LoginOrigin.HISTORY);
                    SessionStore.get().getActiveStore().loadUri(url);
                }
            });
        }

        @Override
        public void onShowContextMenu(@NonNull View view, @NonNull VisitInfo item, boolean isLastVisibleItem) {
            mHistoryViewListeners.forEach((listener) -> listener.onShowContextMenu(view, item, isLastVisibleItem));
        }
    };

    public void addHistoryListener(@NonNull HistoryCallback listener) {
        if (!mHistoryViewListeners.contains(listener)) {
            mHistoryViewListeners.add(listener);
        }
    }

    public void removeHistoryListener(@NonNull HistoryCallback listener) {
        mHistoryViewListeners.remove(listener);
    }

    private SyncStatusObserver mSyncListener = new SyncStatusObserver() {
        @Override
        public void onStarted() {
            boolean isSyncEnabled = mAccounts.isEngineEnabled(SyncEngine.History.INSTANCE);
            mBinding.setIsSyncEnabled(isSyncEnabled);
            mBinding.executePendingBindings();
            if (isSyncEnabled) {
                mSyncingAnimation.setDuration(500);
                mSyncingAnimation.start();
            }
        }

        @Override
        public void onIdle() {
            if (mAccounts.isEngineEnabled(SyncEngine.History.INSTANCE)) {
                mSyncingAnimation.cancel();
                mBinding.setLastSync(mAccounts.getLastSync());
            }
        }

        @Override
        public void onError(@Nullable Exception e) {
            mBinding.setIsSyncEnabled(mAccounts.isEngineEnabled(SyncEngine.History.INSTANCE));
            mBinding.executePendingBindings();
            if (mAccounts.isEngineEnabled(SyncEngine.History.INSTANCE)) {
                mSyncingAnimation.cancel();
            }
        }
    };

    private AccountObserver mAccountListener = new AccountObserver() {

        @Override
        public void onAuthenticated(@NotNull OAuthAccount oAuthAccount, @NotNull AuthType authType) {
            mBinding.setIsSignedIn(true);
        }

        @Override
        public void onProfileUpdated(@NotNull Profile profile) {
        }

        @Override
        public void onLoggedOut() {
            mBinding.setIsSignedIn(false);
        }

        @Override
        public void onAuthenticationProblems() {
            mBinding.setIsSignedIn(false);
        }
    };

    private void updateHistory() {
        Calendar date = new GregorianCalendar();
        date.set(Calendar.HOUR_OF_DAY, 0);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);

        long currentTime = System.currentTimeMillis();
        long todayLimit = date.getTimeInMillis();
        long yesterdayLimit = todayLimit - SystemUtils.ONE_DAY_MILLIS;
        long oneWeekLimit = todayLimit - SystemUtils.ONE_WEEK_MILLIS;

        SessionStore.get().getHistoryStore().getDetailedHistory().thenAcceptAsync((items) -> {
            List<VisitInfo> orderedItems = items.stream()
                    .sorted(Comparator.comparing((VisitInfo mps) -> mps.getVisitTime())
                    .reversed())
                    .collect(Collectors.toList());

            addSection(orderedItems, getResources().getString(R.string.history_section_today), Long.MAX_VALUE, todayLimit);
            addSection(orderedItems, getResources().getString(R.string.history_section_yesterday), todayLimit, yesterdayLimit);
            addSection(orderedItems, getResources().getString(R.string.history_section_last_week), yesterdayLimit, oneWeekLimit);
            addSection(orderedItems, getResources().getString(R.string.history_section_older), oneWeekLimit, 0);

            showHistory(orderedItems);

        }, new UIThreadExecutor()).exceptionally(throwable -> {
            Log.d(LOGTAG, "Can get the detailed history");
            return null;
        });
    }

    private void addSection(final @NonNull List<VisitInfo> items, @NonNull String section, long rangeStart, long rangeEnd) {
        for (int i=0; i< items.size(); i++) {
            if (items.get(i).getVisitTime() == rangeStart && items.get(i).getVisitType() == VisitType.NOT_A_VISIT)
                break;

            if (items.get(i).getVisitTime() < rangeStart && items.get(i).getVisitTime() > rangeEnd) {
                items.add(i, new VisitInfo(
                        section,
                        section,
                        rangeStart,
                        VisitType.NOT_A_VISIT
                ));
                break;
            }
        }
    }

    private void showHistory(List<VisitInfo> historyItems) {
        if (historyItems == null || historyItems.size() == 0) {
            mBinding.setIsEmpty(true);
            mBinding.setIsLoading(false);

        } else {
            mBinding.setIsEmpty(false);
            mBinding.setIsLoading(false);
            mHistoryAdapter.setHistoryList(historyItems);
            mBinding.historyList.post(() -> mBinding.historyList.smoothScrollToPosition(0));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        double width = Math.ceil(getWidth()/getContext().getResources().getDisplayMetrics().density);
        int firstVisibleItem = ((LinearLayoutManager)mBinding.historyList.getLayoutManager()).findFirstVisibleItemPosition();
        int lastVisibleItem = ((LinearLayoutManager)mBinding.historyList.getLayoutManager()).findLastVisibleItemPosition();
        mHistoryAdapter.setNarrow(width < SettingsStore.WINDOW_WIDTH_DEFAULT, firstVisibleItem, lastVisibleItem);
    }

    // HistoryStore.HistoryListener
    @Override
    public void onHistoryUpdated() {
        if (mIgnoreNextListener) {
            mIgnoreNextListener = false;
            return;
        }
        updateHistory();
    }
}

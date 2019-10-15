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
import org.mozilla.vrbrowser.browser.AccountsManager;
import org.mozilla.vrbrowser.browser.HistoryStore;
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
    private AccountsManager mAccountManager;
    private HistoryAdapter mHistoryAdapter;
    private boolean mIgnoreNextListener;
    private boolean mIsSyncEnabled;
    private boolean mIsSignedIn;
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
        mBinding.setIsLoading(true);
        mBinding.executePendingBindings();

        Drawable[] drawables = mBinding.syncButton.getCompoundDrawables();
        mSyncingAnimation = ObjectAnimator.ofInt(drawables[0], "level", 0, 10000);
        mSyncingAnimation.setRepeatCount(ObjectAnimator.INFINITE);

        updateHistory();
        SessionStore.get().getHistoryStore().addListener(this);

        mAccountManager = SessionStore.get().getAccountsManager();
        mAccountManager.addAccountListener(mAccountListener);
        mAccountManager.addSyncListener(mSyncListener);

        mIsSyncEnabled = mAccountManager.getSyncEngineStatus(SyncEngine.History.INSTANCE);

        updateCurrentAccountState();

        setVisibility(GONE);

        setOnTouchListener((v, event) -> {
            v.requestFocusFromTouch();
            return false;
        });
    }

    public void onDestroy() {
        SessionStore.get().getHistoryStore().removeListener(this);
        mAccountManager.removeAccountListener(mAccountListener);
        mAccountManager.removeSyncListener(mSyncListener);
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
            switch(mAccountManager.getAccountStatus()) {
                case NEEDS_RECONNECT:
                case SIGNED_OUT:
                    mAccountManager.getAuthenticationUrlAsync().thenAcceptAsync((url) -> {
                        if (url != null) {
                            mAccountManager.setLoginOrigin(AccountsManager.LoginOrigin.HISTORY);
                            SessionStore.get().getActiveStore().loadUri(url);
                        }
                    });
                    break;

                case SIGNED_IN:
                    mAccountManager.syncNowAsync(SyncReason.User.INSTANCE, false);

                    mHistoryViewListeners.forEach((listener) -> listener.onSyncHistory(view));
                    break;

                default:
                    throw new IllegalStateException("Unexpected value: " + mAccountManager.getAccountStatus());
            }
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
            if (mAccountManager.getSyncEngineStatus(SyncEngine.History.INSTANCE)) {
                mBinding.syncButton.setEnabled(false);
                mSyncingAnimation.start();
            }
        }

        @Override
        public void onIdle() {
            mIsSyncEnabled = mAccountManager.getSyncEngineStatus(SyncEngine.History.INSTANCE);
            if (mIsSyncEnabled) {
                mBinding.syncButton.setEnabled(true);
                mSyncingAnimation.cancel();
            }
            updateUi();
        }

        @Override
        public void onError(@Nullable Exception e) {
            if (mAccountManager.getSyncEngineStatus(SyncEngine.History.INSTANCE)) {
                mBinding.syncButton.setEnabled(true);
                mSyncingAnimation.cancel();
                mBinding.syncDescription.setText(getContext().getString(R.string.fxa_account_last_no_synced));
            }
        }
    };

    private void updateCurrentAccountState() {
        switch(mAccountManager.getAccountStatus()) {
            case NEEDS_RECONNECT:
            case SIGNED_OUT:
                mIsSignedIn = false;
                updateUi();
                break;

            case SIGNED_IN:
                mIsSignedIn = true;
                updateUi();
                break;

            default:
                throw new IllegalStateException("Unexpected value: " + mAccountManager.getAccountStatus());
        }
    }

    private AccountObserver mAccountListener = new AccountObserver() {

        @Override
        public void onAuthenticated(@NotNull OAuthAccount oAuthAccount, @NotNull AuthType authType) {
            mIsSignedIn = true;
            updateUi();
        }

        @Override
        public void onProfileUpdated(@NotNull Profile profile) {
        }

        @Override
        public void onLoggedOut() {
            mIsSignedIn = false;
            updateUi();
        }

        @Override
        public void onAuthenticationProblems() {
            mIsSignedIn = false;
            updateUi();
        }
    };

    private void updateUi() {
        if (mIsSignedIn) {
            mBinding.syncButton.setText(R.string.history_sync);
            mBinding.syncDescription.setVisibility(VISIBLE);

            if (mIsSyncEnabled) {
                mBinding.syncButton.setEnabled(true);
                long lastSync = mAccountManager.getLastSync();
                if (lastSync == 0) {
                    mBinding.syncDescription.setText(getContext().getString(R.string.fxa_account_last_no_synced));

                } else {
                    long timeDiff = System.currentTimeMillis() - lastSync;
                    if (timeDiff < 60000) {
                        mBinding.syncDescription.setText(getContext().getString(R.string.fxa_account_last_synced_now));

                    } else {
                        mBinding.syncDescription.setText(getContext().getString(R.string.fxa_account_last_synced, timeDiff / 60000));
                    }
                }

            } else {
                mBinding.syncButton.setEnabled(false);
                mBinding.syncDescription.setVisibility(GONE);
            }

        } else {
            mBinding.syncButton.setEnabled(true);
            mBinding.syncButton.setText(R.string.fxa_account_sing_to_sync);
            mBinding.syncDescription.setVisibility(GONE);
        }
    }

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
        mBinding.executePendingBindings();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        double width = Math.ceil(getWidth()/getContext().getResources().getDisplayMetrics().density);
        mHistoryAdapter.setNarrow(width < SettingsStore.WINDOW_WIDTH_DEFAULT);
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

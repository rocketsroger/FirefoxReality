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
import org.mozilla.vrbrowser.browser.BookmarksStore;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.engine.SessionStack;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.databinding.BookmarksBinding;
import org.mozilla.vrbrowser.ui.adapters.Bookmark;
import org.mozilla.vrbrowser.ui.adapters.BookmarkAdapter;
import org.mozilla.vrbrowser.ui.adapters.CustomLinearLayoutManager;
import org.mozilla.vrbrowser.ui.callbacks.BookmarkItemCallback;
import org.mozilla.vrbrowser.ui.callbacks.BookmarksCallback;
import org.mozilla.vrbrowser.utils.UIThreadExecutor;

import java.util.ArrayList;
import java.util.List;

import mozilla.appservices.places.BookmarkRoot;
import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.sync.AccountObserver;
import mozilla.components.concept.sync.AuthType;
import mozilla.components.concept.sync.OAuthAccount;
import mozilla.components.concept.sync.Profile;
import mozilla.components.service.fxa.SyncEngine;
import mozilla.components.service.fxa.sync.SyncReason;
import mozilla.components.service.fxa.sync.SyncStatusObserver;

public class BookmarksView extends FrameLayout implements BookmarksStore.BookmarkListener {

    private BookmarksBinding mBinding;
    private ObjectAnimator mSyncingAnimation;
    private AccountsManager mAccountManager;
    private BookmarkAdapter mBookmarkAdapter;
    private boolean mIgnoreNextListener;
    private ArrayList<BookmarksCallback> mBookmarksViewListeners;
    private CustomLinearLayoutManager mLayoutManager;

    public BookmarksView(Context aContext) {
        super(aContext);
        initialize(aContext);
    }

    public BookmarksView(Context aContext, AttributeSet aAttrs) {
        super(aContext, aAttrs);
        initialize(aContext);
    }

    public BookmarksView(Context aContext, AttributeSet aAttrs, int aDefStyle) {
        super(aContext, aAttrs, aDefStyle);
        initialize(aContext);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initialize(Context aContext) {
        LayoutInflater inflater = LayoutInflater.from(aContext);

        mBookmarksViewListeners = new ArrayList<>();

        // Inflate this data binding layout
        mBinding = DataBindingUtil.inflate(inflater, R.layout.bookmarks, this, true);
        mBinding.setCallback(mBookmarksCallback);
        mBookmarkAdapter = new BookmarkAdapter(mBookmarkItemCallback, aContext);
        mBinding.bookmarksList.setAdapter(mBookmarkAdapter);
        mBinding.bookmarksList.setOnTouchListener((v, event) -> {
            v.requestFocusFromTouch();
            return false;
        });
        mBinding.bookmarksList.setHasFixedSize(true);
        mBinding.bookmarksList.setItemViewCacheSize(20);
        mBinding.bookmarksList.setDrawingCacheEnabled(true);
        mBinding.bookmarksList.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        mLayoutManager = (CustomLinearLayoutManager) mBinding.bookmarksList.getLayoutManager();

        mBinding.setIsLoading(true);

        Drawable[] drawables = mBinding.syncButton.getCompoundDrawables();
        mSyncingAnimation = ObjectAnimator.ofInt(drawables[0], "level", 0, 10000);
        mSyncingAnimation.setRepeatCount(ObjectAnimator.INFINITE);

        mAccountManager = SessionStore.get().getAccountsManager();
        mAccountManager.addAccountListener(mAccountListener);
        mAccountManager.addSyncListener(mSyncListener);

        mBinding.setIsSignedIn(mAccountManager.isSignedIn());
        mBinding.setIsSyncEnabled(mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE));

        updateBookmarks();
        SessionStore.get().getBookmarkStore().addListener(this);

        setVisibility(GONE);

        setOnTouchListener((v, event) -> {
            v.requestFocusFromTouch();
            return false;
        });
    }

    public void onDestroy() {
        SessionStore.get().getBookmarkStore().removeListener(this);
        mAccountManager.removeAccountListener(mAccountListener);
        mAccountManager.removeSyncListener(mSyncListener);
    }

    private final BookmarkItemCallback mBookmarkItemCallback = new BookmarkItemCallback() {
        @Override
        public void onClick(@NonNull View view, @NonNull Bookmark item) {
            mBinding.bookmarksList.requestFocusFromTouch();

            SessionStack sessionStack = SessionStore.get().getActiveStore();
            sessionStack.loadUri(item.getUrl());
        }

        @Override
        public void onDelete(@NonNull View view, @NonNull Bookmark item) {
            mBinding.bookmarksList.requestFocusFromTouch();

            mIgnoreNextListener = true;
            SessionStore.get().getBookmarkStore().deleteBookmarkById(item.getGuid());
            mBookmarkAdapter.removeItem(item);
            if (mBookmarkAdapter.itemCount() == 0) {
                mBinding.setIsEmpty(true);
                mBinding.setIsLoading(false);
                mBinding.executePendingBindings();
            }
        }

        @Override
        public void onMore(@NonNull View view, @NonNull Bookmark item) {
            mBinding.bookmarksList.requestFocusFromTouch();

            int rowPosition = mBookmarkAdapter.getItemPosition(item.getGuid());
            RecyclerView.ViewHolder row = mBinding.bookmarksList.findViewHolderForLayoutPosition(rowPosition);
            boolean isLastVisibleItem = false;
            if (mBinding.bookmarksList.getLayoutManager() instanceof LinearLayoutManager) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) mBinding.bookmarksList.getLayoutManager();
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

        @Override
        public void onFolderOpened(@NonNull Bookmark item) {
            int position = mBookmarkAdapter.getItemPosition(item.getGuid());
            mLayoutManager.scrollToPositionWithOffset(position, 20);
        }
    };

    private BookmarksCallback mBookmarksCallback = new BookmarksCallback() {
        @Override
        public void onClearBookmarks(@NonNull View view) {
            mBookmarksViewListeners.forEach((listener) -> listener.onClearBookmarks(view));
        }

        @Override
        public void onSyncBookmarks(@NonNull View view) {
            mAccountManager.syncNowAsync(SyncReason.User.INSTANCE, false);
        }

        @Override
        public void onFxALogin(@NonNull View view) {
            mAccountManager.getAuthenticationUrlAsync().thenAcceptAsync((url) -> {
                if (url != null) {
                    mAccountManager.setLoginOrigin(AccountsManager.LoginOrigin.HISTORY);
                    SessionStore.get().getActiveStore().loadUri(url);
                }
            });
        }

        @Override
        public void onShowContextMenu(@NonNull View view, Bookmark item, boolean isLastVisibleItem) {
            mBookmarksViewListeners.forEach((listener) -> listener.onShowContextMenu(view, item, isLastVisibleItem));
        }
    };

    public void addBookmarksListener(@NonNull BookmarksCallback listener) {
        if (!mBookmarksViewListeners.contains(listener)) {
            mBookmarksViewListeners.add(listener);
        }
    }

    public void removeBookmarksListener(@NonNull BookmarksCallback listener) {
        mBookmarksViewListeners.remove(listener);
    }

    private SyncStatusObserver mSyncListener = new SyncStatusObserver() {
        @Override
        public void onStarted() {
            mBinding.setIsSyncEnabled(mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE));
            mBinding.executePendingBindings();
            if (mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE)) {
                mSyncingAnimation.setDuration(500);
                mSyncingAnimation.start();
            }
        }

        @Override
        public void onIdle() {
            if (mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE)) {
                mSyncingAnimation.cancel();
                mBinding.setLastSync(mAccountManager.getLastSync());
            }
        }

        @Override
        public void onError(@Nullable Exception e) {
            mBinding.setIsSyncEnabled(mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE));
            mBinding.executePendingBindings();
            if (mAccountManager.isEngineEnabled(SyncEngine.Bookmarks.INSTANCE)) {
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

    private void updateBookmarks() {
        SessionStore.get().getBookmarkStore().getTree(BookmarkRoot.Root.getId(), true).thenAcceptAsync(this::showBookmarks, new UIThreadExecutor());
    }

    private void showBookmarks(List<BookmarkNode> aBookmarks) {
        if (aBookmarks == null || aBookmarks.size() == 0) {
            mBinding.setIsEmpty(true);
            mBinding.setIsLoading(false);

        } else {
            mBinding.setIsEmpty(false);
            mBinding.setIsLoading(false);
            mBookmarkAdapter.setBookmarkList(aBookmarks);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        double width = Math.ceil(getWidth()/getContext().getResources().getDisplayMetrics().density);
        int firstVisibleItem = ((LinearLayoutManager)mBinding.bookmarksList.getLayoutManager()).findFirstVisibleItemPosition();
        int lastVisibleItem = ((LinearLayoutManager)mBinding.bookmarksList.getLayoutManager()).findLastVisibleItemPosition();
        mBookmarkAdapter.setNarrow(width < SettingsStore.WINDOW_WIDTH_DEFAULT, firstVisibleItem, lastVisibleItem);
    }

    // BookmarksStore.BookmarksViewListener
    @Override
    public void onBookmarksUpdated() {
        if (mIgnoreNextListener) {
            mIgnoreNextListener = false;
            return;
        }
        updateBookmarks();
    }

    @Override
    public void onBookmarkAdded() {
        if (mIgnoreNextListener) {
            mIgnoreNextListener = false;
            return;
        }
        updateBookmarks();
    }
}

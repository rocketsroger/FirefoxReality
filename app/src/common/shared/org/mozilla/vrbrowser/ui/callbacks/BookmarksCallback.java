package org.mozilla.vrbrowser.ui.callbacks;

import android.view.View;

import androidx.annotation.NonNull;

import org.mozilla.vrbrowser.ui.adapters.Bookmark;

public interface BookmarksCallback {
    default void onClearBookmarks(@NonNull View view) {}
    default void onSyncBookmarks(@NonNull View view) {}
    default void onShowContextMenu(@NonNull View view, Bookmark item, boolean isLastVisibleItem) {}
}

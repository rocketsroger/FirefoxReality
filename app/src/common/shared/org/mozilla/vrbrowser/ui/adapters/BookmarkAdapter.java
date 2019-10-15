package org.mozilla.vrbrowser.ui.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.databinding.BookmarkItemBinding;
import org.mozilla.vrbrowser.databinding.BookmarkItemFolderBinding;
import org.mozilla.vrbrowser.databinding.BookmarkSeparatorBinding;
import org.mozilla.vrbrowser.ui.callbacks.BookmarkItemCallback;
import org.mozilla.vrbrowser.ui.callbacks.BookmarkItemFolderCallback;
import org.mozilla.vrbrowser.ui.widgets.WidgetPlacement;
import org.mozilla.vrbrowser.utils.AnimationHelper;
import org.mozilla.vrbrowser.utils.SystemUtils;

import java.util.List;
import java.util.Objects;

import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.storage.BookmarkNodeType;

public class BookmarkAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final String LOGTAG = SystemUtils.createLogtag(BookmarkAdapter.class);

    private static final int ICON_ANIMATION_DURATION = 200;

    private List<BookmarkNode> mBookmarksList;
    private List<Bookmark> mDisplayList;

    private int mMinPadding;
    private int mMaxPadding;
    private int mIconColorHover;
    private int mIconNormalColor;
    private boolean mIsNarrowLayout;

    @Nullable
    private final BookmarkItemCallback mBookmarkItemCallback;

    public BookmarkAdapter(@Nullable BookmarkItemCallback clickCallback, Context aContext) {
        mBookmarkItemCallback = clickCallback;

        mMinPadding = WidgetPlacement.pixelDimension(aContext, R.dimen.library_icon_padding_min);
        mMaxPadding = WidgetPlacement.pixelDimension(aContext, R.dimen.library_icon_padding_max);

        mIconColorHover = aContext.getResources().getColor(R.color.white, aContext.getTheme());
        mIconNormalColor = aContext.getResources().getColor(R.color.rhino, aContext.getTheme());

        mIsNarrowLayout = false;

        setHasStableIds(false);
    }

    public void setNarrow(boolean isNarrow, int first, int last) {
        if (mIsNarrowLayout != isNarrow) {
            mIsNarrowLayout = isNarrow;
            notifyItemRangeChanged(first, last);
        }
    }

    public void setBookmarkList(final List<BookmarkNode> bookmarkList) {
        mBookmarksList = bookmarkList;

        List<Bookmark> newDisplayList;
        if (mDisplayList == null || mDisplayList.isEmpty()) {
            newDisplayList = Bookmark.getRootDisplayListTree(mBookmarksList);
            mDisplayList = newDisplayList;
            notifyItemRangeInserted(0, mDisplayList.size());

        } else {
            List<String> openFoldersGuid = Bookmark.getOpenFoldersGuid(mDisplayList);
            newDisplayList = Bookmark.getDisplayListTree(mBookmarksList, openFoldersGuid);
            notifyDiff(newDisplayList);
        }
    }

    private void notifyDiff(List<Bookmark> newDisplayList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mDisplayList.size();
            }

            @Override
            public int getNewListSize() {
                return newDisplayList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return mDisplayList.get(oldItemPosition).getGuid().equals(newDisplayList.get(newItemPosition).getGuid()) &&
                        mDisplayList.get(oldItemPosition).isExpanded() == newDisplayList.get(newItemPosition).isExpanded();
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                Bookmark newBookmark = newDisplayList.get(newItemPosition);
                Bookmark oldBookmark = mDisplayList.get(oldItemPosition);
                return newBookmark.getGuid().equals(oldBookmark.getGuid())
                        && Objects.equals(newBookmark.getTitle(), oldBookmark.getTitle())
                        && Objects.equals(newBookmark.getUrl(), oldBookmark.getUrl())
                        && newBookmark.isExpanded() == oldBookmark.isExpanded();
            }
        });

        mDisplayList = newDisplayList;
        result.dispatchUpdatesTo(this);
    }

    public void removeItem(Bookmark aBookmark) {
        int position = mDisplayList.indexOf(aBookmark);
        if (position >= 0) {
            mDisplayList.remove(position);
            notifyItemRemoved(position);
        }
    }

    public int itemCount() {
        return mDisplayList != null ? mDisplayList.size() : 0;
    }

    public int getItemPosition(String id) {
        for (int position=0; position<mDisplayList.size(); position++)
            if (mDisplayList.get(position).getGuid().equalsIgnoreCase(id))
                return position;
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        switch (mDisplayList.get(position).getType()) {
            case FOLDER:
                return BookmarkNodeType.FOLDER.ordinal();
            case ITEM:
                return BookmarkNodeType.ITEM.ordinal();
            case SEPARATOR:
                return BookmarkNodeType.SEPARATOR.ordinal();
        }

        return 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == BookmarkNodeType.ITEM.ordinal()) {
            BookmarkItemBinding binding = DataBindingUtil
                    .inflate(LayoutInflater.from(parent.getContext()), R.layout.bookmark_item,
                            parent, false);

            binding.setCallback(mBookmarkItemCallback);
            binding.setIsHovered(false);
            binding.setIsNarrow(mIsNarrowLayout);
            binding.layout.setOnHoverListener((view, motionEvent) -> {
                int ev = motionEvent.getActionMasked();
                switch (ev) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        binding.setIsHovered(true);
                        return false;

                    case MotionEvent.ACTION_HOVER_EXIT:
                        binding.setIsHovered(false);
                        return false;
                }

                return false;
            });
            binding.layout.setOnTouchListener((view, motionEvent) -> {
                int ev = motionEvent.getActionMasked();
                switch (ev) {
                    case MotionEvent.ACTION_UP:
                        return false;

                    case MotionEvent.ACTION_DOWN:
                        binding.setIsHovered(true);
                        return false;

                    case MotionEvent.ACTION_CANCEL:
                        binding.setIsHovered(false);
                        return false;
                }
                return false;
            });
            binding.more.setOnHoverListener(mIconHoverListener);
            binding.more.setOnTouchListener((view, motionEvent) -> {
                int ev = motionEvent.getActionMasked();
                switch (ev) {
                    case MotionEvent.ACTION_UP:
                        binding.setIsHovered(true);
                        mBookmarkItemCallback.onMore(view, binding.getItem());
                        return true;

                    case MotionEvent.ACTION_DOWN:
                        binding.setIsHovered(true);
                        return true;
                }
                return false;
            });
            binding.trash.setOnHoverListener(mIconHoverListener);
            binding.trash.setOnTouchListener((view, motionEvent) -> {
                int ev = motionEvent.getActionMasked();
                switch (ev) {
                    case MotionEvent.ACTION_UP:
                        binding.setIsHovered(true);
                        mBookmarkItemCallback.onDelete(view, binding.getItem());
                        return true;

                    case MotionEvent.ACTION_DOWN:
                        binding.setIsHovered(true);
                        return true;
                }
                return false;
            });

            return new BookmarkViewHolder(binding);

        } else if (viewType == BookmarkNodeType.FOLDER.ordinal()) {
            BookmarkItemFolderBinding binding = DataBindingUtil
                    .inflate(LayoutInflater.from(parent.getContext()), R.layout.bookmark_item_folder,
                            parent, false);
            binding.setCallback(mBookmarkItemFolderCallback);

            return new BookmarkFolderViewHolder(binding);

        } else if (viewType == BookmarkNodeType.SEPARATOR.ordinal()) {
            BookmarkSeparatorBinding binding = DataBindingUtil
                    .inflate(LayoutInflater.from(parent.getContext()), R.layout.bookmark_separator,
                            parent, false);

            return new BookmarkSeparatorViewHolder(binding);
        }

        throw new IllegalArgumentException("Invalid view Type");
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Bookmark item = mDisplayList.get(position);

        if (holder instanceof BookmarkViewHolder) {
            BookmarkViewHolder bookmarkHolder = (BookmarkViewHolder) holder;
            bookmarkHolder.binding.setItem(item);
            bookmarkHolder.binding.setIsNarrow(mIsNarrowLayout);

        } else if (holder instanceof BookmarkFolderViewHolder) {
            BookmarkFolderViewHolder bookmarkHolder = (BookmarkFolderViewHolder) holder;
            bookmarkHolder.binding.setItem(item);
            bookmarkHolder.binding.executePendingBindings();

        } else if (holder instanceof BookmarkSeparatorViewHolder) {
            BookmarkSeparatorViewHolder bookmarkHolder = (BookmarkSeparatorViewHolder) holder;
            bookmarkHolder.binding.setItem(item);
        }
    }

    @Override
    public int getItemCount() {
        return mDisplayList == null ? 0 : mDisplayList.size();
    }

    @Override
    public long getItemId(int position) {
        Bookmark bookmark = mDisplayList.get(position);
        return  bookmark.getPosition();
    }

    static class BookmarkViewHolder extends RecyclerView.ViewHolder {

        final BookmarkItemBinding binding;

        BookmarkViewHolder(@NonNull BookmarkItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class BookmarkFolderViewHolder extends RecyclerView.ViewHolder {

        final BookmarkItemFolderBinding binding;

        BookmarkFolderViewHolder(@NonNull BookmarkItemFolderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class BookmarkSeparatorViewHolder extends RecyclerView.ViewHolder {

        final BookmarkSeparatorBinding binding;

        BookmarkSeparatorViewHolder(@NonNull BookmarkSeparatorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private View.OnHoverListener mIconHoverListener = (view, motionEvent) -> {
        ImageView icon = (ImageView)view;
        int ev = motionEvent.getActionMasked();
        switch (ev) {
            case MotionEvent.ACTION_HOVER_ENTER:
                icon.setColorFilter(mIconColorHover);
                AnimationHelper.animateViewPadding(view,
                        mMaxPadding,
                        mMinPadding,
                        ICON_ANIMATION_DURATION);
                return false;

            case MotionEvent.ACTION_HOVER_EXIT:
                AnimationHelper.animateViewPadding(view,
                        mMinPadding,
                        mMaxPadding,
                        ICON_ANIMATION_DURATION,
                        () -> icon.setColorFilter(mIconNormalColor));
                return false;
        }

        return false;
    };

    private BookmarkItemFolderCallback mBookmarkItemFolderCallback = new BookmarkItemFolderCallback() {
        @Override
        public void onClick(View view, Bookmark item) {
            List<String> openFoldersGuid = Bookmark.getOpenFoldersGuid(mDisplayList);

            for (Bookmark bookmark : mDisplayList) {
                if (bookmark.getGuid().equals(item.getGuid())) {
                    if (item.isExpanded()) {
                        openFoldersGuid.remove(bookmark.getGuid());

                    } else {
                        openFoldersGuid.add(bookmark.getGuid());
                    }
                    break;
                }
            }

            List<Bookmark> newDisplayList = Bookmark.getDisplayListTree(mBookmarksList, openFoldersGuid);
            notifyDiff(newDisplayList);

            mBookmarkItemCallback.onFolderOpened(item);
        }
    };

}

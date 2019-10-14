package org.mozilla.vrbrowser.ui.widgets;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.browser.engine.Session;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.ui.views.TabView;
import org.mozilla.vrbrowser.ui.views.UIButton;
import org.mozilla.vrbrowser.ui.views.UITextButton;
import org.mozilla.vrbrowser.utils.ViewUtils;

import java.util.ArrayList;

public class TabsWidget extends UIWidget implements WidgetManagerDelegate.FocusChangeListener {
    protected RecyclerView mTabsList;
    protected GridLayoutManager mLayoutManager;
    protected TabAdapter mAdapter;
    protected boolean mPrivateMode;
    protected TabDelegate mTabDelegate;
    protected TextView mTabsAvailableCounter;
    protected TextView mSelectedTabsCounter;
    protected UITextButton mSelectTabsButton;
    protected UITextButton mDoneButton;
    protected UITextButton mCloseTabsButton;
    protected UITextButton mCloseTabsAllButton;
    protected UITextButton mSelectAllButton;
    protected UITextButton mUnselectTabs;
    protected LinearLayout mTabsSelectModeView;
    protected View mTabSelectModeSeparator;

    protected boolean mSelecting;
    protected ArrayList<Session> mSelectedTabs = new ArrayList<>();

    public interface TabDelegate {
        void onTabSelect(Session aTab);
        void onTabAdd();
        void onTabsClose(ArrayList<Session> aTabs);
    }

    public TabsWidget(Context aContext, boolean aPrivateMode) {
        super(aContext);
        mPrivateMode = aPrivateMode;
        initialize();
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        Context context = getContext();
        aPlacement.width = WidgetPlacement.dpDimension(context, R.dimen.tabs_width);
        aPlacement.height = WidgetPlacement.dpDimension(context, R.dimen.tabs_height);
        aPlacement.worldWidth = WidgetPlacement.floatDimension(getContext(), R.dimen.window_world_width) * aPlacement.width/getWorldWidth();
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.parentAnchorX = 0.5f;
        aPlacement.parentAnchorY = 0.5f;
        aPlacement.translationZ = WidgetPlacement.floatDimension(context, R.dimen.context_menu_z_distance);
        aPlacement.visible = false;
    }

    private void initialize() {
        inflate(getContext(), R.layout.tabs, this);
        mTabsList = findViewById(R.id.tabsRecyclerView);
        mTabsList.setHasFixedSize(true);
        final int columns = 4;
        mLayoutManager = new GridLayoutManager(getContext(), columns);
        mTabsList.setLayoutManager(mLayoutManager);
        mTabsList.addItemDecoration(new GridSpacingItemDecoration(getContext(), columns));

        mTabsAvailableCounter = findViewById(R.id.tabsAvailableCounter);
        mSelectedTabsCounter = findViewById(R.id.tabsSelectedCounter);
        mTabSelectModeSeparator = findViewById(R.id.tabsSelectModeSeparator);

        // specify an adapter (see also next example)
        mAdapter = new TabAdapter();
        mTabsList.setAdapter(mAdapter);

        mTabsSelectModeView = findViewById(R.id.tabsSelectModeView);

        UIButton backButton = findViewById(R.id.tabsBackButton);
        backButton.setOnClickListener(view -> {
            view.requestFocusFromTouch();
            onDismiss();
        });

        mSelectTabsButton = findViewById(R.id.tabsSelectButton);
        mSelectTabsButton.setOnClickListener(view -> {
            enterSelectMode();
        });

        mDoneButton = findViewById(R.id.tabsDoneButton);
        mDoneButton.setOnClickListener(view -> {
            exitSelectMode();
        });

        mCloseTabsButton = findViewById(R.id.tabsCloseButton);
        mCloseTabsButton.setOnClickListener(v -> {
            if (mTabDelegate != null) {
                mTabDelegate.onTabsClose(mSelectedTabs);
            }
            onDismiss();
        });

        mCloseTabsAllButton = findViewById(R.id.tabsCloseAllButton);
        mCloseTabsAllButton.setOnClickListener(v -> {
            if (mTabDelegate != null) {
                mTabDelegate.onTabsClose(mAdapter.mTabs);
            }
            onDismiss();
        });

        mSelectAllButton = findViewById(R.id.tabsSelectAllButton);
        mSelectAllButton.setOnClickListener(v -> {
            mSelectedTabs = new ArrayList<>(mAdapter.mTabs);
            mAdapter.notifyDataSetChanged();
            updateSelectionMode();
        });

        mUnselectTabs = findViewById(R.id.tabsUnselectButton);
        mUnselectTabs.setOnClickListener(v -> {
            mSelectedTabs.clear();
            mAdapter.notifyDataSetChanged();
            updateSelectionMode();
        });
    }


    @Override
    public void show(int aShowFlags) {
        super.show(aShowFlags);
        mAdapter.updateTabs(SessionStore.get().getSortedSessions(mPrivateMode));
        mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);
        mWidgetManager.addFocusChangeListener(this);
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        super.hide(aHideFlags);

        mWidgetManager.popWorldBrightness(this);
        mWidgetManager.removeFocusChangeListener(this);
    }

    public void setTabDelegate(TabDelegate aDelegate) {
        mTabDelegate = aDelegate;
    }

    public class TabAdapter extends RecyclerView.Adapter<TabAdapter.MyViewHolder> {
        private ArrayList<Session> mTabs = new ArrayList<>();

        class MyViewHolder extends RecyclerView.ViewHolder {
            // each data item is just a string in this case
            TabView tabView;
            MyViewHolder(TabView v) {
                super(v);
                tabView = v;
            }

        }

        TabAdapter() {}

        void updateTabs(ArrayList<Session> aTabs) {
            mTabs = aTabs;
            notifyDataSetChanged();

            if (mTabs.size() > 1) {
                mTabsAvailableCounter.setText(getContext().getString(R.string.tabs_counter_plural, String.valueOf(mTabs.size())));
            } else {
                mTabsAvailableCounter.setText(R.string.tabs_counter_singular);
            }
        }

        @Override
        public TabAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TabView view = (TabView)LayoutInflater.from(parent.getContext()).inflate(R.layout.tab_view, parent, false);
            return new MyViewHolder(view);
        }

        @Override
        public void onBindViewHolder(MyViewHolder holder, int position) {
            if (position > 0) {
                Session session = mTabs.get(position - 1);
                holder.tabView.attachToSession(session);
            } else {
                holder.tabView.setAddTabMode(true);
            }

            holder.tabView.setSelecting(mSelecting);
            holder.tabView.setSelected(mSelectedTabs.contains(holder.tabView.getSession()));
            holder.tabView.setActive(SessionStore.get().getActiveSession() == holder.tabView.getSession());
            holder.tabView.setDelegate(new TabView.Delegate() {
                @Override
                public void onClose(TabView aSender) {
                    if (mTabDelegate != null) {
                        ArrayList<Session> closed = new ArrayList<>();
                        closed.add(aSender.getSession());
                        mTabDelegate.onTabsClose(closed);
                    }
                    if (mTabs.size() > 1) {
                        mTabs.remove(holder.getAdapterPosition() - 1);
                        mAdapter.notifyItemRemoved(holder.getAdapterPosition());
                    } else {
                        onDismiss();
                    }
                }

                @Override
                public void onClick(TabView aSender) {
                    if (mSelecting) {
                        if (aSender.isSelected()) {
                            aSender.setSelected(false);
                            mSelectedTabs.remove(aSender.getSession());
                        } else {
                            aSender.setSelected(true);
                            mSelectedTabs.add(aSender.getSession());
                        }
                        updateSelectionMode();
                        return;
                    }
                    if (mTabDelegate != null) {
                        mTabDelegate.onTabSelect(aSender.getSession());
                    }
                    onDismiss();
                }

                @Override
                public void onAdd(TabView aSender) {
                    if (mTabDelegate != null) {
                        mTabDelegate.onTabAdd();
                    }
                    onDismiss();
                }
            });
        }

        @Override
        public int getItemCount() {
            return mTabs.size() + 1;
        }
    }

    private Runnable mSelectModeBackHandler = () -> exitSelectMode();

    private void enterSelectMode() {
        if (mSelecting) {
            return;
        }
        mSelecting = true;
        mSelectTabsButton.setVisibility(View.GONE);
        mDoneButton.setVisibility(View.VISIBLE);
        mAdapter.notifyDataSetChanged();
        updateSelectionMode();
        mWidgetManager.pushBackHandler(mSelectModeBackHandler);
    }

    private void exitSelectMode() {
        if (!mSelecting) {
            return;
        }
        mSelecting = false;
        mSelectTabsButton.setVisibility(View.VISIBLE);
        mDoneButton.setVisibility(View.GONE);
        mSelectedTabs.clear();
        mAdapter.notifyDataSetChanged();
        updateSelectionMode();
        mWidgetManager.popBackHandler(mSelectModeBackHandler);
    }

    private void updateSelectionMode() {
        mTabsSelectModeView.setVisibility(mSelecting ? View.VISIBLE : View.GONE);
        mTabSelectModeSeparator.setVisibility(mSelecting ? View.VISIBLE : View.GONE);
        if (mSelectedTabs.size() > 0) {
            mCloseTabsButton.setVisibility(View.VISIBLE);
            mUnselectTabs.setVisibility(View.VISIBLE);
            mCloseTabsAllButton.setVisibility(View.GONE);
            mSelectAllButton.setVisibility(View.GONE);
        } else {
            mCloseTabsButton.setVisibility(View.GONE);
            mUnselectTabs.setVisibility(View.GONE);
            mCloseTabsAllButton.setVisibility(View.VISIBLE);
            mSelectAllButton.setVisibility(View.VISIBLE);
        }

        if (mSelecting) {
            if (mSelectedTabs.size() == 0) {
                mSelectedTabsCounter.setText(R.string.tabs_selected_counter_none);
            } else if (mSelectedTabs.size() > 1) {
                mSelectedTabsCounter.setText(getContext().getString(R.string.tabs_selected_counter_plural, String.valueOf(mSelectedTabs.size())));
            } else {
                mSelectedTabsCounter.setText(R.string.tabs_selected_counter_singular);
            }
        }
    }

    @Override
    protected void onDismiss() {
        exitSelectMode();
        super.onDismiss();
    }

    public class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
        private int mColumns;
        private int mSpacing;
        private int mSpacingFirst;

        public GridSpacingItemDecoration(Context aContext, int aColumns) {
            mColumns = aColumns;
            mSpacing = WidgetPlacement.pixelDimension(aContext, R.dimen.tabs_spacing);
            mSpacingFirst = WidgetPlacement.pixelDimension(aContext, R.dimen.tabs_spacing_first_column);
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view); // item position
            int column = position % mColumns; // item column
            int row = position / mColumns;

            outRect.left = column > 0 ? mSpacing / 2 : mSpacingFirst;
            outRect.right = column == mColumns - 1 ? mSpacingFirst:  mSpacing / 2;
            outRect.top = row > 0 ? mSpacing : 0;
        }
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (ViewUtils.isChildrenOf(this, oldFocus) && this.isVisible() &&
                !ViewUtils.isChildrenOf(this, newFocus)) {
            onDismiss();
        }
    }
}

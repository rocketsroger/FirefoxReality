/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.widgets.settings;

import android.app.Application;
import android.content.Context;
import android.graphics.Point;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;

import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.databinding.OptionsPrivacyPopupsBinding;
import org.mozilla.vrbrowser.db.PopUpSite;
import org.mozilla.vrbrowser.ui.adapters.PopUpAdapter;
import org.mozilla.vrbrowser.ui.callbacks.PopUpSiteItemCallback;
import org.mozilla.vrbrowser.ui.viewmodel.PopUpsViewModel;
import org.mozilla.vrbrowser.ui.widgets.WidgetManagerDelegate;
import org.mozilla.vrbrowser.ui.widgets.WidgetPlacement;

import java.util.List;
import java.util.stream.Collectors;

class AllowedPopUpsOptionsView extends SettingsView {

    private OptionsPrivacyPopupsBinding mBinding;
    private PopUpAdapter mAdapter;
    private PopUpsViewModel mViewModel;

    public AllowedPopUpsOptionsView(Context aContext, WidgetManagerDelegate aWidgetManager) {
        super(aContext, aWidgetManager);
        initialize(aContext);
    }

    private void initialize(Context aContext) {
        LayoutInflater inflater = LayoutInflater.from(aContext);

        // Preferred languages adapter
        mAdapter = new PopUpAdapter(getContext(), mCallback);

        // View Model
        mViewModel = new PopUpsViewModel(((Application)getContext().getApplicationContext()));

        // Inflate this data binding layout
        mBinding = DataBindingUtil.inflate(inflater, R.layout.options_privacy_popups, this, true);

        // Header
        mBinding.headerLayout.setBackClickListener(view -> {
            mDelegate.showView(new PrivacyOptionsView(getContext(), mWidgetManager));
        });

        // Adapters
        mBinding.siteList.setAdapter(mAdapter);

        // Footer
        mBinding.footerLayout.setResetClickListener(mClearAllListener);

        mBinding.executePendingBindings();
    }

    private OnClickListener mClearAllListener = (view) -> {
        reset();
    };

    @Override
    public Point getDimensions() {
        return new Point( WidgetPlacement.dpDimension(getContext(), R.dimen.language_options_width),
                WidgetPlacement.dpDimension(getContext(), R.dimen.language_options_height));
    }

    @Override
    protected boolean reset() {
        mViewModel.deleteAll();
        return true;
    }

    @Override
    public void onShown() {
        super.onShown();

        mViewModel.getAll().observeForever(mObserver);
    }

    @Override
    public void onHidden() {
        super.onHidden();

        mViewModel.getAll().removeObserver(mObserver);
    }

    private Observer<List<PopUpSite>> mObserver = new Observer<List<PopUpSite>>() {
        @Override
        public void onChanged(List<PopUpSite> popUpSites) {
            if (popUpSites != null) {
                mAdapter.setSites(popUpSites.stream().filter((site) -> site.allowed).collect(Collectors.toList()));
            }
        }
    };

    private PopUpSiteItemCallback mCallback = new PopUpSiteItemCallback() {
        @Override
        public void onDelete(@NonNull PopUpSite item) {
            mViewModel.deleteSite(item);
        }
    };
}

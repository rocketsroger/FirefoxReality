package org.mozilla.vrbrowser.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.vrbrowser.geolocation.GeolocationData;

import java.util.Locale;

import kotlin.coroutines.Continuation;
import mozilla.components.browser.search.provider.localization.SearchLocalization;
import mozilla.components.browser.search.provider.localization.SearchLocalizationProvider;

public class GeolocationLocalizationProvider implements SearchLocalizationProvider {

    private String mCountry;
    private String mLanguage;
    private String mRegion;

    GeolocationLocalizationProvider(GeolocationData data) {
        mCountry = data.getCountryCode();
        mLanguage = Locale.getDefault().getLanguage();
        mRegion = data.getCountryCode();
    }

    public SearchLocalization determineRegion(Continuation<? super SearchLocalization> continuation) {
        return new SearchLocalization(mLanguage, mCountry, mRegion);
    }

    @NotNull
    public String getCountry() {
        return mCountry;
    }

    @NotNull
    public String getLanguage() {
        return mLanguage;
    }

    @Nullable
    public String getRegion() {
        return mRegion;
    }

}

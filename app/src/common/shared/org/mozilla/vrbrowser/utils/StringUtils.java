package org.mozilla.vrbrowser.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.NonNull;

import org.mozilla.vrbrowser.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

public class StringUtils {
    @NonNull
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static String getStringByLocale(Context context, int id, Locale locale) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        return context.createConfigurationContext(configuration).getResources().getString(id);
    }

    public static String removeSpaces(@NonNull String aText) {
        return aText.replaceAll("\\s", "");
    }

    public static boolean isEmpty(String aString) {
        return aString == null || aString.length() == 0;
    }

    public static boolean isEmpty(CharSequence aSequence) {
        return aSequence == null || aSequence.length() == 0;
    }


    public static String getLastCharacter(String aText) {
        if (!isEmpty(aText)) {
            return aText.substring(aText.length() - 1);
        }

        return "";
    }

    public static String removeLastCharacter(String aText) {
        if (!isEmpty(aText)) {
            return aText.substring(0, aText.length() - 1);
        }
        return "";
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
    public static String versionCodeToDate(final @NonNull Context context, final int aVersionCode) {
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
            formatted = context.getString(R.string.settings_version_developer);
        }

        return formatted;
    }

    @NonNull
    public static String capitalize(@NonNull String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }
}

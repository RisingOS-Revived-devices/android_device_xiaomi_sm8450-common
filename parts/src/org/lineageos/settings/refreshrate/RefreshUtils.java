/*
 * Copyright (C) 2020 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.refreshrate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

/**
 * Per-app refresh rates. While a listed app is in front its rate is forced through
 * min/peak_refresh_rate; the user's own values (including "never set") are saved first and put
 * back exactly when an unlisted app comes up, also after a reboot.
 */
public final class RefreshUtils {

    private static final String REFRESH_CONTROL = "refresh_control";

    private static final String KEY_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String KEY_MIN_REFRESH_RATE = "min_refresh_rate";

    // The user's values while an app overrides them; a missing saved key means the setting was unset.
    private static final String PREF_OVERRIDING = "refresh_overriding";
    private static final String PREF_SAVED_PEAK = "refresh_saved_peak";
    private static final String PREF_SAVED_MIN = "refresh_saved_min";

    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_STANDARD = 1;
    protected static final int STATE_EXTREME = 2;
    protected static final int STATE_LAND = 3;

    private static final float REFRESH_STATE_STANDARD = 60f;
    private static final float REFRESH_STATE_EXTREME = 120f;
    private static final float REFRESH_STATE_LAND = 60f;

    private static final String REFRESH_STANDARD = "refresh.standard=";
    private static final String REFRESH_EXTREME = "refresh.extreme=";
    private static final String REFRESH_LAND = "refresh.land=";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final SharedPreferences mSharedPrefs;

    // A listed landscape app is in front, so the rate follows the orientation.
    private volatile boolean mLandActive;

    public RefreshUtils(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static void startService(Context context) {
        context.startServiceAsUser(new Intent(context, RefreshService.class),
                UserHandle.CURRENT);
    }

    private void writeValue(String profiles) {
        mSharedPrefs.edit().putString(REFRESH_CONTROL, profiles).apply();
    }

    private String getValue() {
        String value = mSharedPrefs.getString(REFRESH_CONTROL, null);

        if (value == null || value.isEmpty()) {
            value = REFRESH_STANDARD + ":" + REFRESH_EXTREME + ":" + REFRESH_LAND;
            writeValue(value);
        }

        String[] modes = value.split(":");
        if (modes.length < 3) {
            modes = new String[] {
                modes.length > 0 ? modes[0] : REFRESH_STANDARD,
                modes.length > 1 ? modes[1] : REFRESH_EXTREME,
                modes.length > 2 ? modes[2] : REFRESH_LAND
            };
            value = String.join(":", modes);
            writeValue(value);
        }
        return value;
    }

    // Entries look like "refresh.x=a,b,": anchored so "foo.app" does not match org.foo.app.
    private static boolean hasPackage(String mode, String packageName) {
        return mode.contains("=" + packageName + ",") || mode.contains("," + packageName + ",");
    }

    public void writePackage(String packageName, int mode) {
        String value = getValue();
        value = value.replace("=" + packageName + ",", "=")
                .replace("," + packageName + ",", ",");
        String[] modes = value.split(":");

        switch (mode) {
            case STATE_STANDARD:
                modes[0] = modes[0] + packageName + ",";
                break;
            case STATE_EXTREME:
                modes[1] = modes[1] + packageName + ",";
                break;
            case STATE_LAND:
                modes[2] = modes[2] + packageName + ",";
                break;
        }

        writeValue(modes[0] + ":" + modes[1] + ":" + modes[2]);
    }

    public int getStateForPackage(String packageName) {
        String[] modes = getValue().split(":");
        if (hasPackage(modes[0], packageName)) {
            return STATE_STANDARD;
        } else if (hasPackage(modes[1], packageName)) {
            return STATE_EXTREME;
        } else if (hasPackage(modes[2], packageName)) {
            return STATE_LAND;
        }
        return STATE_DEFAULT;
    }

    /** Applies the rate of the app that came to the front. */
    protected synchronized void setRefreshRate(String packageName) {
        int state = getStateForPackage(packageName);
        mLandActive = state == STATE_LAND;

        switch (state) {
            case STATE_STANDARD:
                forceRate(REFRESH_STATE_STANDARD);
                break;
            case STATE_EXTREME:
                forceRate(REFRESH_STATE_EXTREME);
                break;
            case STATE_LAND:
                saveUserRates();
                applyOrientation();
                break;
            default:
                restoreUserRates();
                break;
        }
    }

    private void forceRate(float rate) {
        saveUserRates();
        Settings.System.putFloat(mResolver, KEY_MIN_REFRESH_RATE, rate);
        Settings.System.putFloat(mResolver, KEY_PEAK_REFRESH_RATE, rate);
    }

    /** Runs on the main thread, while setRefreshRate runs on a binder thread and saves the rates first. */
    protected synchronized void onConfigurationChanged() {
        if (mLandActive) applyOrientation();
    }

    /** Landscape apps: 60 Hz in landscape, the user's own rates in portrait. */
    private void applyOrientation() {
        if (mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            Settings.System.putFloat(mResolver, KEY_MIN_REFRESH_RATE, REFRESH_STATE_LAND);
            Settings.System.putFloat(mResolver, KEY_PEAK_REFRESH_RATE, REFRESH_STATE_LAND);
        } else {
            writeUserRates();
        }
    }

    private void saveUserRates() {
        if (mSharedPrefs.getBoolean(PREF_OVERRIDING, false)) return;
        SharedPreferences.Editor editor = mSharedPrefs.edit().putBoolean(PREF_OVERRIDING, true);
        save(editor, PREF_SAVED_PEAK, Settings.System.getString(mResolver, KEY_PEAK_REFRESH_RATE));
        save(editor, PREF_SAVED_MIN, Settings.System.getString(mResolver, KEY_MIN_REFRESH_RATE));
        editor.commit();
    }

    private static void save(SharedPreferences.Editor editor, String key, String value) {
        if (value != null) {
            editor.putString(key, value);
        } else {
            editor.remove(key);
        }
    }

    private void restoreUserRates() {
        if (!mSharedPrefs.getBoolean(PREF_OVERRIDING, false)) return;
        writeUserRates();
        mSharedPrefs.edit().remove(PREF_OVERRIDING).remove(PREF_SAVED_PEAK)
                .remove(PREF_SAVED_MIN).commit();
    }

    private void writeUserRates() {
        restore(KEY_PEAK_REFRESH_RATE, mSharedPrefs.getString(PREF_SAVED_PEAK, null));
        restore(KEY_MIN_REFRESH_RATE, mSharedPrefs.getString(PREF_SAVED_MIN, null));
    }

    private void restore(String key, String value) {
        if (value != null) {
            Settings.System.putString(mResolver, key, value);
        } else {
            mResolver.delete(Settings.System.getUriFor(key), null, null);
        }
    }
}

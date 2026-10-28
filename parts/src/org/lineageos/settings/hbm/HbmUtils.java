/*
 * Copyright (C) 2016 The OmniROM Project
 *               2018-2021 crDroid Android Project
 *               2019-2022 Evolution X
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.utils.FileUtils;

/** HBM and Auto HBM state shared by the dashboard, the QS tile and AutoHBMService. */
public final class HbmUtils {

    public static final String KEY_HBM_SWITCH = "hbm";
    public static final String KEY_AUTO_HBM_SWITCH = "auto_hbm";
    // Stored as strings: AutoHBMService parses them with getString().
    public static final String KEY_AUTO_HBM_THRESHOLD = "auto_hbm_threshold";
    public static final String KEY_HBM_DISABLE_TIME = "hbm_disable_time";
    private static final String KEY_LAST_BRIGHTNESS = "last_brightness";

    public static final int THRESHOLD_MIN = 0;
    public static final int THRESHOLD_MAX = 60000;
    public static final int THRESHOLD_STEP = 1000;
    public static final int THRESHOLD_DEFAULT = 20000;
    public static final int DISABLE_TIME_MIN = 2;
    public static final int DISABLE_TIME_MAX = 15;
    public static final int DISABLE_TIME_DEFAULT = 5;

    // mi_disp feature interface: "<feature id> <value>", DISP_FEATURE_HBM is 1.
    private static final String DISP_PARAM = "/sys/class/mi_display/disp-DSI-0/disp_param";
    private static final String DISP_FEATURE_HBM = "1";
    public static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String MAX_BACKLIGHT = "/sys/class/backlight/panel0-backlight/max_brightness";
    private static final int MAX_BACKLIGHT_FALLBACK = 8191;

    // disp_param cannot be read back per feature, so remember what was last written.
    private static volatile boolean sHbmActive = false;

    private HbmUtils() {}

    /** Switches the panel HBM state through mi_disp. */
    public static void writeHbm(boolean enabled) {
        if (FileUtils.writeLine(DISP_PARAM, DISP_FEATURE_HBM + " " + (enabled ? "1" : "0"))) {
            sHbmActive = enabled;
        }
    }

    public static boolean isHbmActive() {
        return sHbmActive;
    }

    /** Highest value accepted by the backlight node (8191 on the zeus panel). */
    public static int getMaxBacklight() {
        String raw = FileUtils.readOneLine(MAX_BACKLIGHT);
        try {
            return raw != null ? Integer.parseInt(raw.trim()) : MAX_BACKLIGHT_FALLBACK;
        } catch (NumberFormatException e) {
            return MAX_BACKLIGHT_FALLBACK;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isSupported() {
        return FileUtils.isFileWritable(DISP_PARAM);
    }

    public static boolean isHbmEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HBM_SWITCH, false);
    }

    public static boolean setHbmEnabled(Context context, boolean enabled) {
        SharedPreferences sharedPrefs = prefs(context);
        if (enabled) {
            int currentBrightness = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            sharedPrefs.edit().putInt(KEY_LAST_BRIGHTNESS, currentBrightness).apply();
            writeHbm(true);
            FileUtils.writeLine(BACKLIGHT, String.valueOf(getMaxBacklight()));
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
        } else {
            writeHbm(false);
            int lastBrightness = sharedPrefs.getInt(KEY_LAST_BRIGHTNESS, 128);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, lastBrightness);
        }
        sharedPrefs.edit().putBoolean(KEY_HBM_SWITCH, enabled).commit();
        return true;
    }

    public static boolean isAutoHbmEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HBM_SWITCH, false);
    }

    public static void setAutoHbmEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_HBM_SWITCH, enabled).commit();
        FileUtils.enableService(context);
    }

    public static int getThreshold(Context context) {
        return readInt(context, KEY_AUTO_HBM_THRESHOLD, THRESHOLD_DEFAULT);
    }

    public static void setThreshold(Context context, int lux) {
        prefs(context).edit().putString(KEY_AUTO_HBM_THRESHOLD, String.valueOf(lux)).commit();
    }

    public static int getDisableTime(Context context) {
        return readInt(context, KEY_HBM_DISABLE_TIME, DISABLE_TIME_DEFAULT);
    }

    public static void setDisableTime(Context context, int seconds) {
        prefs(context).edit().putString(KEY_HBM_DISABLE_TIME, String.valueOf(seconds)).commit();
    }

    private static int readInt(Context context, String key, int defaultValue) {
        try {
            return Integer.parseInt(prefs(context).getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException | ClassCastException e) {
            return defaultValue;
        }
    }
}

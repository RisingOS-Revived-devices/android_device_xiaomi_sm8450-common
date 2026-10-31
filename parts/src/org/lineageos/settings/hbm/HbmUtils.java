/*
 * Copyright (C) 2016 The OmniROM Project
 *               2018-2021 crDroid Android Project
 *               2019-2022 Evolution X
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hbm;

import android.content.ContentResolver;
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

    private static final String KEY_LAST_BRIGHTNESS_MODE = "last_brightness_mode";
    // What Auto HBM has to put back; set only while it holds the brightness.
    public static final String KEY_AUTO_SAVED_BRIGHTNESS = "auto_hbm_saved_brightness";
    public static final String KEY_AUTO_SAVED_MODE = "auto_hbm_saved_mode";
    // Auto HBM turns off below 65 % of the threshold, so 0 lux would never turn it off
    public static final int THRESHOLD_MIN = 1000;
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
    /** The brightness and mode manual HBM goes back to when it turns off. */
    public static void setLastBrightness(Context context, int brightness, int mode) {
        prefs(context).edit()
                .putInt(KEY_LAST_BRIGHTNESS, brightness)
                .putInt(KEY_LAST_BRIGHTNESS_MODE, mode)
                .commit();
    }

            int currentBrightness = Settings.System.getInt(context.getContentResolver(),
        if (enabled == isHbmEnabled(context)) return true;
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            sharedPrefs.edit()
                    .putInt(KEY_LAST_BRIGHTNESS, currentBrightness)
                    .putInt(KEY_LAST_BRIGHTNESS_MODE, holdManualBrightness(context))
                    .commit();
            writeHbm(true);
            FileUtils.writeLine(BACKLIGHT, String.valueOf(getMaxBacklight()));
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
        } else {
            writeHbm(false);
            releaseManualBrightness(context,
                    sharedPrefs.getInt(KEY_LAST_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL),
                    sharedPrefs.getInt(KEY_LAST_BRIGHTNESS, 128));
        }
        sharedPrefs.edit().putBoolean(KEY_HBM_SWITCH, enabled).commit();
        return true;
    }

    public static boolean isAutoHbmEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HBM_SWITCH, false);
    }
    /**
     * Switches to manual brightness and returns the mode in use: in auto mode the framework
     * takes a SCREEN_BRIGHTNESS write as a user choice and teaches it to the brightness curve.
     */
    public static int holdManualBrightness(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        return mode;
    }

    /** Puts the manual brightness back, or hands the brightness back to auto brightness. */
    public static void releaseManualBrightness(Context context, int mode, int brightness) {
        ContentResolver resolver = context.getContentResolver();
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
        } else {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
        }
    }

    /**
     * Manual HBM ends with the screen: the panel leaves HBM when it powers off, so put the
     * switch and the brightness back (boot and screen off).
     */
    public static void resetHbm(Context context) {
        if (isHbmEnabled(context)) setHbmEnabled(context, false);
    }


    public static void setAutoHbmEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_HBM_SWITCH, enabled).commit();
        FileUtils.enableService(context);
    }

    public static int getThreshold(Context context) {
        return Math.max(THRESHOLD_MIN, readInt(context, KEY_AUTO_HBM_THRESHOLD, THRESHOLD_DEFAULT));
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

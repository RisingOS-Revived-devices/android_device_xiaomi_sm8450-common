/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.utils.FileUtils;

/**
 * Wired fast charge boost and reverse wireless charging, written to the same nodes with the
 * same values as HyperOS (SecurityCenter through the MiCharge HAL).
 */
public final class ChargeUtils {

    public static final String KEY_BOOST = "charge_boost";
    public static final String KEY_REVERSE_LIMIT = "reverse_charge_limit";

    static final String NODE_SPORT_MODE = "/sys/class/qcom-battery/sport_mode";
    static final String NODE_CHARGER_TEMP = "/sys/class/thermal/thermal_message/charger_temp";
    static final String NODE_REVERSE_CHG_MODE = "/sys/class/qcom-battery/reverse_chg_mode";

    // HyperOS tells the thermal driver about boost with charger_temp 8, and 0 without it.
    private static final String CHARGER_TEMP_BOOST = "8";

    public static final int[] REVERSE_LIMITS = {20, 30, 40};
    private static final int REVERSE_LIMIT_DEFAULT = 30;

    /** Why reverse charging can't run right now. */
    public static final int REVERSE_OK = 0;
    public static final int REVERSE_BLOCKED_WIRELESS = 1;
    public static final int REVERSE_BLOCKED_LOW_BATTERY = 2;

    private ChargeUtils() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isBoostSupported() {
        return FileUtils.fileExists(NODE_SPORT_MODE) && FileUtils.fileExists(NODE_CHARGER_TEMP);
    }

    public static boolean isBoostEnabled(Context context) {
        // Off until the user turns it on: the values are zeus' (HyperOS) charger and thermal tuning
        return prefs(context).getBoolean(KEY_BOOST, false);
    }

    public static void setBoostEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BOOST, enabled).apply();
        applyBoost(context);
    }

    static void applyBoost(Context context) {
        if (!isBoostSupported()) return;
        boolean on = isBoostEnabled(context);
        FileUtils.writeLine(NODE_SPORT_MODE, on ? "1" : "0");
        FileUtils.writeLine(NODE_CHARGER_TEMP, on ? CHARGER_TEMP_BOOST : "0");
    }

    public static boolean isReverseSupported() {
        return FileUtils.fileExists(NODE_REVERSE_CHG_MODE);
    }

    public static boolean isReverseOn() {
        return "1".equals(FileUtils.readOneLine(NODE_REVERSE_CHG_MODE));
    }

    public static int getReverseLimit(Context context) {
        int limit = prefs(context).getInt(KEY_REVERSE_LIMIT, REVERSE_LIMIT_DEFAULT);
        for (int l : REVERSE_LIMITS) {
            if (l == limit) return limit;
        }
        return REVERSE_LIMIT_DEFAULT;
    }

    public static void setReverseLimit(Context context, int limit) {
        prefs(context).edit().putInt(KEY_REVERSE_LIMIT, limit).apply();
        checkReverseLimit(context, batteryStatus(context));
    }

    /** Turns reverse charging on or off; returns REVERSE_OK or why it was refused. */
    public static int setReverseOn(Context context, boolean on) {
        if (on) {
            int blocked = reverseBlockedReason(context, batteryStatus(context));
            if (blocked != REVERSE_OK) return blocked;
        }
        FileUtils.writeLine(NODE_REVERSE_CHG_MODE, on ? "1" : "0");
        return REVERSE_OK;
    }

    /**
     * Same rule as HyperOS: no reverse charging while the phone itself charges wirelessly,
     * or on battery below the limit. A wired charger keeps it running at any level.
     */
    static int reverseBlockedReason(Context context, Intent status) {
        if (status == null) return REVERSE_OK;
        int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) return REVERSE_BLOCKED_WIRELESS;
        if (plugged > 0) return REVERSE_OK;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0 && level * 100 / scale < getReverseLimit(context)) {
            return REVERSE_BLOCKED_LOW_BATTERY;
        }
        return REVERSE_OK;
    }

    /** Stops reverse charging once it is no longer allowed; returns the reason it stopped. */
    static int checkReverseLimit(Context context, Intent status) {
        if (!isReverseOn()) return REVERSE_OK;
        int blocked = reverseBlockedReason(context, status);
        if (blocked != REVERSE_OK) {
            FileUtils.writeLine(NODE_REVERSE_CHG_MODE, "0");
        }
        return blocked;
    }

    static Intent batteryStatus(Context context) {
        return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** Boot: restore boost and start watching the battery for the reverse charging limit. */
    public static void startService(Context context) {
        if (isBoostSupported() || isReverseSupported()) {
            context.startService(new Intent(context, ChargeService.class));
        }
    }
}

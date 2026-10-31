/*
 * Copyright (C) 2025 TheMysticle
 *               2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hypercharge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.utils.FileUtils;

/**
 * HyperCharge settings. The chosen limit is the ceiling the kernel puts on the charge current
 * mi_thermald asks for, so the thermal limits keep working under it.
 */
public final class HyperChargeUtils {

    private static final String TAG = "HyperChargeUtils";

    public static final String KEY_STATUS = "hypercharge_status";
    public static final String KEY_LIMIT = "hypercharge_limit";

    // Ceiling on constant_charge_current in uA, 0 for none
    private static final String NODE_CONSTANT_CHARGE_CURRENT_MAX =
            "/sys/class/power_supply/battery/constant_charge_current_max";
    private static final String NO_CEILING = "0";

    // Charge current in uA; 22 A is the 120 W HyperCharge current.
    public static final String LIMIT_120W = "22000000";
    public static final String LIMIT_33W = "6000000";
    private static final String LIMIT_90W = "16500000";
    private static final String LIMIT_67W = "12300000";

    // The device tree sets this; limits above it can't apply and its top limit means no ceiling.
    private static final int MAX_WATTS =
            SystemProperties.getInt("ro.xiaomiparts.hypercharge.max_watts", 67);

    private HyperChargeUtils() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isSupported() {
        return FileUtils.fileExists(NODE_CONSTANT_CHARGE_CURRENT_MAX);
    }

    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_STATUS, true);
        } catch (ClassCastException e) {
            Log.w(TAG, "Corrupt " + KEY_STATUS + " pref, resetting");
            prefs(context).edit().remove(KEY_STATUS).apply();
            return true;
        }
    }

    /** Index of the first entry of the limit arrays this device can use. */
    public static int firstLimit() {
        return MAX_WATTS >= 120 ? 0 : MAX_WATTS >= 90 ? 1 : 2;
    }

    private static String topLimit() {
        return MAX_WATTS >= 120 ? LIMIT_120W : MAX_WATTS >= 90 ? LIMIT_90W : LIMIT_67W;
    }

    public static String getLimit(Context context) {
        try {
            return prefs(context).getString(KEY_LIMIT, topLimit());
        } catch (ClassCastException e) {
            Log.w(TAG, "Corrupt " + KEY_LIMIT + " pref, resetting");
            prefs(context).edit().remove(KEY_LIMIT).apply();
            return topLimit();
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_STATUS, enabled).apply();
        update(context);
    }

    public static void setLimit(Context context, String limit) {
        prefs(context).edit().putString(KEY_LIMIT, limit).apply();
        update(context);
    }

    /** 33 W with HyperCharge off, no ceiling at the top limit, else the chosen limit. */
    static String getCeiling(Context context) {
        if (!isEnabled(context)) return LIMIT_33W;
        String limit = getLimit(context);
        return LIMIT_120W.equals(limit) || topLimit().equals(limit) ? NO_CEILING : limit;
    }

    /** Writes the ceiling; the kernel keeps it until reboot, so boot and every change call this. */
    public static void update(Context context) {
        if (!isSupported()) return;
        if (!FileUtils.writeLine(NODE_CONSTANT_CHARGE_CURRENT_MAX, getCeiling(context))) {
            Log.e(TAG, "Failed to write the charge current ceiling");
        }
    }
}

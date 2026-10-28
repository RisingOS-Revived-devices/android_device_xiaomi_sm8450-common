/*
 * Copyright (C) 2025 TheMysticle
 *               2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hypercharge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;

/** HyperCharge settings and the rule that decides whether HyperChargeService has to run. */
public final class HyperChargeUtils {

    private static final String TAG = "HyperChargeUtils";

    public static final String KEY_STATUS = "hypercharge_status";
    public static final String KEY_LIMIT = "hypercharge_limit";

    public static final String NODE_CONSTANT_CHARGE_CURRENT =
            "/sys/class/power_supply/battery/constant_charge_current";
    public static final String NODE_FASTCHG_MODE = "/sys/class/qcom-battery/fastchg_mode";

    // Charge current in uA; 22 A is the unrestricted 120 W HyperCharge current.
    public static final String LIMIT_120W = "22000000";
    public static final String LIMIT_33W = "6000000";

    private HyperChargeUtils() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isSupported() {
        return new File(NODE_CONSTANT_CHARGE_CURRENT).exists() && new File(NODE_FASTCHG_MODE).exists();
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

    public static String getLimit(Context context) {
        try {
            return prefs(context).getString(KEY_LIMIT, LIMIT_120W);
        } catch (ClassCastException e) {
            Log.w(TAG, "Corrupt " + KEY_LIMIT + " pref, resetting");
            prefs(context).edit().remove(KEY_LIMIT).apply();
            return LIMIT_120W;
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

    /** The current the service enforces: 33 W with HyperCharge off, else the chosen limit. */
    static String getTargetLimit(Context context) {
        return isEnabled(context) ? getLimit(context) : LIMIT_33W;
    }

    /**
     * Unrestricted 120 W is the kernel default, so the service only has to run when
     * HyperCharge is off or a lower limit is selected.
     */
    public static void update(Context context) {
        Intent intent = new Intent(context, HyperChargeService.class);
        if (isEnabled(context) && LIMIT_120W.equals(getLimit(context))) {
            context.stopService(intent);
        } else {
            context.startService(intent);
        }
    }
}

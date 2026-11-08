/*
 * Copyright (C) 2025 Joshaby
 *               2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.powerprofile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.R;

/**
 * System wide thermal profile (Balanced, Gaming, Performance, Battery saver). ThermalService
 * follows it and is the only writer of thermal_message/sconfig: apps with their own per-app
 * thermal mode override it while they are in the foreground, see ThermalUtils.
 */
public class PowerProfileUtil {

    private static final String TAG = "PowerProfileUtil";
    public static final String PREF_MODE = "power_profile_mode";
    // The profile battery saver replaced, put back when battery saver turns off
    private static final String PREF_MODE_BEFORE_SAVER = "power_profile_mode_before_saver";
    // init applies the CPU side once post_boot is done, so it survives reboots
    private static final String PERF_MODE_PROP = "persist.sys.parts.perf_mode";
    private static final String CHANNEL_ID = TAG;
    private static final int NOTIFICATION_ID_PERFORMANCE = 1001;
    private static final int NOTIFICATION_ID_GAMING = 1002;

    public static final int MODE_BALANCE = 0;
    public static final int MODE_GAMING = 1;
    public static final int MODE_PERFORMANCE = 2;
    public static final int MODE_BATTERY_SAVER = 3;

    // sconfig values of the mi_thermald profiles, indexed by mode: normal, tgame, mgame and
    // normal again, as zeus' thermal-map.conf has no battery saver profile (3 is huanji).
    private static final String[] SCONFIG = {"0", "13", "20", "0"};
    // persist.sys.parts.perf_mode values, indexed by mode.
    private static final int[] PERF_MODE = {1, 3, 2, 0};

    private final Context mContext;
    private final SharedPreferences mSharedPrefs;
    private final NotificationManager mNotificationManager;
    private ContentObserver mBatterySaverObserver;

    public PowerProfileUtil(Context context) {
        this(context, false);
    }

    /** watchBatterySaver: follow the system battery saver, for the always running ThermalService. */
    public PowerProfileUtil(Context context, boolean watchBatterySaver) {
        mContext = context;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        setupNotificationChannel();
        if (watchBatterySaver) registerBatterySaverObserver();
    }

    /** The selected profile; Balanced until the user picks another one. */
    public static int getMode(Context context) {
        int mode = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_MODE, MODE_BALANCE);
        return mode >= MODE_BALANCE && mode <= MODE_BATTERY_SAVER ? mode : MODE_BALANCE;
    }

    /** sconfig value of the selected profile, used for apps without a per-app mode. */
    public static String getSconfig(Context context) {
        return SCONFIG[getMode(context)];
    }

    public int getMode() {
        return getMode(mContext);
    }

    public String getModeLabel(int mode) {
        return mContext.getResources().getStringArray(R.array.power_profile_entries)[mode];
    }

    public void setMode(int mode) {
        mSharedPrefs.edit().remove(PREF_MODE_BEFORE_SAVER).apply();
        applyMode(mode);
    }

    private void applyMode(int mode) {
        if (mode < MODE_BALANCE || mode > MODE_BATTERY_SAVER) mode = MODE_BALANCE;
        // Android refuses battery saver on a charger, and nothing would end the profile then.
        boolean saver = mode == MODE_BATTERY_SAVER;
        if (!enableBatterySaver(saver) && saver) mode = MODE_BALANCE;
        mSharedPrefs.edit().putInt(PREF_MODE, mode).apply();
        SystemProperties.set(PERF_MODE_PROP, String.valueOf(PERF_MODE[mode]));
        Log.d(TAG, "Power profile set to " + mode);

        updateNotifications(mode);
    }

    /** Boot: catch up with battery saver, which turns off before this service starts, and hand the profile to init. */
    public void restore() {
        syncBatterySaver();
        int mode = getMode();
        SystemProperties.set(PERF_MODE_PROP, String.valueOf(PERF_MODE[mode]));
        updateNotifications(mode);
    }

    private void updateNotifications(int mode) {
        if (mode == MODE_PERFORMANCE) {
            showNotification(NOTIFICATION_ID_PERFORMANCE, R.string.perf_mode_title,
                    R.string.perf_mode_notification, R.drawable.ic_thermal_performance);
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID_PERFORMANCE);
        }
        if (mode == MODE_GAMING) {
            showNotification(NOTIFICATION_ID_GAMING, R.string.gaming_mode_title,
                    R.string.gaming_mode_notification, R.drawable.ic_thermal_gaming);
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID_GAMING);
        }
    }

    /** Balanced -> Gaming -> Performance -> Battery saver -> Balanced. */
    public void toggleMode() {
        setMode((getMode() + 1) % SCONFIG.length);
    }

    private boolean enableBatterySaver(boolean enable) {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        return pm == null || pm.isPowerSaveMode() == enable || pm.setPowerSaveModeEnabled(enable);
    }

    private void setupNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                mContext.getString(R.string.power_profile_title), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showNotification(int id, int title, int text, int icon) {
        Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setContentTitle(mContext.getString(title))
                .setContentText(mContext.getString(text))
                .setSmallIcon(icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        mNotificationManager.notify(id, notification);
    }

    private void syncBatterySaver() {
        boolean on = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LOW_POWER_MODE, 0) == 1;
        int mode = getMode();
        if (on && mode != MODE_BATTERY_SAVER) {
            Log.d(TAG, "Battery saver enabled, switching to the battery saver profile.");
            mSharedPrefs.edit().putInt(PREF_MODE_BEFORE_SAVER, mode).apply();
            applyMode(MODE_BATTERY_SAVER);
        } else if (!on && mode == MODE_BATTERY_SAVER) {
            // Nothing is saved when the user picked the profile by hand, so that ends on Balanced.
            int before = mSharedPrefs.getInt(PREF_MODE_BEFORE_SAVER, MODE_BALANCE);
            Log.d(TAG, "Battery saver disabled, going back to profile " + before);
            mSharedPrefs.edit().remove(PREF_MODE_BEFORE_SAVER).apply();
            applyMode(before);
        }
    }

    private void registerBatterySaverObserver() {
        mBatterySaverObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                syncBatterySaver();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE), false, mBatterySaverObserver);
    }

    public void cleanup() {
        if (mBatterySaverObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mBatterySaverObserver);
            mBatterySaverObserver = null;
        }
    }
}

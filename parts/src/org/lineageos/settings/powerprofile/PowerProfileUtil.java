/*
 * Copyright (C) 2025 Joshaby
 *               2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.powerprofile;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.R;
import org.lineageos.settings.utils.FileUtils;

import java.util.Arrays;
import java.util.List;

/**
 * System wide thermal profile (Balanced, Gaming, Performance, Battery saver) written to
 * thermal_message/sconfig. Apps with their own per-app thermal mode override it while
 * they are in the foreground, see ThermalUtils.
 */
public class PowerProfileUtil {

    private static final String TAG = "PowerProfileUtil";
    private static final String THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig";
    private static final String PREF_MODE = "power_profile_mode";
    private static final String SYS_PROP = "sys.perf_mode_active";
    private static final String CHANNEL_ID = TAG;
    private static final int NOTIFICATION_ID_PERFORMANCE = 1001;
    private static final int NOTIFICATION_ID_GAMING = 1002;

    public static final int MODE_BALANCE = 0;
    public static final int MODE_GAMING = 1;
    public static final int MODE_PERFORMANCE = 2;
    public static final int MODE_BATTERY_SAVER = 3;

    // sconfig values of the mi_thermald profiles, indexed by mode.
    private static final String[] SCONFIG = {"0", "13", "20", "3"};
    // sys.perf_mode_active values, indexed by mode.
    private static final int[] PERF_MODE = {1, 3, 2, 0};

    private final Context mContext;
    private final SharedPreferences mSharedPrefs;
    private final NotificationManager mNotificationManager;
    private final List<String> mGamePackages;
    private ContentObserver mBatterySaverObserver;

    public PowerProfileUtil(Context context) {
        mContext = context;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mGamePackages = Arrays.asList(context.getResources().getStringArray(R.array.game_packages));
        setupNotificationChannel();
        registerBatterySaverObserver();
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
        if (mode < MODE_BALANCE || mode > MODE_BATTERY_SAVER) mode = MODE_BALANCE;
        mSharedPrefs.edit().putInt(PREF_MODE, mode).apply();
        SystemProperties.set(SYS_PROP, String.valueOf(PERF_MODE[mode]));
        boolean success = FileUtils.writeLine(THERMAL_SCONFIG, SCONFIG[mode]);
        Log.d(TAG, "Power profile set to " + mode + ": " + success);

        enableBatterySaver(mode == MODE_BATTERY_SAVER);
        if (mode == MODE_PERFORMANCE) {
            showNotification(NOTIFICATION_ID_PERFORMANCE, R.string.perf_mode_title,
                    R.string.perf_mode_notification, R.drawable.ic_thermal_performance);
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID_PERFORMANCE);
        }
        if (mode == MODE_GAMING) {
            showNotification(NOTIFICATION_ID_GAMING, R.string.gaming_mode_title,
                    R.string.gaming_mode_notification, R.drawable.ic_thermal_gaming);
            optimizeGameLaunch();
        } else {
            mNotificationManager.cancel(NOTIFICATION_ID_GAMING);
        }
    }

    /** Balanced -> Gaming -> Performance -> Battery saver -> Balanced. */
    public void toggleMode() {
        setMode((getMode() + 1) % SCONFIG.length);
    }

    private void optimizeGameLaunch() {
        ActivityManager am = mContext.getSystemService(ActivityManager.class);
        if (am == null) return;
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running == null) return;

        for (ActivityManager.RunningAppProcessInfo info : running) {
            if (mGamePackages.contains(info.processName)) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
                trimMemory(am, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
                Log.d(TAG, "Optimizations applied to game package: " + info.processName);
            } else if (info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                am.killBackgroundProcesses(info.processName);
            }
        }
    }

    private void trimMemory(ActivityManager am, int level) {
        try {
            am.getClass().getMethod("trimMemory", int.class).invoke(am, level);
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim memory", e);
        }
    }

    private void enableBatterySaver(boolean enable) {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (pm != null && pm.isPowerSaveMode() != enable) {
            pm.setPowerSaveModeEnabled(enable);
        }
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

    private void registerBatterySaverObserver() {
        mBatterySaverObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                boolean on = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.LOW_POWER_MODE, 0) == 1;
                if (on && getMode() != MODE_BATTERY_SAVER) {
                    Log.d(TAG, "Battery saver enabled, switching to the battery saver profile.");
                    setMode(MODE_BATTERY_SAVER);
                }
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

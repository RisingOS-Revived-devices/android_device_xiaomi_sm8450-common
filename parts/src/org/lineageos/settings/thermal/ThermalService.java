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

package org.lineageos.settings.thermal;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.hbm.HbmUtils;
import org.lineageos.settings.powerprofile.PowerProfileUtil;
import org.lineageos.settings.saturation.SaturationUtils;

public class ThermalService extends Service {

    private static final String TAG = "ThermalService";
    private static final boolean DEBUG = false;
    private static final String KEY_DISPLAY_COLOR_MODE = "display_color_mode";
    // Long enough to land after the framework's own saturation transaction.
    private static final long COLOR_MODE_SETTLE_MS = 500;

    private boolean mScreenOn = true;
    private String mCurrentApp = "";
    private ThermalUtils mThermalUtils;
    private PowerProfileUtil mPowerProfile;
    private SharedPreferences mPrefs;

    private IActivityTaskManager mActivityTaskManager;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRestoreSaturation = () -> SaturationUtils.restore(this);

    // Every colour mode resets saturation in SurfaceFlinger, while Parts keeps showing the stored value.
    private final ContentObserver mColorModeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mHandler.removeCallbacks(mRestoreSaturation);
            mHandler.postDelayed(mRestoreSaturation, COLOR_MODE_SETTLE_MS);
        }
    };

    // A field, as SharedPreferences only keeps a weak reference to its listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> {
                if (PowerProfileUtil.PREF_MODE.equals(key)) setThermalProfile();
            };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mScreenOn = false;
                    setThermalProfile();
                    // The always running Parts service, so manual HBM resets without the tile
                    HbmUtils.resetHbm(context);
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mScreenOn = true;
                    setThermalProfile();
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service");
        try {
            mActivityTaskManager = ActivityTaskManager.getService();
            mActivityTaskManager.registerTaskStackListener(mTaskListener);
        } catch (RemoteException e) {
            // Do nothing
        }
        mThermalUtils = new ThermalUtils(this);
        mPowerProfile = new PowerProfileUtil(this, true);
        mPowerProfile.restore();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_DISPLAY_COLOR_MODE), false, mColorModeObserver);
        // Both reapply the colour mode in ColorDisplayService without changing the setting above.
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED), false, mColorModeObserver);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED), false, mColorModeObserver);
        registerReceiver();
        super.onCreate();
        // A restarted service keeps the foreground app's mode instead of falling back to the profile.
        mScreenOn = getSystemService(PowerManager.class).isInteractive();
        try {
            mTaskListener.onTaskStackChanged();
        } catch (RemoteException e) {
            // Do nothing
        }
        setThermalProfile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        try {
            if (mActivityTaskManager != null) {
                mActivityTaskManager.unregisterTaskStackListener(mTaskListener);
            }
        } catch (RemoteException e) {
            // Do nothing
        }
        unregisterReceiver(mIntentReceiver);
        mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        getContentResolver().unregisterContentObserver(mColorModeObserver);
        mHandler.removeCallbacks(mRestoreSaturation);
        mPowerProfile.cleanup();
        super.onDestroy();
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        this.registerReceiver(mIntentReceiver, filter);
    }

    private void setThermalProfile() {
        if (mScreenOn) {
            mThermalUtils.setThermalProfile(mCurrentApp);
        } else {
            mThermalUtils.setDefaultThermalProfile();
        }
    }

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            try {
                final ActivityTaskManager.RootTaskInfo focusedTask =
                        ActivityTaskManager.getService().getFocusedRootTaskInfo();
                if (focusedTask != null && focusedTask.topActivity != null) {
                    ComponentName taskComponentName = focusedTask.topActivity;
                    String foregroundApp = taskComponentName.getPackageName();
                    if (!foregroundApp.equals(mCurrentApp)) {
                        mCurrentApp = foregroundApp;
                        setThermalProfile();
                    }
                }
            } catch (Exception e) {}
        }
    };
}

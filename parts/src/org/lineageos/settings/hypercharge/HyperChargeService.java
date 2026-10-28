/*
 * Copyright (C) 2025 TheMysticle
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.hypercharge;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.lineageos.settings.utils.FileUtils;

/** Keeps constant_charge_current at the chosen limit while a HyperCharge charger is attached. */
public class HyperChargeService extends Service {
    private static final String TAG = "HyperChargeService";

    private static final int POLLING_INTERVAL_MS = 5000;
    private static final int HANDSHAKE_DELAY_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mMonitoringRunnable;
    private Runnable mPendingStartRunnable;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                Log.i(TAG, "Charger connected, waiting " + HANDSHAKE_DELAY_MS + "ms for the handshake.");
                stopMonitoring();
                mPendingStartRunnable = HyperChargeService.this::startMonitoring;
                mHandler.postDelayed(mPendingStartRunnable, HANDSHAKE_DELAY_MS);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                Log.i(TAG, "Charger disconnected, stopping monitoring.");
                stopMonitoring();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerReceiver, filter);

        if (isDevicePluggedIn()) {
            startMonitoring();
        }
    }

    private void startMonitoring() {
        if (mMonitoringRunnable != null) return;

        if (!"1".equals(FileUtils.readOneLine(HyperChargeUtils.NODE_FASTCHG_MODE))) {
            Log.i(TAG, "Standard charger detected, nothing to limit.");
            return;
        }

        Log.i(TAG, "HyperCharge charger detected, monitoring the charge current.");
        mMonitoringRunnable = new Runnable() {
            @Override
            public void run() {
                if ("1".equals(FileUtils.readOneLine(HyperChargeUtils.NODE_FASTCHG_MODE))) {
                    String target = HyperChargeUtils.getTargetLimit(HyperChargeService.this);
                    String current = FileUtils.readOneLine(HyperChargeUtils.NODE_CONSTANT_CHARGE_CURRENT);
                    if (!target.equals(current)) {
                        Log.i(TAG, "Applying charge limit: " + current + " -> " + target);
                        FileUtils.writeLine(HyperChargeUtils.NODE_CONSTANT_CHARGE_CURRENT, target);
                    }
                    mHandler.postDelayed(this, POLLING_INTERVAL_MS);
                } else {
                    Log.i(TAG, "HyperCharge mode lost, stopping monitoring.");
                    mMonitoringRunnable = null;
                }
            }
        };
        mHandler.post(mMonitoringRunnable);
    }

    private void stopMonitoring() {
        if (mPendingStartRunnable != null) {
            mHandler.removeCallbacks(mPendingStartRunnable);
            mPendingStartRunnable = null;
        }
        if (mMonitoringRunnable != null) {
            mHandler.removeCallbacks(mMonitoringRunnable);
            mMonitoringRunnable = null;
        }
    }

    private boolean isDevicePluggedIn() {
        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) return false;
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(powerReceiver);
        stopMonitoring();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

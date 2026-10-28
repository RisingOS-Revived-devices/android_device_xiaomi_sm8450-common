/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.charge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.util.Log;

import org.lineageos.settings.R;
import org.lineageos.settings.ui.PartsActivity;

/** Restores the boost setting on each plug-in and stops reverse charging at the chosen limit. */
public class ChargeService extends Service {
    private static final String TAG = "ChargeService";

    private static final String CHANNEL_ID = "reverse_charge";
    private static final int NOTIFICATION_ID = 0x5245; // "RE"

    private int mLastLevel = -1;
    private int mLastPlugged = -1;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                ChargeUtils.applyBoost(context);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                onBatteryChanged(intent);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ChargeUtils.applyBoost(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mReceiver, filter);
    }

    private void onBatteryChanged(Intent status) {
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        // BATTERY_CHANGED also fires for voltage and temperature; only level and plug matter here.
        if (level == mLastLevel && plugged == mLastPlugged) return;
        mLastLevel = level;
        mLastPlugged = plugged;
        if (!ChargeUtils.isReverseSupported()) return;

        int stopped = ChargeUtils.checkReverseLimit(this, status);
        if (stopped != ChargeUtils.REVERSE_OK) {
            Log.i(TAG, "Reverse charging stopped, reason " + stopped + ", level " + level);
            notifyStopped(stopped);
        }
    }

    private void notifyStopped(int reason) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.reverse_charge_title), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setBlockable(true);
        nm.createNotificationChannel(channel);

        String text = reason == ChargeUtils.REVERSE_BLOCKED_WIRELESS
                ? getString(R.string.reverse_charge_stopped_wireless)
                : getString(R.string.reverse_charge_stopped_limit, ChargeUtils.getReverseLimit(this));
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                PartsActivity.intentFor(this, PartsActivity.ROUTE_REVERSE_CHARGE),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.reverse_charge_stopped_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_reverse_charge)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

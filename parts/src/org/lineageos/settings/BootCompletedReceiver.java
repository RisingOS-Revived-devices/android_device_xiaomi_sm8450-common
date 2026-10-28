/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
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

package org.lineageos.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Display.HdrCapabilities;

import org.lineageos.settings.auxcamera.AuxCameraUtils;
import org.lineageos.settings.charge.ChargeUtils;
import org.lineageos.settings.hypercharge.HyperChargeUtils;
import org.lineageos.settings.misound.MiSoundUtils;
import org.lineageos.settings.refreshrate.RefreshUtils;
import org.lineageos.settings.resolution.ResolutionUtils;
import org.lineageos.settings.thermal.ThermalUtils;
import org.lineageos.settings.utils.FileUtils;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final boolean DEBUG = false;
    private static final String TAG = "XiaomiParts";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (DEBUG)
            Log.d(TAG, "Received boot completed intent");

        // Each step on its own, so a feature this device lacks can't skip the rest.
        restore("thermal profiles", () -> ThermalUtils.startService(context));
        restore("refresh rate", () -> RefreshUtils.startService(context));
        restore("HDR types", () -> overrideHdrTypes(context));
        restore("per-app resolution", () -> ResolutionUtils.startService(context));
        restore("auto HBM", () -> FileUtils.enableService(context));
        restore("aux camera restrictions", () -> AuxCameraUtils.INSTANCE.restore(context));
        restore("HyperCharge", () -> HyperChargeUtils.update(context));
        // Wired boost and the reverse charging limit
        restore("charge service", () -> ChargeUtils.startService(context));
        // MiSound: the persistent process owns the effect and restores its settings
        restore("MiSound", () -> {
            if (MiSoundUtils.isSupported()) {
                MiSoundUtils.getInstance(context);
            }
        });
    }

    private static void restore(String what, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore " + what + " on boot", e);
        }
    }

    private static void overrideHdrTypes(Context context) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        dm.overrideHdrTypes(Display.DEFAULT_DISPLAY, new int[]{
                HdrCapabilities.HDR_TYPE_DOLBY_VISION, HdrCapabilities.HDR_TYPE_HDR10,
                HdrCapabilities.HDR_TYPE_HLG, HdrCapabilities.HDR_TYPE_HDR10_PLUS});
    }
}

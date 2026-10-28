/*
 * Copyright (C) 2025 KamiKaonashi
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

package org.lineageos.settings.resolution;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.UserHandle;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import androidx.preference.PreferenceManager;

public final class ResolutionUtils {

    private static final String TAG = "ResolutionUtils";

    // Per-app list buckets
    private static final String RESOLUTION_CONTROL = "resolutioncontrol";
    private static final String RESOLUTION_720P = "resolution.720p";
    private static final String RESOLUTION_900P = "resolution.900p";
    private static final String RESOLUTION_1080P = "resolution.1080p";

    // System-wide baseline state
    private static final String RESOLUTION_GLOBAL_STATE = "resolution.global_state";

    // States
    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_720P = 1;
    protected static final int STATE_900P = 2;
    protected static final int STATE_1080P = 3;

    protected static boolean isAppInList = false;

    // State tracking to prevent aggressive resets
    private int mCurrentState = STATE_DEFAULT;
    private int mSavedUserDensity = -1;

    private static class ResolutionConfig {
        int width;
        int height;
        int density; // integer DPI

        ResolutionConfig(int w, int h, int d) {
            width = w;
            height = h;
            density = d;
        }
    }

    private static ResolutionConfig[] RESOLUTION_CONFIGS = new ResolutionConfig[4];

    private final SharedPreferences mSharedPrefs;
    private final Context mContext;

    // Native baselines from physical mode and initial density
    private int mStockWidth;
    private int mStockHeight;
    private int mInitialDensity;

    public ResolutionUtils(Context context) {
        mContext = context.getApplicationContext();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        initializeStockBaselines();
        calculateResolutionConfigs();
    }

    public static void startService(Context context) {
        // Apply the baseline early at boot so home/lock honor it before the first app focus event
        new ResolutionUtils(context).applyBaselineFromGlobal();
        context.startServiceAsUser(new Intent(context, ResolutionService.class), UserHandle.CURRENT);
    }

    private void initializeStockBaselines() {
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Display.Mode mode = d.getMode(); // physical/native mode
        mStockWidth = mode.getPhysicalWidth();
        mStockHeight = mode.getPhysicalHeight();

        try {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            mInitialDensity = wm.getInitialDisplayDensity(Display.DEFAULT_DISPLAY);
        } catch (RemoteException e) {
            mInitialDensity = 560; // zeus ro.sf.lcd_density; overwritten on success path
        }
    }

    private void calculateResolutionConfigs() {
        // Scale by target WIDTH; keep native aspect ratio and scale DPI proportionally
        // scale = targetW / stockW; targetH = round(stockH * scale); dpi = round(initialDPI * scale)

        RESOLUTION_CONFIGS[STATE_DEFAULT] =
                new ResolutionConfig(mStockWidth, mStockHeight, mInitialDensity);

        int w720 = 720;
        float s720 = (float) w720 / (float) mStockWidth;
        int h720 = Math.max(1, Math.round(mStockHeight * s720));
        int d720 = Math.max(120, Math.round(mInitialDensity * s720));
        RESOLUTION_CONFIGS[STATE_720P] = new ResolutionConfig(w720, h720, d720);

        int w900 = 900;
        float s900 = (float) w900 / (float) mStockWidth;
        int h900 = Math.max(1, Math.round(mStockHeight * s900));
        int d900 = Math.max(120, Math.round(mInitialDensity * s900));
        RESOLUTION_CONFIGS[STATE_900P] = new ResolutionConfig(w900, h900, d900);

        int w1080 = 1080;
        float s1080 = (float) w1080 / (float) mStockWidth;
        int h1080 = Math.max(1, Math.round(mStockHeight * s1080));
        int d1080 = Math.max(120, Math.round(mInitialDensity * s1080));
        RESOLUTION_CONFIGS[STATE_1080P] = new ResolutionConfig(w1080, h1080, d1080);
    }

    // ----- System-wide baseline -----

    public int getGlobalState() {
        return mSharedPrefs.getInt(RESOLUTION_GLOBAL_STATE, STATE_DEFAULT);
    }

    public void setGlobalState(int state) {
        if (state < STATE_DEFAULT || state > STATE_1080P) state = STATE_DEFAULT;
        mSharedPrefs.edit().putInt(RESOLUTION_GLOBAL_STATE, state).apply();
        applyBaselineFromGlobal();
    }

    public void applyBaselineFromGlobal() {
        int s = getGlobalState();
        ResolutionConfig cfg = RESOLUTION_CONFIGS[s];
        applyResolution(cfg, s);
    }

    // service compatibility
    public void restoreDefaultResolution() {
        applyBaselineFromGlobal();
    }

    // ----- Per-app list management -----

    private void writeValue(String profiles) {
        mSharedPrefs.edit().putString(RESOLUTION_CONTROL, profiles).apply();
    }

    private String getValue() {
        String value = mSharedPrefs.getString(RESOLUTION_CONTROL, null);
        if (value == null || value.isEmpty()) {
            value = RESOLUTION_720P + ";" + RESOLUTION_900P + ";" + RESOLUTION_1080P;
            writeValue(value);
        }
        String[] modes = value.split(";");
        if (modes.length < 3) {
            String[] fixed = new String[]{
                    modes.length > 0 ? modes[0] : RESOLUTION_720P,
                    modes.length > 1 ? modes[1] : RESOLUTION_900P,
                    modes.length > 2 ? modes[2] : RESOLUTION_1080P
            };
            value = String.join(";", fixed);
            writeValue(value);
        }
        return value;
    }

    public void writePackage(String packageName, int mode) {
        String value = getValue();
        value = value.replace(packageName + ",", "");
        String[] modes = value.split(";");

        switch (mode) {
            case STATE_720P:
                modes[0] = modes[0] + packageName + ",";
                break;
            case STATE_900P:
                modes[1] = modes[1] + packageName + ",";
                break;
            case STATE_1080P:
                modes[2] = modes[2] + packageName + ",";
                break;
            default:
                break;
        }
        writeValue(modes[0] + ";" + modes[1] + ";" + modes[2]);
    }

    public int getStateForPackage(String packageName) {
        String[] modes = getValue().split(";");
        if (modes[0].contains(packageName + ",")) return STATE_720P;
        if (modes[1].contains(packageName + ",")) return STATE_900P;
        if (modes[2].contains(packageName + ",")) return STATE_1080P;
        return STATE_DEFAULT;
    }

    protected void setResolution(String packageName) {
        String[] modes = getValue().split(";");
        int globalState = getGlobalState();
        int newState = globalState;

        // Start from the system baseline; override if app is listed
        isAppInList = false;
        if (modes[0].contains(packageName + ",")) {
            newState = STATE_720P;
            isAppInList = true;
        } else if (modes[1].contains(packageName + ",")) {
            newState = STATE_900P;
            isAppInList = true;
        } else if (modes[2].contains(packageName + ",")) {
            newState = STATE_1080P;
            isAppInList = true;
        }

        ResolutionConfig cfg = RESOLUTION_CONFIGS[newState];
        applyResolution(cfg, newState);
    }

    // ----- Low-level application -----

    private void applyResolution(ResolutionConfig cfg, int newState) {
        if (newState == mCurrentState) {
            return; // No change, do nothing
        }

        try {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            if (newState != STATE_DEFAULT) {
                // Entering a custom resolution state
                if (mCurrentState == STATE_DEFAULT) {
                    // Save the user's current density before changing it
                    // NOTE: getBaseDisplayDensity returns the density stored in persistent settings
                    mSavedUserDensity = wm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
                }
                wm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, cfg.width, cfg.height);
                wm.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, cfg.density, UserHandle.USER_CURRENT);
            } else {
                // Returning to the default state
                wm.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
                if (mSavedUserDensity != -1) {
                    // Restore the saved user density
                    wm.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, mSavedUserDensity, UserHandle.USER_CURRENT);
                    mSavedUserDensity = -1; // Clear the saved value
                } else {
                    // Fallback if no density was saved
                    wm.clearForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, UserHandle.USER_CURRENT);
                }
            }
            mCurrentState = newState;
        } catch (RemoteException e) {
            // Swallow to avoid crashes
        }
    }

    public String getResolutionString(int state) {
        ResolutionConfig c = (state >= 0 && state < RESOLUTION_CONFIGS.length) ? RESOLUTION_CONFIGS[state] : null;
        return c != null ? (c.width + "x" + c.height) : (mStockWidth + "x" + mStockHeight);
    }
}

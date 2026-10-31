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
import android.graphics.Point;
import android.os.RemoteException;
import android.os.UserHandle;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
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

    private static final String[] BUCKETS = {RESOLUTION_720P, RESOLUTION_900P, RESOLUTION_1080P};
    // System-wide baseline state
    private static final String RESOLUTION_GLOBAL_STATE = "resolution.global_state";

    // States
    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_720P = 1;
    protected static final int STATE_900P = 2;
    protected static final int STATE_1080P = 3;

    // Set while a size forced by Parts is active, with the user's own forced density from before
    // it (key missing: the user had none). Kept in prefs: every instance and reboots see them.
    private static final String PREF_CUSTOM_ACTIVE = "resolution.custom_active";
    private static final String PREF_SAVED_DENSITY = "resolution.saved_density";
    private static final String DISPLAY_DENSITY_FORCED = "display_density_forced";

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
        String[] modes = getValue().split(";");
        for (int i = 0; i < BUCKETS.length; i++) {
            modes[i] = modes[i].replace(BUCKETS[i] + packageName + ",", BUCKETS[i])
                    .replace("," + packageName + ",", ",");
        }

        switch (mode) {
    // Entries look like "resolution.720pa,b,": the first one follows the bucket name directly.
    // Anchored so "foo.app" does not match org.foo.app.
    private static boolean hasPackage(String mode, String bucket, String packageName) {
        return mode.startsWith(bucket + packageName + ",")
                || mode.contains("," + packageName + ",");
    }

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
        if (hasPackage(modes[0], RESOLUTION_720P, packageName)) return STATE_720P;
        if (hasPackage(modes[1], RESOLUTION_900P, packageName)) return STATE_900P;
        if (hasPackage(modes[2], RESOLUTION_1080P, packageName)) return STATE_1080P;
        return STATE_DEFAULT;
    }

    protected void setResolution(String packageName) {
        // Start from the system baseline; override if app is listed
        int appState = getStateForPackage(packageName);
        int newState = appState != STATE_DEFAULT ? appState : getGlobalState();

        ResolutionConfig cfg = RESOLUTION_CONFIGS[newState];
        applyResolution(cfg, newState);
    }

    // ----- Low-level application -----

    private void applyResolution(ResolutionConfig cfg, int newState) {
        try {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            if (newState != STATE_DEFAULT) {
                if (!customActive) {
                    String userDensity = Settings.Secure.getString(
                            mContext.getContentResolver(), DISPLAY_DENSITY_FORCED);
                    SharedPreferences.Editor editor =
                            mSharedPrefs.edit().putBoolean(PREF_CUSTOM_ACTIVE, true);
                    if (userDensity != null && !userDensity.isEmpty()) {
                        editor.putString(PREF_SAVED_DENSITY, userDensity);
                    } else {
                        editor.remove(PREF_SAVED_DENSITY);
                    }
                    editor.commit();
                }
                // Each change restarts the visible activities, so skip what is already in place
                if (size.x != cfg.width || size.y != cfg.height) {
                    wm.setForcedDisplaySize(Display.DEFAULT_DISPLAY, cfg.width, cfg.height);
            Point size = new Point();
            wm.getBaseDisplaySize(Display.DEFAULT_DISPLAY, size);
            int density = wm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);
            boolean customActive = mSharedPrefs.getBoolean(PREF_CUSTOM_ACTIVE, false)
                    || isLeftoverSize(size);

                }
                if (density != cfg.density) {
                    wm.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, cfg.density,
                            UserHandle.USER_CURRENT);
                }
            } else if (customActive) {
                wm.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
                String saved = mSharedPrefs.getString(PREF_SAVED_DENSITY, null);
                if (saved != null) {
                    wm.setForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY,
                            Integer.parseInt(saved), UserHandle.USER_CURRENT);
                } else {
                    wm.clearForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY,
                            UserHandle.USER_CURRENT);
                }
            }
        } catch (RemoteException | NumberFormatException e) {
            Log.e(TAG, "Failed to apply resolution state " + newState, e);
        }
    }

    /** A size older Parts builds forced and lost track of: exactly one of the scaled modes. */
    private boolean isLeftoverSize(Point size) {
        for (int s = STATE_720P; s <= STATE_1080P; s++) {
            ResolutionConfig c = RESOLUTION_CONFIGS[s];
            if (c.width != mStockWidth && size.x == c.width && size.y == c.height) return true;
        }
    }
                mSharedPrefs.edit().remove(PREF_CUSTOM_ACTIVE).remove(PREF_SAVED_DENSITY).commit();

    public String getResolutionString(int state) {
        ResolutionConfig c = (state >= 0 && state < RESOLUTION_CONFIGS.length) ? RESOLUTION_CONFIGS[state] : null;
        return c != null ? (c.width + "x" + c.height) : (mStockWidth + "x" + mStockHeight);
    }
        return false;
}

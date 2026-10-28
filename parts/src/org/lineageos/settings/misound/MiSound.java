/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.misound;

import android.media.audiofx.AudioEffect;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Client for the vendor libmisoundfx. Parameter ids and values are the ones the HyperOS
 * framework (android.media.audiofx.MiSound) sends, checked against MiSound_command.
 */
public class MiSound extends AudioEffect {

    private static final int PARAM_HEADSET_TYPE = 1;
    private static final int PARAM_EQ_LEVEL = 2;
    private static final int PARAM_SCENARIO = 15;
    private static final int PARAM_3D_SURROUND = 20;
    // Native effect enable: issues EFFECT_CMD_ENABLE/DISABLE inside the library.
    private static final int PARAM_ENABLE = 25;

    public static final int SCENARIO_MUSIC = 1;
    public static final int SCENARIO_VIDEO = 2;
    public static final int SCENARIO_VOCAL = 3;
    public static final int SCENARIO_SMART = 4;

    public static final int HEADSET_GENERAL = 5;
    public static final int HEADSET_GENERAL_INEAR = 6;

    public static final int EQ_BANDS = 10;
    // The HyperOS equalizer maps its -100..100 % sliders to this range in dB.
    public static final float EQ_MAX_DB = 6f;

    static final UUID EFFECT_TYPE_MISOUND = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b");

    public MiSound(int priority, int audioSession) {
        super(EFFECT_TYPE_NULL, EFFECT_TYPE_MISOUND, priority, audioSession);
    }

    @Override
    public boolean hasControl() {
        try {
            return super.hasControl();
        } catch (IllegalStateException e) {
            // Released or dead (audioserver restart) handle.
            return false;
        }
    }

    /**
     * Keeps the framework and native enable states together, as the HyperOS client does;
     * a failed enable is rolled back so the effect never stays half-enabled.
     */
    @Override
    public int setEnabled(boolean enabled) throws IllegalStateException {
        if (!enabled) {
            RuntimeException failure = null;
            try {
                checkStatus(setParameter(PARAM_ENABLE, 0));
            } catch (RuntimeException e) {
                failure = e;
            }
            try {
                checkStatus(super.setEnabled(false));
            } catch (RuntimeException e) {
                failure = merge(failure, e);
            }
            if (failure != null) throw failure;
            return SUCCESS;
        }

        checkStatus(setParameter(PARAM_ENABLE, 1));
        int status;
        try {
            status = super.setEnabled(true);
        } catch (RuntimeException e) {
            try {
                checkStatus(setParameter(PARAM_ENABLE, 0));
            } catch (RuntimeException rollback) {
                merge(e, rollback);
            }
            throw e;
        }
        if (status != SUCCESS) {
            IllegalStateException failure =
                    new IllegalStateException("Framework enable failed: " + status);
            try {
                checkStatus(setParameter(PARAM_ENABLE, 0));
            } catch (RuntimeException rollback) {
                merge(failure, rollback);
            }
            throw failure;
        }
        return SUCCESS;
    }

    private static RuntimeException merge(RuntimeException failure, RuntimeException other) {
        if (failure == null) return other;
        if (failure != other) failure.addSuppressed(other);
        return failure;
    }

    /** The library reports enabled from persist.vendor.audio.misound.disable. */
    public boolean isNativeEnabled() {
        int[] value = new int[1];
        int count = getParameter(PARAM_ENABLE, value);
        checkStatus(count);
        if (count != 1) throw new IllegalStateException("Incomplete enable state: " + count);
        return value[0] == 1;
    }

    public void setHeadsetType(int type) {
        checkStatus(setParameter(PARAM_HEADSET_TYPE, type));
    }

    /** Gain of one of the 10 bands in dB; the library only applies it with headphones. */
    public void setLevel(int band, float db) {
        if (band < 0 || band >= EQ_BANDS || Float.isNaN(db) || Math.abs(db) > EQ_MAX_DB) {
            throw new IllegalArgumentException("Invalid level " + db + " for band " + band);
        }
        // The library runs stof on the first 8 bytes, so keep it short and NUL-terminated.
        checkStatus(setParameter(new int[]{PARAM_EQ_LEVEL, band},
                (String.format(Locale.ROOT, "%.2f", db) + "\0").getBytes(StandardCharsets.US_ASCII)));
    }

    public void setScenario(int scenario) {
        checkStatus(setParameter(PARAM_SCENARIO, scenario));
    }

    public void set3dSurround(boolean enabled) {
        checkStatus(setParameter(PARAM_3D_SURROUND, enabled ? 1 : 0));
    }
}

/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.misound;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Owns the vendor MiSound effect. Saved preferences are the source of truth and are reapplied
 * whenever the effect is (re)created: at boot, after an audioserver restart and when control
 * returns from another client. All DSP calls run on one worker thread.
 * Recovery follows veux DiracUtils (ported from PocoF3Releases sm8250-common MiSoundUtils).
 */
public final class MiSoundUtils {

    private static final String TAG = "MiSoundUtils";

    private static final String PREF_ENABLE = "misound_enable";
    private static final String PREF_SCENARIO = "misound_scenario";
    private static final String PREF_HEADSET = "misound_headset";
    private static final String PREF_3D_SURROUND = "misound_3d_surround";
    private static final String PREF_EQ_PRESET = "misound_eq_preset";
    private static final String PREF_EQ_CUSTOM = "misound_eq_custom";

    /**
     * HyperOS presets in dB, in its order: custom, rock, jazz, pop, classical, hip hop, blues,
     * electronic, metal (MiSound app, dax_geq_preset_values). Index 0 uses the saved custom bands.
     */
    private static final String[] EQ_PRESETS = {
            "0,0,0,0,0,0,0,0,0,0",
            "4,1,-2,-0.25,0,-2,0,-2,0.5,4",
            "0,0,0,-1,-1,-3,-0.5,0,0,0",
            "-2,-0.5,-5,-1,0,0,-0.5,-3,-0.5,0",
            "0,0,0,0,0.5,3,1,6,2,6",
            "3,0,-3,-0.5,-0.5,-3,-0.5,0,0,2",
            "2,2,-6,-2,3,1,0,1,0,2",
            "3,1,-1,0,-0.5,-3,-0.5,0,0,0",
            "2,0,0,-1.25,-1,-4,0,0,0,0",
    };
    public static final int EQ_PRESET_CUSTOM = 0;
    public static final int EQ_PRESET_COUNT = EQ_PRESETS.length;

    public static final int[] SCENARIOS = {
            MiSound.SCENARIO_SMART, MiSound.SCENARIO_MUSIC, MiSound.SCENARIO_VIDEO,
            MiSound.SCENARIO_VOCAL};
    public static final int[] HEADSETS = {MiSound.HEADSET_GENERAL, MiSound.HEADSET_GENERAL_INEAR};

    // LunarisDolby (hardware/dolby) owns Dolby; only its on/off state is read here.
    private static final UUID EFFECT_TYPE_DOLBY = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    private static final int DOLBY_PARAM_CPDP_VALUES = 5;
    private static final int DOLBY_PARAM_ENABLE = 0;
    // Below LunarisDolby's priority (100) so the probe never takes control from it.
    private static final int DOLBY_PROBE_PRIORITY = -1;
    private static final ComponentName DOLBY_ACTIVITY =
            new ComponentName("org.lunaris.dolby", "org.lunaris.dolby.ui.DolbyActivity");

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 1000;

    private static MiSoundUtils sInstance;

    private final SharedPreferences mPrefs;
    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final Executor mExecutor;

    // Worker-thread state.
    private MiSound mMiSound;
    private boolean mServerDown;
    private boolean mCallbackRegistered;
    private int mCallbackAttempts;
    private int mAttempts;

    private final Runnable mCallbackTask = this::registerCallback;
    private final Runnable mRestoreTask = this::restore;

    private final AudioManager.AudioServerStateCallback mServerCallback =
            new AudioManager.AudioServerStateCallback() {
                @Override
                public void onAudioServerDown() {
                    mServerDown = true;
                    mHandler.removeCallbacks(mRestoreTask);
                    releaseEffect();
                }

                @Override
                public void onAudioServerUp() {
                    mServerDown = false;
                    mAttempts = 0;
                    restore();
                }
            };

    private MiSoundUtils(Context context) {
        Context app = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(app);
        mAudioManager = app.getSystemService(AudioManager.class);
        HandlerThread worker = new HandlerThread("MiSound");
        worker.start();
        mHandler = new Handler(worker.getLooper());
        mExecutor = mHandler::post;
        mHandler.post(mCallbackTask);
    }

    public static synchronized MiSoundUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new MiSoundUtils(context);
        }
        return sInstance;
    }

    // DSP tuning the effect loads; only some devices' vendor trees ship it
    private static final String SPEAKER_RES = "/vendor/lib/rfsa/adsp/misound_res_spk.bin";

    /** Whether this vendor ships libmisoundfx (registered in audio_effects.xml) and its tuning. */
    public static boolean isSupported() {
        if (!new File(SPEAKER_RES).exists()) return false;
        try {
            for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
                if (MiSound.EFFECT_TYPE_MISOUND.equals(d.uuid)) return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot query effects", e);
        }
        return false;
    }

    /** Reads Dolby's on state without taking control of it; false if Dolby is absent. */
    public static boolean isDolbyEnabled() {
        AudioEffect probe = null;
        try {
            probe = new AudioEffect(AudioEffect.EFFECT_TYPE_NULL, EFFECT_TYPE_DOLBY,
                    DOLBY_PROBE_PRIORITY, 0);
            byte[] value = new byte[12];
            ByteBuffer.wrap(value).order(ByteOrder.nativeOrder()).putInt(DOLBY_PARAM_ENABLE);
            int status = probe.getParameter(DOLBY_PARAM_CPDP_VALUES + DOLBY_PARAM_ENABLE, value);
            return status >= 0 && ByteBuffer.wrap(value).order(ByteOrder.nativeOrder()).getInt() == 1;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (probe != null) probe.release();
        }
    }

    public static Intent dolbySettingsIntent() {
        return new Intent().setComponent(DOLBY_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ----- Public API (any thread; DSP work is queued in call order) -----

    public boolean isEnabled() {
        return mPrefs.getBoolean(PREF_ENABLE, false);
    }

    public int getScenario() {
        return validOr(mPrefs.getInt(PREF_SCENARIO, MiSound.SCENARIO_SMART), SCENARIOS,
                MiSound.SCENARIO_SMART);
    }

    public int getHeadsetType() {
        return validOr(mPrefs.getInt(PREF_HEADSET, MiSound.HEADSET_GENERAL), HEADSETS,
                MiSound.HEADSET_GENERAL);
    }

    public boolean is3dSurroundEnabled() {
        return mPrefs.getBoolean(PREF_3D_SURROUND, false);
    }

    public void setEnabled(boolean enable) {
        mPrefs.edit().putBoolean(PREF_ENABLE, enable).apply();
        submit(() -> {
            MiSound effect = requireEffect();
            if (enable) applySettings(effect);
            applyEnabled(effect, enable);
        });
    }

    public void setScenario(int scenario) {
        mPrefs.edit().putInt(PREF_SCENARIO, validOr(scenario, SCENARIOS, MiSound.SCENARIO_SMART))
                .apply();
        submit(() -> requireEffect().setScenario(getScenario()));
    }

    public void setHeadsetType(int type) {
        mPrefs.edit().putInt(PREF_HEADSET, validOr(type, HEADSETS, MiSound.HEADSET_GENERAL)).apply();
        submit(() -> requireEffect().setHeadsetType(getHeadsetType()));
    }

    public void set3dSurroundEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(PREF_3D_SURROUND, enabled).apply();
        submit(() -> requireEffect().set3dSurround(enabled));
    }

    public int getEqPreset() {
        int preset = mPrefs.getInt(PREF_EQ_PRESET, EQ_PRESET_CUSTOM);
        return preset >= 0 && preset < EQ_PRESET_COUNT ? preset : EQ_PRESET_CUSTOM;
    }

    /** Band gains in dB of the selected preset. */
    public float[] getEqLevels() {
        int preset = getEqPreset();
        if (preset != EQ_PRESET_CUSTOM) return parseLevels(EQ_PRESETS[preset]);
        try {
            return parseLevels(mPrefs.getString(PREF_EQ_CUSTOM, EQ_PRESETS[EQ_PRESET_CUSTOM]));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Discarding invalid custom equalizer", e);
            mPrefs.edit().remove(PREF_EQ_CUSTOM).apply();
            return parseLevels(EQ_PRESETS[EQ_PRESET_CUSTOM]);
        }
    }

    public void setEqPreset(int preset) {
        if (preset < 0 || preset >= EQ_PRESET_COUNT) throw new IllegalArgumentException("Preset " + preset);
        mPrefs.edit().putInt(PREF_EQ_PRESET, preset).apply();
        float[] levels = getEqLevels();
        submit(() -> applyEq(requireEffect(), levels));
    }

    /** Saves the bands as the custom preset and selects it. */
    public void setEqCustom(float[] levels) {
        String saved = formatLevels(levels);
        mPrefs.edit().putString(PREF_EQ_CUSTOM, saved).putInt(PREF_EQ_PRESET, EQ_PRESET_CUSTOM).apply();
        float[] parsed = parseLevels(saved);
        submit(() -> applyEq(requireEffect(), parsed));
    }

    private static float[] parseLevels(String value) {
        String[] parts = value.split(",");
        if (parts.length != MiSound.EQ_BANDS) {
            throw new IllegalArgumentException("Expected " + MiSound.EQ_BANDS + " bands: " + value);
        }
        float[] levels = new float[MiSound.EQ_BANDS];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Float.parseFloat(parts[i].trim());
            if (Float.isNaN(levels[i]) || Math.abs(levels[i]) > MiSound.EQ_MAX_DB) {
                throw new IllegalArgumentException("Invalid level in " + value);
            }
        }
        return levels;
    }

    private static String formatLevels(float[] levels) {
        if (levels.length != MiSound.EQ_BANDS) throw new IllegalArgumentException("Band count");
        StringBuilder sb = new StringBuilder();
        for (float level : levels) {
            if (sb.length() > 0) sb.append(',');
            sb.append(Math.max(-MiSound.EQ_MAX_DB, Math.min(MiSound.EQ_MAX_DB, level)));
        }
        return sb.toString();
    }

    private static void applyEq(MiSound effect, float[] levels) {
        for (int band = 0; band < levels.length; band++) {
            effect.setLevel(band, levels[band]);
        }
    }

    private static int validOr(int value, int[] allowed, int fallback) {
        for (int v : allowed) {
            if (v == value) return value;
        }
        return fallback;
    }

    private void submit(Runnable action) {
        mHandler.post(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot apply sound setting", e);
                restoreAfterFailure();
            }
        });
    }

    // ----- Worker thread -----

    private void registerCallback() {
        try {
            mAudioManager.setAudioServerStateCallback(mExecutor, mServerCallback);
            mCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot register audio server callback", e);
            if (++mCallbackAttempts < MAX_ATTEMPTS) {
                mHandler.postDelayed(mCallbackTask, RETRY_DELAY_MS);
            }
            return;
        }
        restore();
    }

    private void restore() {
        try {
            MiSound effect = requireEffect();
            if (effect.isNativeEnabled() != isEnabled() || effect.getEnabled() != isEnabled()) {
                applyEnabled(effect, isEnabled());
            }
            mAttempts = 0;
            mHandler.removeCallbacks(mRestoreTask);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot restore sound effect", e);
            if (++mAttempts < MAX_ATTEMPTS && !mServerDown) {
                mHandler.removeCallbacks(mRestoreTask);
                mHandler.postDelayed(mRestoreTask, RETRY_DELAY_MS);
            }
        }
    }

    private void restoreAfterFailure() {
        // Keep a handle that lost control: its listener resumes recovery.
        if (mMiSound == null || mMiSound.hasControl()) releaseEffect();
        mAttempts = 0;
        restore();
    }

    private void releaseEffect() {
        MiSound previous = mMiSound;
        mMiSound = null;
        if (previous == null) return;
        try {
            previous.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot release effect", e);
        }
    }

    private MiSound requireEffect() {
        if (!mCallbackRegistered) throw new IllegalStateException("Audio callback unavailable");
        if (mServerDown) throw new IllegalStateException("Audio server is down");
        if (mMiSound != null) {
            if (!mMiSound.hasControl()) throw new IllegalStateException("Effect control unavailable");
            return mMiSound;
        }
        MiSound effect = new MiSound(0, 0);
        mMiSound = effect;
        try {
            effect.setControlStatusListener((e, granted) -> mHandler.post(() -> {
                if (mMiSound != effect || mServerDown || !granted) return;
                // The other owner may have changed the tuning too; start clean.
                mAttempts = 0;
                releaseEffect();
                restore();
            }));
            if (!effect.hasControl()) throw new IllegalStateException("Effect control unavailable");
            applySettings(effect);
            applyEnabled(effect, isEnabled());
            return effect;
        } catch (RuntimeException e) {
            if (effect.hasControl()) releaseEffect();
            throw e;
        }
    }

    private void applySettings(MiSound effect) {
        effect.setHeadsetType(getHeadsetType());
        effect.setScenario(getScenario());
        effect.set3dSurround(is3dSurroundEnabled());
        applyEq(effect, getEqLevels());
    }

    private static void applyEnabled(MiSound effect, boolean enable) {
        if (effect.setEnabled(enable) != AudioEffect.SUCCESS) {
            throw new IllegalStateException("Enable state did not apply");
        }
    }
}

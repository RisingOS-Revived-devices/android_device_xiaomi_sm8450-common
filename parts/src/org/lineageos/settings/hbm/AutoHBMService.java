package org.lineageos.settings.hbm;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.utils.FileUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class AutoHBMService extends Service {
    private static final String TAG = "AutoHBMService";

    private static final String BACKLIGHT = HbmUtils.BACKLIGHT;

    // SharedPreferences corrupt olsa bile uygulanacak güvenli varsayılanlar.
    private static final float DEFAULT_LUX_THRESHOLD = 20000f;
    private static final long DEFAULT_ENABLE_TIME_SEC = 2L;
    private static final long DEFAULT_DISABLE_TIME_SEC = 5L;
    private static final int DEFAULT_BRIGHTNESS = 255;
    private static final int BACKLIGHT_MAX = HbmUtils.getMaxBacklight();

    // Histerezis: kapatma eşiği açma eşiğinin bu oranı kadar düşük olmalı.
    private static final float HBM_OFF_THRESHOLD_RATIO = 0.65f;
    private static final long MIN_TOGGLE_INTERVAL_MS = 4500L;
    private static final int BRIGHTNESS_RAMP_STEPS = 24;
    private static final long BRIGHTNESS_RAMP_STEP_MS = 70L;

    private static volatile boolean mAutoHBMActive = false;
    private ExecutorService mExecutorService;

    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private KeyguardManager mKeyguardManager;

    private SharedPreferences mSharedPrefs;

    private int mStoredBrightness = -1;
    private int mStoredBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;

    // Sensor thread'in en son okuduğu lux; worker thread sleep sonrası burayı okur (stale closure değil).
    private volatile float mLastLux = 0f;
    private volatile long mLastToggleTime = 0L;
    // Bekleyen enable/disable runnable'ları; lux tersine dönerse iptal edilir.
    private volatile Future<?> mPendingEnable;
    private volatile Future<?> mPendingDisable;
    private volatile boolean mTransitionInProgress = false;
    // Çift unregisterReceiver / shutdown koruması için bayraklar.
    private volatile boolean mReceiverRegistered = false;

    public void activateLightSensorRead() {
        safeSubmit(() -> {
            try {
                if (mSensorManager == null) {
                    mSensorManager = (SensorManager) getApplicationContext()
                            .getSystemService(Context.SENSOR_SERVICE);
                }
                if (mSensorManager == null) {
                    Log.w(TAG, "SensorManager unavailable; skipping sensor register");
                    return;
                }
                if (mLightSensor == null) {
                    mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                }
                if (mLightSensor == null) {
                    Log.w(TAG, "Light sensor not available on this device");
                    return;
                }
                mSensorManager.registerListener(mSensorEventListener, mLightSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Exception e) {
                Log.e(TAG, "activateLightSensorRead failed", e);
            }
        });
    }

    public void deactivateLightSensorRead() {
        cancelPendingEnable();
        cancelPendingDisable();
        safeSubmit(() -> {
            try {
                if (mSensorManager != null) {
                    mSensorManager.unregisterListener(mSensorEventListener);
                }
                mAutoHBMActive = false;
                // Stale from here on; a queued evaluateLux() must not act on it.
                mLastLux = 0f;
                setHbmImmediate(false);
            } catch (Exception e) {
                Log.w(TAG, "deactivateLightSensorRead failed", e);
            }
        });
    }

    private int getCurrentBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS);
        } catch (Exception e) {
            return DEFAULT_BRIGHTNESS;
        }
    }

    private int readBacklightLevel() {
        final String raw = FileUtils.readOneLine(BACKLIGHT);
        if (raw == null) {
            return settingsToBacklight(getCurrentBrightness());
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return settingsToBacklight(getCurrentBrightness());
        }
    }

    private static int settingsToBacklight(int settingsBrightness) {
        return (settingsBrightness * BACKLIGHT_MAX) / DEFAULT_BRIGHTNESS;
    }

    private void rampBacklight(int from, int to) throws InterruptedException {
        if (from == to) {
            FileUtils.writeLine(BACKLIGHT, String.valueOf(to));
            return;
        }
        for (int step = 1; step <= BRIGHTNESS_RAMP_STEPS; step++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            final int value = from + (to - from) * step / BRIGHTNESS_RAMP_STEPS;
            FileUtils.writeLine(BACKLIGHT, String.valueOf(value));
            Thread.sleep(BRIGHTNESS_RAMP_STEP_MS);
        }
    }

    private void rampSettingsBrightness(int from, int to) throws InterruptedException {
        if (from == to) {
            try {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, to);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set screen brightness", e);
            }
            return;
        }
        for (int step = 1; step <= BRIGHTNESS_RAMP_STEPS; step++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            final int value = from + (to - from) * step / BRIGHTNESS_RAMP_STEPS;
            try {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, value);
            } catch (Exception e) {
                Log.w(TAG, "Failed to ramp screen brightness", e);
                return;
            }
            Thread.sleep(BRIGHTNESS_RAMP_STEP_MS);
        }
    }

    private void storeBrightness() {
        if (mStoredBrightness != -1) {
            return;
        }
        mStoredBrightness = getCurrentBrightness();
        mStoredBrightnessMode = HbmUtils.holdManualBrightness(this);
        // A reboot or crash skips the way back, so onCreate needs these to restore them.
        mSharedPrefs.edit()
                .putInt(HbmUtils.KEY_AUTO_SAVED_BRIGHTNESS, mStoredBrightness)
                .putInt(HbmUtils.KEY_AUTO_SAVED_MODE, mStoredBrightnessMode)
                .commit();
    }

    private void clearStoredBrightness() {
        mStoredBrightness = -1;
        mSharedPrefs.edit()
                .remove(HbmUtils.KEY_AUTO_SAVED_BRIGHTNESS)
                .remove(HbmUtils.KEY_AUTO_SAVED_MODE)
                .commit();
    }

    private void transitionToHbmEnabled() throws InterruptedException {
        storeBrightness();
        final int startSettings = mStoredBrightness;
        final int startBacklight = readBacklightLevel();

        HbmUtils.writeHbm(true);
        rampBacklight(startBacklight, BACKLIGHT_MAX);
        rampSettingsBrightness(startSettings, DEFAULT_BRIGHTNESS);
    }

    /** Manual HBM wins: it keeps the panel in HBM and takes over the brightness to go back to. */
    private boolean handOverToManualHbm() {
        if (!HbmUtils.isHbmEnabled(this)) {
            return false;
        }
        if (mStoredBrightness != -1) {
            HbmUtils.setLastBrightness(this, mStoredBrightness, mStoredBrightnessMode);
            clearStoredBrightness();
        }
        return true;
    }

    private void transitionToHbmDisabled() throws InterruptedException {
        if (handOverToManualHbm()) {
            return;
        }
        if (mStoredBrightness == -1) {
            HbmUtils.writeHbm(false);
            return;
        }
        final int targetSettings = mStoredBrightness;
        final int targetBacklight = settingsToBacklight(targetSettings);
        final int startBacklight = readBacklightLevel();

        rampBacklight(startBacklight, targetBacklight);
        HbmUtils.writeHbm(false);
        try {
            HbmUtils.releaseManualBrightness(this, mStoredBrightnessMode, targetSettings);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore screen brightness", e);
        }
        clearStoredBrightness();
    }

    private void setHbmImmediate(boolean enable) {
        try {
            if (enable) {
                storeBrightness();
                HbmUtils.writeHbm(true);
                FileUtils.writeLine(BACKLIGHT, String.valueOf(BACKLIGHT_MAX));
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS);
            } else if (!handOverToManualHbm()) {
                HbmUtils.writeHbm(false);
                if (mStoredBrightness != -1) {
                    HbmUtils.releaseManualBrightness(this, mStoredBrightnessMode,
                            mStoredBrightness);
                    clearStoredBrightness();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "setHbmImmediate(" + enable + ") failed", t);
        }
    }

    private void enableHBM(boolean enable) {
        if (mTransitionInProgress) {
            return;
        }
        mTransitionInProgress = true;
        try {
            if (enable) {
                transitionToHbmEnabled();
            } else {
                transitionToHbmDisabled();
            }
        } catch (InterruptedException ignored) {
            setHbmImmediate(false);
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "enableHBM(" + enable + ") failed", t);
        } finally {
            mTransitionInProgress = false;
        }
    }

    private boolean isCurrentlyEnabled() {
        try {
            return HbmUtils.isHbmActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private float getOffThreshold(float onThreshold) {
        return onThreshold * HBM_OFF_THRESHOLD_RATIO;
    }

    /** The delay, stretched so the toggle lands at least MIN_TOGGLE_INTERVAL_MS after the last. */
    private long toggleWaitMs(long delaySec) {
        return Math.max(delaySec * 1000L,
                mLastToggleTime + MIN_TOGGLE_INTERVAL_MS - SystemClock.elapsedRealtime());
    }

    private void markToggled() {
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    private final SensorEventListener mSensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0) {
                return;
            }
            // The light sensor is on-change: a steady light sends few events, so an
            // average over the last N events stays stuck on old readings. The enable and
            // disable delays below already require the new level to hold.
            mLastLux = event.values[0];
            evaluateLux();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // do nothing
        }
    };

    /**
     * Acts on the last reading: on every event, after a toggle and on unlock, as steady light
     * sends no new event for one skipped during a ramp or on the keyguard.
     */
    private void evaluateLux() {
        try {
            final float lux = mLastLux;

            final KeyguardManager km = mKeyguardManager;
            final boolean keyguardShowing = km != null && km.isKeyguardLocked();

            final float luxThreshold = Math.max(HbmUtils.THRESHOLD_MIN, readFloatPref(
                    HbmUtils.KEY_AUTO_HBM_THRESHOLD, DEFAULT_LUX_THRESHOLD));
            final float offThreshold = getOffThreshold(luxThreshold);
            final long timeToEnableHBM = DEFAULT_ENABLE_TIME_SEC;
            final long timeToDisableHBM = readLongPref(
                    HbmUtils.KEY_HBM_DISABLE_TIME, DEFAULT_DISABLE_TIME_SEC);

            if (mTransitionInProgress) {
                return;
            }

            if (lux > luxThreshold) {
                cancelPendingDisable();
                // Manual HBM owns the brightness while on; holding it here would save its 255 as the user's.
                if ((!mAutoHBMActive || !isCurrentlyEnabled()) && !keyguardShowing
                        && !HbmUtils.isHbmEnabled(this)) {
                    final Future<?> pendingEnable = mPendingEnable;
                    if (pendingEnable == null || pendingEnable.isDone()) {
                        final float enableLuxThreshold = luxThreshold;
                        mPendingEnable = safeSubmit(() -> {
                            final long waitMs = toggleWaitMs(timeToEnableHBM);
                            if (waitMs > 0L) {
                                try {
                                    Thread.sleep(waitMs);
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                            }
                            if (mLastLux > enableLuxThreshold
                                    && (!mAutoHBMActive || !isCurrentlyEnabled())
                                    && !HbmUtils.isHbmEnabled(this)) {
                                enableHBM(true);
                                if (isCurrentlyEnabled()) {
                                    mAutoHBMActive = true;
                                    markToggled();
                                    safeSubmit(this::evaluateLux);
                                }
                            }
                        });
                    }
                }
            } else if (lux < offThreshold) {
                cancelPendingEnable();
                final Future<?> pending = mPendingDisable;
                if (mAutoHBMActive && (pending == null || pending.isDone())) {
                    final float disableLuxThreshold = offThreshold;
                    mPendingDisable = safeSubmit(() -> {
                        final long waitMs = toggleWaitMs(timeToDisableHBM);
                        if (waitMs > 0L) {
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ignored) {
                                return;
                            }
                        }
                        if (mLastLux < disableLuxThreshold && mAutoHBMActive) {
                            enableHBM(false);
                            if (!isCurrentlyEnabled()) {
                                mAutoHBMActive = false;
                                markToggled();
                                safeSubmit(this::evaluateLux);
                            }
                        }
                    });
                }
            } else {
                cancelPendingEnable();
                cancelPendingDisable();
            }
        } catch (Throwable t) {
            Log.e(TAG, "evaluateLux failed", t);
        }
    }

    private float readFloatPref(String key, float defaultVal) {
        try {
            final String raw = mSharedPrefs.getString(key, String.valueOf(defaultVal));
            return raw != null ? Float.parseFloat(raw) : defaultVal;
        } catch (NumberFormatException | ClassCastException | NullPointerException e) {
            return defaultVal;
        }
    }

    private long readLongPref(String key, long defaultVal) {
        try {
            final String raw = mSharedPrefs.getString(key, String.valueOf(defaultVal));
            return raw != null ? Long.parseLong(raw) : defaultVal;
        } catch (NumberFormatException | ClassCastException | NullPointerException e) {
            return defaultVal;
        }
    }

    private void cancelPendingEnable() {
        final Future<?> pending = mPendingEnable;
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
        mPendingEnable = null;
    }

    private void cancelPendingDisable() {
        final Future<?> pending = mPendingDisable;
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
        mPendingDisable = null;
    }

    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                activateLightSensorRead();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                deactivateLightSensorRead();
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                evaluateLux();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutorService = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "AutoHBM-Worker");
            t.setDaemon(true);
            return t;
        });
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        mStoredBrightness = mSharedPrefs.getInt(HbmUtils.KEY_AUTO_SAVED_BRIGHTNESS, -1);
        mStoredBrightnessMode = mSharedPrefs.getInt(HbmUtils.KEY_AUTO_SAVED_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        if (mStoredBrightness != -1) {
            safeSubmit(() -> setHbmImmediate(false));
        }

        final IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenStateFilter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ explicit export flag zorunlu. USER_PRESENT comes from SystemUI, which
                // NOT_EXPORTED drops; all three actions are protected broadcasts.
                registerReceiver(mScreenStateReceiver, screenStateFilter,
                        Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mScreenStateReceiver, screenStateFilter);
            }
            mReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register screen state receiver", e);
        }

        try {
            final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive()) {
                activateLightSensorRead();
            }
        } catch (Exception e) {
            Log.w(TAG, "PowerManager.isInteractive failed", e);
        }
    }

    private Future<?> safeSubmit(Runnable runnable) {
        final ExecutorService exec = mExecutorService;
        if (exec == null || exec.isShutdown()) {
            return null;
        }
        try {
            return exec.submit(runnable);
        } catch (RejectedExecutionException e) {
            // Executor kapanış sırasında yarışı kayıpla — sessiz geç.
            return null;
        }
    }

    private void shutdownExecutor() {
        final ExecutorService exec = mExecutorService;
        if (exec == null) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 1) Yeni sensor event'i gelmemesi için receiver'ı kaldır.
        if (mReceiverRegistered) {
            try {
                unregisterReceiver(mScreenStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered — yarış durumuna karşı sessiz geç.
            } catch (Exception e) {
                Log.w(TAG, "unregisterReceiver failed", e);
            }
            mReceiverRegistered = false;
        }

        // 2) Bekleyen işleri iptal et + temizlik işini kuyruğa koy.
        cancelPendingEnable();
        cancelPendingDisable();
        safeSubmit(() -> {
            try {
                if (mSensorManager != null) {
                    mSensorManager.unregisterListener(mSensorEventListener);
                }
                mAutoHBMActive = false;
                setHbmImmediate(false);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy cleanup failed", e);
            }
        });

        // 3) Executor'u kapat (thread leak yok).
        shutdownExecutor();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

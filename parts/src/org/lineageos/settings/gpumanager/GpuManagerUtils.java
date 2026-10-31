/*
 * Copyright (C) 2025 KamiKaonashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.settings.gpumanager;

import android.os.SystemProperties;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public class GpuManagerUtils {

    private static final String GPU_BASE_PATH = "/sys/class/kgsl/kgsl-3d0";
    private static final String DEVFREQ_PATH = GPU_BASE_PATH + "/devfreq";
    private static final String DEFAULT_GOVERNOR = "msm-adreno-tz";
    
    // GPU paths
    private static final String GPU_MODEL = "/gpu_model";
    private static final String GPU_AVAILABLE_FREQUENCIES = "/gpu_available_frequencies";
    private static final String GPU_CURRENT_FREQ = "/gpuclk";
    private static final String GPU_MIN_FREQ = "/devfreq/min_freq";
    private static final String GPU_MAX_FREQ = "/devfreq/max_freq";
    private static final String GPU_GOVERNOR = "/devfreq/governor";
    private static final String GPU_AVAILABLE_GOVERNORS = "/devfreq/available_governors";
    private static final String GPU_BUSY_PERCENTAGE = "/gpu_busy_percentage";
    private static final String GPU_TEMPERATURE = "/temp";
    private static final String GPU_THERMAL_PWRLEVEL = "/thermal_pwrlevel";
    private static final String GPU_FORCE_BUS_ON = "/force_bus_on";
    private static final String GPU_FORCE_NO_NAP = "/force_no_nap";
    private static final String GPU_BUS_SPLIT = "/bus_split";
    private static final String GPU_MAX_GPUCLK = "/max_gpuclk";

    private static final String PROP_MIN_FREQ = "persist.sys.parts.gpu.min_frequency";
    private static final String PROP_MAX_FREQ = "persist.sys.parts.gpu.max_frequency";
    private static final String PROP_MIN_CLOCK = "persist.sys.parts.gpu.min_clock";
    private static final String PROP_MAX_CLOCK = "persist.sys.parts.gpu.max_clock";

    public String getGpuModel() {
        try {
            return readFile(GPU_BASE_PATH + GPU_MODEL).trim();
        } catch (Exception e) {
            return "Unknown GPU";
        }
    }

    public String[] getAvailableGovernors() {
        try {
            String governors = readFile(GPU_BASE_PATH + GPU_AVAILABLE_GOVERNORS);
            return governors.trim().split("\\s+");
        } catch (Exception e) {
            return new String[]{"msm-adreno-tz", "performance", "powersave", "simple_ondemand"};
        }
    }

    /** Lowest first; kgsl lists them by power level, which starts at the highest clock. */
    public String[] getAvailableFrequencies() {
        try {
            String[] frequencies = readFile(GPU_BASE_PATH + GPU_AVAILABLE_FREQUENCIES)
                    .trim().split("\\s+");
            Arrays.sort(frequencies, Comparator.comparingLong(Long::parseLong));
            return frequencies;
        } catch (Exception e) {
            return null;
        }
    }

    public String getCurrentGovernor() {
        try {
            return readFile(GPU_BASE_PATH + GPU_GOVERNOR).trim();
        } catch (Exception e) {
            return DEFAULT_GOVERNOR;
        }
    }

    public String getCurrentFrequency() {
        try {
            return readFile(GPU_BASE_PATH + GPU_CURRENT_FREQ).trim();
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * The range the user set, not devfreq's: min_freq/max_freq also carry the thermal and boost
     * limits, and the screen would store them as the user's choice on Apply.
     */
    public String getCurrentMinFrequency() {
        return storedOrDefault(PROP_MIN_FREQ, true);
    }

    public String getCurrentMaxFrequency() {
        return storedOrDefault(PROP_MAX_FREQ, false);
    }

    private String storedOrDefault(String prop, boolean lowest) {
        String[] frequencies = getAvailableFrequencies();
        if (frequencies == null || frequencies.length == 0) return "0";
        String stored = SystemProperties.get(prop, "");
        return Arrays.asList(frequencies).contains(stored)
                ? stored : frequencies[lowest ? 0 : frequencies.length - 1];
    }

    public String getGpuBusyPercentage() {
        try {
            return readFile(GPU_BASE_PATH + GPU_BUSY_PERCENTAGE).trim();
        } catch (Exception e) {
            return "0";
        }
    }

    public String getGpuTemperature() {
        try {
            String rawTemp = readFile(GPU_BASE_PATH + GPU_TEMPERATURE).trim();
            int tempMilliCelsius = Integer.parseInt(rawTemp);
            // Convert millidegrees Celsius to degrees Celsius
            double tempCelsius = tempMilliCelsius / 1000.0;
            return String.format("%.1f", tempCelsius);
        } catch (Exception e) {
            return "0";
        }
    }

    public String getThermalPowerLevel() {
        try {
            return readFile(GPU_BASE_PATH + GPU_THERMAL_PWRLEVEL).trim();
        } catch (Exception e) {
            return "0";
        }
    }

    public boolean getForceBusOn() {
        try {
            String value = readFile(GPU_BASE_PATH + GPU_FORCE_BUS_ON).trim();
            return "1".equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean getForceNoNap() {
        try {
            String value = readFile(GPU_BASE_PATH + GPU_FORCE_NO_NAP).trim();
            return "1".equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean getBusSplit() {
        try {
            String value = readFile(GPU_BASE_PATH + GPU_BUS_SPLIT).trim();
            return "1".equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    public void setGovernor(String governor) {
        try {
            writeFile(GPU_BASE_PATH + GPU_GOVERNOR, governor);
        } catch (Exception e) {
            // Ignore errors
        }
    }

    /** Fixes a range stored upside down by the old reset, which pinned the GPU to its lowest clock. */
    public void fixStoredRange() {
        String min = SystemProperties.get(PROP_MIN_FREQ, "");
        String max = SystemProperties.get(PROP_MAX_FREQ, "");
        try {
            if (!min.isEmpty() && !max.isEmpty() && Long.parseLong(min) > Long.parseLong(max)) {
                setFrequencyRange(min, max);
            }
        } catch (NumberFormatException e) {
            // Nothing valid stored
        }
    }

    /** Applies the range now and stores it; init restores it at boot (init.xiaomi_sm8450.rc). */
    public void setFrequencyRange(String minFreq, String maxFreq) {
        try {
            if (Long.parseLong(minFreq) > Long.parseLong(maxFreq)) {
                String lower = maxFreq;
                maxFreq = minFreq;
                minFreq = lower;
            }
        } catch (NumberFormatException e) {
            return;
        }
        try {
            // The clock props must be set first: the frequency triggers read them.
            int minMhz = Integer.parseInt(minFreq) / 1000000;
            int maxMhz = Integer.parseInt(maxFreq) / 1000000;
            SystemProperties.set(PROP_MIN_CLOCK, String.valueOf(minMhz));
            SystemProperties.set(PROP_MAX_CLOCK, String.valueOf(maxMhz));
            SystemProperties.set(PROP_MIN_FREQ, minFreq);
            SystemProperties.set(PROP_MAX_FREQ, maxFreq);
        } catch (RuntimeException e) {
            // Not stored; the values below still apply until reboot.
        }
        // The *_clock_mhz nodes belong to root; init writes them from the props above.
        try {
            writeFile(GPU_BASE_PATH + GPU_MIN_FREQ, minFreq);
            writeFile(GPU_BASE_PATH + GPU_MAX_FREQ, maxFreq);
        } catch (Exception e) {
            // Ignore errors
        }
    }

    public void setForceBusOn(boolean enabled) {
        try {
            writeFile(GPU_BASE_PATH + GPU_FORCE_BUS_ON, enabled ? "1" : "0");
        } catch (Exception e) {
            // Ignore errors
        }
    }

    public void setForceNoNap(boolean enabled) {
        try {
            writeFile(GPU_BASE_PATH + GPU_FORCE_NO_NAP, enabled ? "1" : "0");
        } catch (Exception e) {
            // Ignore errors
        }
    }

    public void setBusSplit(boolean enabled) {
        try {
            writeFile(GPU_BASE_PATH + GPU_BUS_SPLIT, enabled ? "1" : "0");
        } catch (Exception e) {
            // Ignore errors
        }
    }

    public void resetToDefaults() {
        setGovernor(DEFAULT_GOVERNOR);
        String[] frequencies = getAvailableFrequencies();
        if (frequencies != null && frequencies.length > 0) {
            String minFreq = frequencies[0];
            String maxFreq = frequencies[frequencies.length - 1];
            setFrequencyRange(minFreq, maxFreq);
        }
        
        // Reset power settings
        setForceBusOn(false);
        setForceNoNap(false);
        setBusSplit(true);
    }

    private String readFile(String path) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return reader.readLine();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private void writeFile(String path, String value) throws IOException {
        FileWriter writer = null;
        try {
            writer = new FileWriter(path);
            writer.write(value);
            writer.flush();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}

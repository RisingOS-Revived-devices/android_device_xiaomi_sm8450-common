/*
 * Copyright (C) 2025 KamiKaonashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.settings.kernelmanager;

import android.os.SystemProperties;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class KernelManagerUtils {

    // SM8450: cores 0-3 (silver), 4-6 (gold), 7 (prime).
    public static final int EFFICIENCY_CLUSTER = 0;
    public static final int PERFORMANCE_CLUSTER = 4;
    public static final int PRIME_CLUSTER = 7;
    
    private static final int[] POLICIES = {EFFICIENCY_CLUSTER, PERFORMANCE_CLUSTER, PRIME_CLUSTER};
    private static final String DEFAULT_GOVERNOR = "walt";
    private static final String EFFICIENCY_MIN_FREQ = "614400";

    /** persist.sys.parts.cpu.<name>.* props, restored at boot by init.xiaomi_sm8450.rc. */
    private static String propPrefix(int cluster) {
        switch (cluster) {
            case PERFORMANCE_CLUSTER: return "persist.sys.parts.cpu.big.";
            case PRIME_CLUSTER: return "persist.sys.parts.cpu.prime.";
            default: return "persist.sys.parts.cpu.little.";
        }
    }

    private static void store(int cluster, String key, String value) {
        try {
            SystemProperties.set(propPrefix(cluster) + key, value);
        } catch (RuntimeException e) {
            // Not stored; the sysfs write still applies until reboot.
        }
    }
    
    // CPU frequency and governor paths
    private static final String CPU_BASE_PATH = "/sys/devices/system/cpu/cpufreq/policy";
    private static final String SCALING_GOVERNOR = "/scaling_governor";
    private static final String SCALING_MIN_FREQ = "/scaling_min_freq";
    private static final String SCALING_MAX_FREQ = "/scaling_max_freq";
    private static final String SCALING_AVAILABLE_GOVERNORS = "/scaling_available_governors";
    private static final String SCALING_AVAILABLE_FREQUENCIES = "/scaling_available_frequencies";
    private static final String SCALING_BOOST_FREQUENCIES = "/scaling_boost_frequencies";

    public String[] getAvailableGovernors() {
        try {
            String governors = readFile(CPU_BASE_PATH + EFFICIENCY_CLUSTER + SCALING_AVAILABLE_GOVERNORS);
            return governors.trim().split("\\s+");
        } catch (Exception e) {
            return new String[]{"walt", "schedutil", "performance", "powersave"};
        }
    }

    /** Lowest first. The top frequencies are listed apart as boost, and a max below them caps the CPU. */
    public String[] getAvailableFrequencies(int cluster) {
        try {
            String frequencies = readFile(CPU_BASE_PATH + cluster + SCALING_AVAILABLE_FREQUENCIES);
            return (frequencies + " " + getBoostFrequencies(cluster)).trim().split("\\s+");
        } catch (Exception e) {
            return null;
        }
    }

    private String getBoostFrequencies(int cluster) {
        try {
            String frequencies = readFile(CPU_BASE_PATH + cluster + SCALING_BOOST_FREQUENCIES);
            return frequencies != null ? frequencies : "";
        } catch (Exception e) {
            return "";
        }
    }

    public String getCurrentGovernor(int cluster) {
        try {
            return readFile(CPU_BASE_PATH + cluster + SCALING_GOVERNOR).trim();
        } catch (Exception e) {
            return DEFAULT_GOVERNOR;
        }
    }

    /**
     * The range the user set, not cpufreq's: scaling_min/max_freq also carry the thermal and
     * boost limits, and the screen would store them as the user's choice on Apply.
     */
    public String getCurrentMinFrequency(int cluster) {
        return storedOrDefault(cluster, "min_frequency", true);
    }

    public String getCurrentMaxFrequency(int cluster) {
        return storedOrDefault(cluster, "max_frequency", false);
    }

    private String storedOrDefault(int cluster, String key, boolean lowest) {
        String[] frequencies = getAvailableFrequencies(cluster);
        if (frequencies == null || frequencies.length == 0) return "0";
        String stored = SystemProperties.get(propPrefix(cluster) + key, "");
        return Arrays.asList(frequencies).contains(stored)
                ? stored : lowest ? defaultMin(cluster, frequencies) : frequencies[frequencies.length - 1];
    }

    /** The silver cluster's floor from post_boot, same as stock; the others start at their lowest. */
    private static String defaultMin(int cluster, String[] frequencies) {
        return cluster == EFFICIENCY_CLUSTER && Arrays.asList(frequencies).contains(EFFICIENCY_MIN_FREQ)
                ? EFFICIENCY_MIN_FREQ : frequencies[0];
    }

    public void setGovernor(String governor) {
        for (int cluster : POLICIES) {
            setGovernor(cluster, governor);
        }
    }

    public void setGovernor(int cluster, String governor) {
        store(cluster, "governor", governor);
        try {
            writeFile(CPU_BASE_PATH + cluster + SCALING_GOVERNOR, governor);
        } catch (Exception e) {
            // Ignore errors
        }
    }

    public String[] getAvailableGovernors(int cluster) {
        try {
            String governors = readFile(CPU_BASE_PATH + cluster + SCALING_AVAILABLE_GOVERNORS);
            return governors.trim().split("\\s+");
        } catch (Exception e) {
            return getAvailableGovernors();
        }
    }

    /** init writes the stored range as is at boot, and an inverted one pins the cluster. */
    public void fixStoredRanges() {
        for (int cluster : POLICIES) {
            String min = SystemProperties.get(propPrefix(cluster) + "min_frequency", "");
            String max = SystemProperties.get(propPrefix(cluster) + "max_frequency", "");
            try {
                if (!min.isEmpty() && !max.isEmpty()
                        && Long.parseLong(min) > Long.parseLong(max)) {
                    setFrequencyRange(cluster, min, max);
                }
            } catch (NumberFormatException e) {
                // Nothing valid stored
            }
        }
    }

    public void setFrequencyRange(int cluster, String minFreq, String maxFreq) {
        try {
            if (Long.parseLong(minFreq) > Long.parseLong(maxFreq)) {
                String lower = maxFreq;
                maxFreq = minFreq;
                minFreq = lower;
            }
        } catch (NumberFormatException e) {
            return;
        }
        store(cluster, "min_frequency", minFreq);
        store(cluster, "max_frequency", maxFreq);
        try {
            // Set min frequency first
            writeFile(CPU_BASE_PATH + cluster + SCALING_MIN_FREQ, minFreq);
            // Then set max frequency
            writeFile(CPU_BASE_PATH + cluster + SCALING_MAX_FREQ, maxFreq);
        } catch (Exception e) {
            // Ignore errors
        }
    }

    // Cluster-specific helper methods
    public void setEfficiencyClusterFrequency(String minFreq, String maxFreq) {
        setFrequencyRange(EFFICIENCY_CLUSTER, minFreq, maxFreq);
    }
    
    public void setPerformanceClusterFrequency(String minFreq, String maxFreq) {
        setFrequencyRange(PERFORMANCE_CLUSTER, minFreq, maxFreq);
    }

    public void setPrimeClusterFrequency(String minFreq, String maxFreq) {
        setFrequencyRange(PRIME_CLUSTER, minFreq, maxFreq);
    }

    public void resetToDefaults() {
        setGovernor(DEFAULT_GOVERNOR);
        // Reset frequencies to available range
        for (int cluster : POLICIES) {
            String[] frequencies = getAvailableFrequencies(cluster);
            if (frequencies != null && frequencies.length > 0) {
                String minFreq = defaultMin(cluster, frequencies);
                String maxFreq = frequencies[frequencies.length - 1];
                setFrequencyRange(cluster, minFreq, maxFreq);
            }
        }
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

#!/system/bin/sh
# Copyright (C) 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0

{
    echo "Kernel diagnostics begin"
    getprop ro.boot.slot_suffix
    getprop ro.build.version.incremental
    cat /proc/uptime
    grep -E ' (_text|set_machine_constraints|enter_s2idle_proper)$' /proc/kallsyms
    grep -E '^(gh_|qcom_wdt)' /proc/modules
    ls -l /sys/bus/platform/drivers/gh-watchdog
    cat /sys/bus/platform/devices/hypervisor:qcom,gh-watchdog/uevent
    cat /sys/bus/platform/devices/hypervisor:qcom,gh-watchdog/disable
    cat /sys/bus/platform/devices/hypervisor:qcom,gh-watchdog/user_pet_enabled
    # Camera's CAM_ERR uses KERN_INFO, which the previous console level drops.
    dmesg | grep -E 'CAM_ERR|CAM_WARN|CAM-CPAS|gh-watchdog|gh_rm|watchdog|vWDOG|vWDT'
    echo "Kernel diagnostics end"
} 2>&1 | while IFS= read -r line; do
    printf '<3>ZEUS_BOOT %s\n' "$line"
done > /dev/kmsg

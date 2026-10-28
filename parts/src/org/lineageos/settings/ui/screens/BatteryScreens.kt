/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.ui.screens

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lineageos.settings.R
import org.lineageos.settings.charge.ChargeUtils
import org.lineageos.settings.hypercharge.HyperChargeUtils
import org.lineageos.settings.ui.components.ChipGroupCard
import org.lineageos.settings.ui.components.ChoiceRow
import org.lineageos.settings.ui.components.Group
import org.lineageos.settings.ui.components.MainSwitchCard
import org.lineageos.settings.ui.components.NoteText
import org.lineageos.settings.ui.components.PartsPage
import org.lineageos.settings.ui.components.SliderCard
import org.lineageos.settings.ui.components.SwitchRow
import org.lineageos.settings.zram.ZramUtils

@Composable
fun HyperChargeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val supported = remember { HyperChargeUtils.isSupported() }
    val values = stringArrayResource(R.array.hypercharge_limit_values).toList()
    var enabled by remember { mutableStateOf(HyperChargeUtils.isEnabled(context)) }
    var limit by remember { mutableIntStateOf(values.indexOf(HyperChargeUtils.getLimit(context)).coerceAtLeast(0)) }
    val boostSupported = remember { ChargeUtils.isBoostSupported() }
    var boost by remember { mutableStateOf(ChargeUtils.isBoostEnabled(context)) }
    PartsPage(stringResource(R.string.hypercharge_title), onBack) {
        item {
            MainSwitchCard(stringResource(R.string.hypercharge_switch), enabled && supported, enabled = supported) {
                HyperChargeUtils.setEnabled(context, it)
                enabled = it
            }
        }
        item {
            Group(
                { shape ->
                    ChoiceRow(
                        shape = shape,
                        title = stringResource(R.string.hypercharge_limit_title),
                        options = stringArrayResource(R.array.hypercharge_limit_entries).toList(),
                        selected = limit,
                        enabled = enabled && supported,
                    ) {
                        HyperChargeUtils.setLimit(context, values[it])
                        limit = it
                    }
                },
            )
        }
        item {
            Group(
                { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.charge_boost_title),
                        summary = stringResource(if (boostSupported) R.string.charge_boost_summary else R.string.hypercharge_unsupported),
                        checked = boost && boostSupported,
                        enabled = boostSupported,
                    ) {
                        ChargeUtils.setBoostEnabled(context, it)
                        boost = it
                    }
                },
            )
        }
        item {
            NoteText(stringResource(if (supported) R.string.hypercharge_note else R.string.hypercharge_unsupported))
        }
    }
}

private const val REVERSE_REFRESH_MS = 3000L

@Composable
fun ReverseChargeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val supported = remember { ChargeUtils.isReverseSupported() }
    var on by remember { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(ChargeUtils.REVERSE_LIMITS.indexOf(ChargeUtils.getReverseLimit(context))) }
    // The firmware or ChargeService can stop reverse charging while this page is open.
    LaunchedEffect(supported) {
        while (supported) {
            on = withContext(Dispatchers.IO) { ChargeUtils.isReverseOn() }
            delay(REVERSE_REFRESH_MS)
        }
    }
    PartsPage(stringResource(R.string.reverse_charge_title), onBack) {
        item {
            MainSwitchCard(stringResource(R.string.reverse_charge_switch), on && supported, enabled = supported) {
                when (ChargeUtils.setReverseOn(context, it)) {
                    ChargeUtils.REVERSE_BLOCKED_WIRELESS ->
                        Toast.makeText(context, R.string.reverse_charge_blocked_wireless, Toast.LENGTH_SHORT).show()
                    ChargeUtils.REVERSE_BLOCKED_LOW_BATTERY -> Toast.makeText(context,
                        context.getString(R.string.reverse_charge_blocked_low, ChargeUtils.getReverseLimit(context)),
                        Toast.LENGTH_SHORT).show()
                    else -> on = it
                }
            }
        }
        item {
            ChipGroupCard(
                stringResource(R.string.reverse_charge_limit_title),
                ChargeUtils.REVERSE_LIMITS.map { stringResource(R.string.percent_value, it) },
                limit,
                enabled = supported,
            ) {
                ChargeUtils.setReverseLimit(context, ChargeUtils.REVERSE_LIMITS[it])
                limit = it
                on = ChargeUtils.isReverseOn()
            }
        }
        item {
            NoteText(stringResource(if (supported) R.string.reverse_charge_note else R.string.hypercharge_unsupported))
        }
    }
}

private val ZramSizes = intArrayOf(0, 2, 4, 8, -1)
private val ZramAlgorithms = arrayOf("lz4kd", "lz4", "zstd")
private const val SWAPPINESS_DEFAULT = 100

@Composable
fun ZramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val utils = remember { ZramUtils(context) }
    var size by remember {
        mutableIntStateOf(ZramSizes.indexOf(utils.currentZramSize).takeIf { it >= 0 } ?: ZramSizes.lastIndex)
    }
    var algorithm by remember {
        mutableIntStateOf(ZramAlgorithms.indexOf(utils.currentCompression).takeIf { it >= 0 } ?: 0)
    }
    var swappiness by remember { mutableIntStateOf(utils.currentSwappiness) }
    var showRebootDialog by remember { mutableStateOf(false) }

    PartsPage(stringResource(R.string.zram_title), onBack) {
        item {
            ChipGroupCard(stringResource(R.string.zram_size_title), stringArrayResource(R.array.zram_size_entries).toList(), size) {
                if (it != size) {
                    utils.setZramSize(ZramSizes[it])
                    size = it
                    showRebootDialog = true
                }
            }
        }
        item {
            ChipGroupCard(stringResource(R.string.zram_compression_title), ZramAlgorithms.toList(), algorithm) {
                if (it != algorithm) {
                    utils.setCompressionAlgorithm(ZramAlgorithms[it])
                    algorithm = it
                    showRebootDialog = true
                }
            }
        }
        item {
            SliderCard(
                title = stringResource(R.string.swappiness_title),
                value = swappiness,
                range = 0..100,
                defaultValue = SWAPPINESS_DEFAULT,
                onValueChange = { swappiness = it },
                // Applied on release: the setter writes /proc and shows a toast each time.
                onValueChangeFinished = { utils.setSwappiness(it) },
            )
        }
        item { NoteText(stringResource(R.string.zram_note)) }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            confirmButton = {
                TextButton(onClick = { showRebootDialog = false }) { Text(stringResource(android.R.string.ok)) }
            },
            title = { Text(stringResource(R.string.zram_title)) },
            text = { Text(stringResource(R.string.zram_reboot_recommended), style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

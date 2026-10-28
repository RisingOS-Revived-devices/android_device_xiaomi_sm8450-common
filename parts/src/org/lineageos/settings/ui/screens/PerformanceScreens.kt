/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.lineageos.settings.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringArrayResource
import org.lineageos.settings.powerprofile.PowerProfileTileService
import org.lineageos.settings.powerprofile.PowerProfileUtil
import org.lineageos.settings.ui.components.ChipGroupCard
import org.lineageos.settings.ui.components.PillButton
import org.lineageos.settings.utils.TileUtils
import org.lineageos.settings.corecontrol.CoreControlUtils
import org.lineageos.settings.gpumanager.GpuManagerUtils
import org.lineageos.settings.kernelmanager.KernelManagerUtils
import org.lineageos.settings.ui.components.ApplyResetButtons
import org.lineageos.settings.ui.components.ChoiceRow
import org.lineageos.settings.ui.components.Group
import org.lineageos.settings.ui.components.NoteText
import org.lineageos.settings.ui.components.PartsPage
import org.lineageos.settings.ui.components.QuickToggle
import org.lineageos.settings.ui.components.SectionTitle
import org.lineageos.settings.ui.components.StatCard
import org.lineageos.settings.ui.components.SwitchRow

@Composable
fun PowerProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { PowerProfileUtil(context) }
    DisposableEffect(Unit) { onDispose { manager.cleanup() } }
    var mode by remember { mutableIntStateOf(manager.mode) }
    PartsPage(stringResource(R.string.power_profile_title), onBack) {
        item {
            ChipGroupCard(stringResource(R.string.power_profile_mode), stringArrayResource(R.array.power_profile_entries).toList(), mode) {
                manager.setMode(it)
                mode = it
            }
        }
        item { NoteText(stringResource(R.string.power_profile_note)) }
        item {
            PillButton(
                text = stringResource(R.string.tile_add),
                primary = false,
                icon = Icons.Rounded.AddCircleOutline,
                onClick = {
                    TileUtils.requestAddTileService(
                        context, PowerProfileTileService::class.java,
                        R.string.power_profile_title, R.drawable.ic_thermal_balance,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

/** One CPU policy: its governors and frequencies, as written to sysfs (kHz strings). */
private class ClusterState(
    val policy: Int,
    val title: Int,
    val governors: List<String>,
    val governor: String,
    val available: List<String>,
    val min: String,
    val max: String,
)

private val Clusters = listOf(
    KernelManagerUtils.EFFICIENCY_CLUSTER to R.string.efficiency_cluster,
    KernelManagerUtils.PERFORMANCE_CLUSTER to R.string.performance_cluster,
    KernelManagerUtils.PRIME_CLUSTER to R.string.prime_cluster,
)

private fun readCluster(utils: KernelManagerUtils, policy: Int, title: Int): ClusterState? {
    val available = utils.getAvailableFrequencies(policy)?.toList()?.filter { it.isNotBlank() }
    if (available.isNullOrEmpty()) return null
    return ClusterState(
        policy, title, utils.getAvailableGovernors(policy).toList(), utils.getCurrentGovernor(policy),
        available, utils.getCurrentMinFrequency(policy), utils.getCurrentMaxFrequency(policy),
    )
}

private fun khzLabel(value: String) = value.toLongOrNull()?.let { "${it / 1000} MHz" } ?: value
private fun hzLabel(value: String) = value.toLongOrNull()?.let { "${it / 1000000} MHz" } ?: value

@Composable
fun KernelManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val utils = remember { KernelManagerUtils() }
    var reload by remember { mutableIntStateOf(0) }
    val clusters by produceState<List<ClusterState>?>(null, reload) {
        value = withContext(Dispatchers.IO) { Clusters.mapNotNull { (policy, title) -> readCluster(utils, policy, title) } }
    }
    // Selections per policy; only written when Apply is pressed.
    val governor = remember(clusters) { mutableStateMapOf<Int, String>().apply { clusters?.forEach { put(it.policy, it.governor) } } }
    val min = remember(clusters) { mutableStateMapOf<Int, String>().apply { clusters?.forEach { put(it.policy, it.min) } } }
    val max = remember(clusters) { mutableStateMapOf<Int, String>().apply { clusters?.forEach { put(it.policy, it.max) } } }

    PartsPage(stringResource(R.string.kernel_manager_title), onBack) {
        val list = clusters ?: return@PartsPage
        list.forEach { cluster ->
            item { SectionTitle(stringResource(cluster.title)) }
            item {
                val labels = cluster.available.map(::khzLabel)
                Group(
                    { shape ->
                        ChoiceRow(shape, stringResource(R.string.cpu_governor), cluster.governors,
                            cluster.governors.indexOf(governor[cluster.policy]).coerceAtLeast(0)) {
                            governor[cluster.policy] = cluster.governors[it]
                        }
                    },
                    { shape ->
                        ChoiceRow(shape, stringResource(R.string.min_frequency), labels,
                            cluster.available.indexOf(min[cluster.policy]).coerceAtLeast(0)) {
                            min[cluster.policy] = cluster.available[it]
                        }
                    },
                    { shape ->
                        ChoiceRow(shape, stringResource(R.string.max_frequency), labels,
                            cluster.available.indexOf(max[cluster.policy]).coerceAtLeast(0)) {
                            max[cluster.policy] = cluster.available[it]
                        }
                    },
                )
            }
        }
        if (list.isEmpty()) {
            item { NoteText(stringResource(R.string.frequencies_unavailable)) }
        }
        item {
            ApplyResetButtons(
                onApply = {
                    list.forEach { cluster ->
                        governor[cluster.policy]?.let { utils.setGovernor(cluster.policy, it) }
                        val lo = min[cluster.policy]
                        val hi = max[cluster.policy]
                        if (lo != null && hi != null) utils.setFrequencyRange(cluster.policy, lo, hi)
                    }
                    Toast.makeText(context, R.string.settings_applied, Toast.LENGTH_SHORT).show()
                },
                onReset = {
                    utils.resetToDefaults()
                    reload++
                    Toast.makeText(context, R.string.settings_reset, Toast.LENGTH_SHORT).show()
                },
            )
        }
        item { NoteText(stringResource(R.string.kernel_manager_note)) }
    }
}

private class GpuState(
    val model: String,
    val governors: List<String>,
    val governor: String,
    val frequencies: List<String>,
    val min: String,
    val max: String,
    val forceClkOn: Boolean,
    val forceBusOn: Boolean,
    val forceRailOn: Boolean,
    val forceNoNap: Boolean,
    val busSplit: Boolean,
)

private class GpuLive(val frequency: String, val busy: String, val temperature: String, val thermalLevel: String)

@Composable
fun GpuManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val utils = remember { GpuManagerUtils() }
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<GpuState?>(null, reload) {
        value = withContext(Dispatchers.IO) {
            GpuState(
                model = utils.gpuModel,
                governors = utils.availableGovernors.toList(),
                governor = utils.currentGovernor,
                frequencies = utils.availableFrequencies?.toList()?.filter { it.isNotBlank() }.orEmpty(),
                min = utils.currentMinFrequency,
                max = utils.currentMaxFrequency,
                forceClkOn = utils.forceClkOn,
                forceBusOn = utils.forceBusOn,
                forceRailOn = utils.forceRailOn,
                forceNoNap = utils.forceNoNap,
                busSplit = utils.busSplit,
            )
        }
    }
    val unknown = stringResource(R.string.value_unknown)
    val live by produceState<GpuLive?>(null) {
        while (isActive) {
            value = withContext(Dispatchers.IO) {
                val freq = utils.currentFrequency
                val temp = utils.gpuTemperature
                GpuLive(
                    frequency = if (freq == "0") unknown else hzLabel(freq),
                    busy = utils.gpuBusyPercentage,
                    temperature = if (temp == "0") unknown else context.getString(R.string.celsius_value, temp),
                    thermalLevel = context.getString(R.string.gpu_level_value, utils.thermalPowerLevel),
                )
            }
            delay(2000)
        }
    }
    var governor by remember(state) { mutableStateOf(state?.governor) }
    var min by remember(state) { mutableStateOf(state?.min) }
    var max by remember(state) { mutableStateOf(state?.max) }
    var forceClk by remember(state) { mutableStateOf(state?.forceClkOn == true) }
    var forceBus by remember(state) { mutableStateOf(state?.forceBusOn == true) }
    var forceRail by remember(state) { mutableStateOf(state?.forceRailOn == true) }
    var noNap by remember(state) { mutableStateOf(state?.forceNoNap == true) }
    var busSplit by remember(state) { mutableStateOf(state?.busSplit == true) }

    PartsPage(stringResource(R.string.gpu_manager_title), onBack) {
        val s = state ?: return@PartsPage
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                StatCard(stringResource(R.string.gpu_model), s.model, Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stringResource(R.string.gpu_current_freq), live?.frequency ?: "…", Modifier.weight(1f))
                    StatCard(stringResource(R.string.gpu_busy), live?.busy ?: "…", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stringResource(R.string.gpu_temperature), live?.temperature ?: "…", Modifier.weight(1f))
                    StatCard(stringResource(R.string.gpu_thermal_level), live?.thermalLevel ?: "…", Modifier.weight(1f))
                }
            }
        }
        item {
            val labels = s.frequencies.map(::hzLabel)
            val governorRow: @Composable (Shape) -> Unit = { shape ->
                ChoiceRow(shape, stringResource(R.string.gpu_governor), s.governors,
                    s.governors.indexOf(governor).coerceAtLeast(0)) { governor = s.governors[it] }
            }
            if (s.frequencies.isEmpty()) {
                Group(governorRow)
            } else {
                Group(
                    governorRow,
                    { shape ->
                        ChoiceRow(shape, stringResource(R.string.min_frequency), labels,
                            s.frequencies.indexOf(min).coerceAtLeast(0)) { min = s.frequencies[it] }
                    },
                    { shape ->
                        ChoiceRow(shape, stringResource(R.string.max_frequency), labels,
                            s.frequencies.indexOf(max).coerceAtLeast(0)) { max = s.frequencies[it] }
                    },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.gpu_power)) }
        item {
            Group(
                { shape -> SwitchRow(shape, stringResource(R.string.gpu_force_clk_on), stringResource(R.string.gpu_force_clk_on_summary), forceClk) { utils.setForceClkOn(it); forceClk = it } },
                { shape -> SwitchRow(shape, stringResource(R.string.gpu_force_bus_on), stringResource(R.string.gpu_force_bus_on_summary), forceBus) { utils.setForceBusOn(it); forceBus = it } },
                { shape -> SwitchRow(shape, stringResource(R.string.gpu_force_rail_on), stringResource(R.string.gpu_force_rail_on_summary), forceRail) { utils.setForceRailOn(it); forceRail = it } },
                { shape -> SwitchRow(shape, stringResource(R.string.gpu_force_no_nap), stringResource(R.string.gpu_force_no_nap_summary), noNap) { utils.setForceNoNap(it); noNap = it } },
                { shape -> SwitchRow(shape, stringResource(R.string.gpu_bus_split), stringResource(R.string.gpu_bus_split_summary), busSplit) { utils.setBusSplit(it); busSplit = it } },
            )
        }
        item {
            ApplyResetButtons(
                onApply = {
                    governor?.let { utils.setGovernor(it) }
                    val gMin = min
                    val gMax = max
                    if (gMin != null && gMax != null) utils.setFrequencyRange(gMin, gMax)
                    utils.setForceClkOn(forceClk)
                    utils.setForceBusOn(forceBus)
                    utils.setForceRailOn(forceRail)
                    utils.setForceNoNap(noNap)
                    utils.setBusSplit(busSplit)
                    Toast.makeText(context, R.string.settings_applied, Toast.LENGTH_SHORT).show()
                },
                onReset = {
                    utils.resetToDefaults()
                    reload++
                    Toast.makeText(context, R.string.settings_reset, Toast.LENGTH_SHORT).show()
                },
            )
        }
        item { NoteText(stringResource(R.string.gpu_manager_note)) }
    }
}

@Composable
fun CoreControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val online = remember {
        mutableStateListOf<Boolean>().apply {
            repeat(CoreControlUtils.NUM_CORES) { add(CoreControlUtils.isCoreOnline(it)) }
        }
    }
    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) {
            (0 until CoreControlUtils.NUM_CORES).map { CoreControlUtils.isCoreOnline(it) }
        }
        fresh.forEachIndexed { i, v -> online[i] = v }
    }
    PartsPage(stringResource(R.string.core_control_title), onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                (0 until CoreControlUtils.NUM_CORES).chunked(4).forEach { rowCores ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowCores.forEach { core ->
                            QuickToggle(
                                icon = Icons.Rounded.Memory,
                                label = stringResource(R.string.core_label, core),
                                checked = online[core],
                                enabled = true,
                            ) { requested ->
                                if (!requested && !CoreControlUtils.canOffline(core)) {
                                    Toast.makeText(context, R.string.core_control_min_online, Toast.LENGTH_SHORT).show()
                                } else {
                                    CoreControlUtils.setCoreState(core, requested)
                                    online[core] = CoreControlUtils.isCoreOnline(core)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { NoteText(stringResource(R.string.core_control_note)) }
    }
}

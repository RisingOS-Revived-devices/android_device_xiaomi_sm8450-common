/*
 * Copyright (C) 2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lineageos.settings.R
import org.lineageos.settings.misound.MiSound
import org.lineageos.settings.misound.MiSoundUtils
import org.lineageos.settings.ui.components.ActionRow
import org.lineageos.settings.ui.components.ChoiceRow
import org.lineageos.settings.ui.components.Group
import org.lineageos.settings.ui.components.MainSwitchCard
import org.lineageos.settings.ui.components.NoteText
import org.lineageos.settings.ui.components.PartsCard
import org.lineageos.settings.ui.components.PartsPage
import org.lineageos.settings.ui.components.SectionTitle
import org.lineageos.settings.ui.components.SliderCard
import org.lineageos.settings.ui.components.SwitchRow
import org.lineageos.settings.ui.components.WarningCard
import java.io.IOException
import kotlin.math.roundToInt

private const val CLEAN_DURATION_SECONDS = 30
private const val TAG = "ClearSpeaker"

/** Loops the low tone on the media stream with the earpiece clean mode on. */
private class ClearSpeakerPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null

    fun start(): Boolean {
        audioManager.setParameters("status_earpiece_clean=on")
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.isLooping = true
        return try {
            context.resources.openRawResourceFd(R.raw.clear_speaker_sound).use { file ->
                mp.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            mp.setVolume(1.0f, 1.0f)
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to play speaker clean sound!", e)
            mp.release()
            audioManager.setParameters("status_earpiece_clean=off")
            false
        }
    }

    fun stop() {
        player?.let {
            if (it.isPlaying) it.stop()
            it.reset()
            it.release()
        }
        player = null
        audioManager.setParameters("status_earpiece_clean=off")
    }
}

@Composable
fun ClearSpeakerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { ClearSpeakerPlayer(context) }
    var remaining by remember { mutableIntStateOf(0) }
    val playing = remaining > 0

    DisposableEffect(Unit) {
        (context as? Activity)?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { player.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (remaining > 0) {
            player.stop()
            remaining = 0
        }
    }
    LaunchedEffect(playing) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        if (!playing) return@LaunchedEffect
        player.stop()
    }

    PartsPage(stringResource(R.string.clear_speaker_title), onBack) {
        item {
            val bubble by animateColorAsState(
                if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                label = "speaker",
            )
            PartsCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(bubble)
                            .clickable(enabled = !playing, role = Role.Button) {
                                if (player.start()) remaining = CLEAN_DURATION_SECONDS
                            },
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.HourglassTop else Icons.Rounded.WaterDrop,
                            contentDescription = null,
                            tint = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (playing) stringResource(R.string.clear_speaker_running, remaining)
                        else stringResource(R.string.clear_speaker_start),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item { NoteText(stringResource(R.string.clear_speaker_description)) }
    }
}

@Composable
fun MiSoundScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val utils = remember { MiSoundUtils.getInstance(context) }
    val supported = remember { MiSoundUtils.isSupported() }
    var enabled by remember { mutableStateOf(utils.isEnabled) }
    var scenario by remember { mutableIntStateOf(MiSoundUtils.SCENARIOS.indexOf(utils.scenario)) }
    var headset by remember { mutableIntStateOf(MiSoundUtils.HEADSETS.indexOf(utils.headsetType)) }
    var surround by remember { mutableStateOf(utils.is3dSurroundEnabled()) }
    var eqPreset by remember { mutableIntStateOf(utils.eqPreset) }
    val eqLevels = remember { utils.eqLevels.toMutableList().toMutableStateList() }
    // Dolby can be switched in its own app while this page is in the background.
    var resumeTick by remember { mutableIntStateOf(0) }
    var dolbyOn by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(resumeTick) {
        dolbyOn = withContext(Dispatchers.IO) { MiSoundUtils.isDolbyEnabled() }
    }
    val active = supported && enabled

    PartsPage(stringResource(R.string.misound_title), onBack) {
        item {
            // Turning off is always allowed; turning on waits for Dolby to be off.
            MainSwitchCard(stringResource(R.string.misound_switch), active, enabled = supported && (enabled || !dolbyOn)) {
                utils.setEnabled(it)
                enabled = it
            }
        }
        if (supported && dolbyOn) {
            item { WarningCard(stringResource(R.string.misound_dolby_on)) }
            item {
                Group({ shape ->
                    ActionRow(shape, stringResource(R.string.misound_open_dolby)) {
                        try {
                            context.startActivity(MiSoundUtils.dolbySettingsIntent())
                        } catch (e: ActivityNotFoundException) {
                            Log.w("MiSound", "Dolby settings not installed", e)
                        }
                    }
                })
            }
        }
        item {
            Group(
                { shape ->
                    ChoiceRow(
                        shape = shape,
                        title = stringResource(R.string.misound_scenario_title),
                        options = listOf(
                            stringResource(R.string.misound_scenario_smart),
                            stringResource(R.string.misound_scenario_music),
                            stringResource(R.string.misound_scenario_video),
                            stringResource(R.string.misound_scenario_vocal),
                        ),
                        selected = scenario,
                        enabled = active,
                    ) {
                        utils.setScenario(MiSoundUtils.SCENARIOS[it])
                        scenario = it
                    }
                },
                { shape ->
                    ChoiceRow(
                        shape = shape,
                        title = stringResource(R.string.misound_headset_title),
                        options = listOf(
                            stringResource(R.string.misound_headset_general),
                            stringResource(R.string.misound_headset_inear),
                        ),
                        selected = headset,
                        enabled = active,
                    ) {
                        utils.setHeadsetType(MiSoundUtils.HEADSETS[it])
                        headset = it
                    }
                },
                { shape ->
                    SwitchRow(
                        shape = shape,
                        title = stringResource(R.string.misound_3d_title),
                        summary = stringResource(R.string.misound_3d_summary),
                        checked = surround,
                        enabled = active,
                    ) {
                        utils.set3dSurroundEnabled(it)
                        surround = it
                    }
                },
            )
        }
        item { SectionTitle(stringResource(R.string.misound_eq_title)) }
        item {
            Group({ shape ->
                ChoiceRow(
                    shape = shape,
                    title = stringResource(R.string.misound_eq_preset),
                    options = listOf(
                        R.string.misound_eq_custom, R.string.misound_eq_rock, R.string.misound_eq_jazz,
                        R.string.misound_eq_pop, R.string.misound_eq_classical, R.string.misound_eq_hiphop,
                        R.string.misound_eq_blues, R.string.misound_eq_electronic, R.string.misound_eq_metal,
                    ).map { stringResource(it) },
                    selected = eqPreset,
                    enabled = active,
                ) {
                    utils.setEqPreset(it)
                    eqPreset = it
                    utils.eqLevels.forEachIndexed { band, db -> eqLevels[band] = db }
                }
            })
        }
        // Like HyperOS: sliders in % of the +-6 dB range; moving one saves all bands as custom.
        eqLevels.indices.forEach { band ->
            item {
                SliderCard(
                    title = stringResource(R.string.misound_eq_band, band + 1),
                    value = (eqLevels[band] / MiSound.EQ_MAX_DB * 100).roundToInt(),
                    range = -100..100,
                    step = 5,
                    defaultValue = 0,
                    enabled = active,
                    format = { context.getString(R.string.misound_eq_percent, it) },
                    onValueChange = { eqLevels[band] = it * MiSound.EQ_MAX_DB / 100 },
                    onValueChangeFinished = {
                        utils.setEqCustom(eqLevels.toFloatArray())
                        eqPreset = MiSoundUtils.EQ_PRESET_CUSTOM
                    },
                )
            }
        }
        item {
            NoteText(stringResource(if (supported) R.string.misound_note else R.string.misound_unsupported))
        }
    }
}

package com.github.voxqon.xposed.autoflow.engine

import android.media.AudioManager
import android.net.Uri
import android.os.Vibrator
import android.provider.Settings
import com.github.voxqon.xposed.autoflow.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class SoundActionsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun audioLooper() = shadowOf(ShadowLooper.getAllLoopers().first { it.thread.name == "atlt-audio" })

    @Test fun playbackStopsAtTheConfiguredDurationAndReleasesItsResources() {
        val uri = Uri.parse("content://media/internal/audio/media/42")
        Settings.System.putString(app.contentResolver, Settings.System.NOTIFICATION_SOUND, uri.toString())
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, uri), ShadowMediaPlayer.MediaInfo(10_000, 0))
        var player: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { _, created -> player = created }
        val results = mutableListOf<ExecutionStatus>()
        assertTrue(SoundActions.play(app, "tone", SoundSpec(SoundKind.NOTIFICATION, 2)) { status, _ -> results.add(status) })
        audioLooper().idle()
        assertTrue(requireNotNull(player).isReallyPlaying)
        audioLooper().idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(ExecutionStatus.SUCCESS), results)
        assertEquals(ShadowMediaPlayer.State.END, requireNotNull(player).state)
    }

    @Test fun cancellingLoadingPlaybackPreventsALatePreparedCallbackFromRestartingIt() {
        val uri = Uri.parse("content://media/internal/audio/media/42")
        Settings.System.putString(app.contentResolver, Settings.System.NOTIFICATION_SOUND, uri.toString())
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, uri), ShadowMediaPlayer.MediaInfo(10_000, 5_000))
        var player: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { _, created -> player = created }
        val results = mutableListOf<ExecutionStatus>()
        SoundActions.play(app, "cancelled", SoundSpec(SoundKind.NOTIFICATION, 2)) { status, _ -> results.add(status) }
        audioLooper().idle()
        SoundActions.cancelRule("cancelled")
        audioLooper().idle()
        audioLooper().idleFor(Duration.ofSeconds(20))
        assertEquals(listOf(ExecutionStatus.SUCCESS), results)
        assertEquals(ShadowMediaPlayer.State.END, requireNotNull(player).state)
    }

    @Test fun mediaVolumeRestoresOriginalLevelAndInvalidVibrationIsRejected() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val executor = ActionExecutor { app }
        // The invalid persistent ID keeps this restore test entirely in memory.
        val id = "in memory"
        val result = executor.executeAll(id, listOf(Action(ActionType.MEDIA_VOLUME, "100")), true)
        assertEquals(ExecutionStatus.SUCCESS, result.single().status)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(ExecutionStatus.SUCCESS, executor.restoreAll(id).single().status)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        assertEquals(ExecutionStatus.FAILED,
            executor.executeAll("vibrate", listOf(Action(ActionType.VIBRATE, "10001")), false).single().status)
        assertFalse(shadowOf(vibrator).isVibrating)
        assertEquals(ExecutionStatus.SUCCESS,
            executor.executeAll("vibrate", listOf(Action(ActionType.VIBRATE, "500")), false).single().status)
        assertEquals(500L, shadowOf(vibrator).milliseconds)
    }
}

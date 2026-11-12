package com.github.voxqon.xposed.autoflow.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.HandlerThread
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.SoundKind
import com.github.voxqon.xposed.autoflow.model.SoundSpec
import com.github.voxqon.xposed.autoflow.util.XLog

/** Playback and its timeout live off the engine thread; every completion path releases the player. */
object SoundActions {
    private val playbackHandler = lazy { Handler(HandlerThread("atlt-audio").apply { start() }.looper) }
    private val handler by playbackHandler
    private data class Playback(val player: MediaPlayer, val finish: (ExecutionStatus, String) -> Unit)
    private val active = HashMap<String, MutableList<Playback>>()

    fun play(context: Context?, ruleId: String, spec: SoundSpec?,
        completed: (ExecutionStatus, String) -> Unit): Boolean {
        if (context == null || spec == null) return false
        handler.post {
            val player = runCatching { MediaPlayer() }.getOrElse {
                completed(ExecutionStatus.FAILED, it.message ?: "无法初始化声音播放器")
                return@post
            }
            var finished = false
            var timeout: Runnable? = null
            lateinit var playback: Playback
            fun finish(status: ExecutionStatus, message: String) {
                if (finished) return
                finished = true
                timeout?.let(handler::removeCallbacks)
                runCatching { player.release() }
                active[ruleId]?.let { list ->
                    list.remove(playback)
                    if (list.isEmpty()) active.remove(ruleId)
                }
                completed(status, message)
            }
            playback = Playback(player, ::finish)
            active.getOrPut(ruleId) { mutableListOf() }.add(playback)
            try {
                val (type, usage) = when (spec.kind) {
                    SoundKind.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION to AudioAttributes.USAGE_NOTIFICATION
                    SoundKind.ALARM -> RingtoneManager.TYPE_ALARM to AudioAttributes.USAGE_ALARM
                    SoundKind.RINGTONE -> RingtoneManager.TYPE_RINGTONE to AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                }
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, type)
                    ?: error("所选系统声音设为静音，请先在系统声音设置中选择铃声")
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                player.setDataSource(context, uri)
                player.setOnPreparedListener {
                    if (finished) return@setOnPreparedListener
                    try {
                        player.isLooping = true
                        player.start()
                        timeout?.let(handler::removeCallbacks)
                        timeout = Runnable { finish(ExecutionStatus.SUCCESS, "声音播放完成") }
                        handler.postDelayed(requireNotNull(timeout), spec.seconds * 1_000L)
                    } catch (t: Throwable) {
                        finish(ExecutionStatus.FAILED, t.message ?: "无法播放声音")
                    }
                }
                player.setOnErrorListener { _, what, extra ->
                    finish(ExecutionStatus.FAILED, "声音播放失败：$what/$extra")
                    true
                }
                timeout = Runnable { finish(ExecutionStatus.FAILED, "声音加载超时") }
                handler.postDelayed(requireNotNull(timeout), 10_000L)
                player.prepareAsync()
            } catch (t: Throwable) {
                XLog.w("sound playback failed: ${t.message}")
                finish(ExecutionStatus.FAILED, t.message ?: "无法加载系统声音")
            }
        }
        return true
    }

    fun cancelRule(ruleId: String) {
        if (!playbackHandler.isInitialized()) return
        handler.post {
            active[ruleId]?.toList()?.forEach { it.finish(ExecutionStatus.SUCCESS, "规则退出，已停止播放") }
        }
    }
}

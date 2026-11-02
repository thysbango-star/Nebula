package com.nebula.assistant.notch

import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live system activities the Notch can show. Fed by the notification listener and the notch controller. */
object NotchBus {

    data class Media(
        val title: String, val artist: String, val art: Bitmap?, val playing: Boolean,
        val position: Long, val duration: Long, val updatedAt: Long, val pkg: String,
        val controller: MediaController?,
    )

    data class Call(
        val caller: String, val subtitle: String, val ringing: Boolean, val icon: Bitmap?,
        val answer: PendingIntent?, val decline: PendingIntent?, val key: String,
    )

    data class Message(
        val key: String, val app: String, val pkg: String, val sender: String, val text: String, val icon: Bitmap?,
        val open: PendingIntent?, val replyAction: android.app.Notification.Action?, val at: Long,
    )

    private val _media = MutableStateFlow<Media?>(null)
    private val _call = MutableStateFlow<Call?>(null)
    private val _message = MutableStateFlow<Message?>(null)
    val media: StateFlow<Media?> = _media.asStateFlow()
    val call: StateFlow<Call?> = _call.asStateFlow()
    val message: StateFlow<Message?> = _message.asStateFlow()

    /** True while the user drags the Notch size sliders, so the island shows itself for preview. */
    val preview = MutableStateFlow(false)

    fun setMedia(m: Media?) { _media.value = m }
    fun setCall(c: Call?) { _call.value = c }
    fun setMessage(m: Message?) { _message.value = m }
    fun clearMessage(key: String) { if (_message.value?.key == key) _message.value = null }
    fun clearCall(key: String) { if (_call.value?.key == key) _call.value = null }
}

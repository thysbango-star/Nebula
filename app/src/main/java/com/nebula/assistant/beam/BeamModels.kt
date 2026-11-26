package com.nebula.assistant.beam

import android.graphics.Bitmap
import android.net.Uri

/** A real device found on the local network (only Nebula devices that made themselves visible). */
data class BeamDevice(val id: String, val name: String, val type: String, val host: String, val port: Int, val seenAt: Long = System.currentTimeMillis())

/** Where a Beam goes. Devices are discovered; the two system routes are always offered and say what they are. */
sealed class BeamTarget {
    data class Nebula(val device: BeamDevice) : BeamTarget()
    /** Android's own share sheet — Quick Share lives there (and, on phones that support it, Quick Share to AirDrop). */
    object System : BeamTarget()
    /** A one-time page served by this phone over Wi-Fi, opened by scanning a QR code. */
    object Link : BeamTarget()
    val label get() = when (this) { is Nebula -> device.name; System -> "Quick Share & apps"; Link -> "Beam Link" }
}

/** What's being sent. Files are content:// URIs from the system pickers; text is inline. */
data class BeamItem(val uri: Uri?, val name: String, val size: Long, val mime: String, val text: String? = null)

/** Everything the Beam notch shows. Only real states: nothing here is ever simulated. */
sealed class BeamUi {
    object Idle : BeamUi()
    data class Scanning(val devices: List<BeamDevice>, val visible: Boolean, val wifi: Boolean, val note: String = "") : BeamUi()
    data class Connecting(val to: String) : BeamUi()
    data class Waiting(val to: String, val code: String) : BeamUi()
    /** [progress] is the real fraction sent, or null when the route doesn't report it. */
    data class Sending(val to: String, val progress: Float?, val count: Int, val code: String) : BeamUi()
    data class Sent(val to: String, val count: Int, val how: String) : BeamUi()
    data class Failed(val reason: String, val canLink: Boolean, val canRetry: Boolean, val canHotspot: Boolean = false) : BeamUi()
    /**
     * Beam Link is live. Same Wi-Fi: one QR (the link). Hotspot: two steps — [joinQr] joins the phone's own
     * temporary Wi-Fi ([ssid]), then [qr] opens the link. [step] is which one is showing.
     */
    data class Link(val url: String, val qr: Bitmap, val expiresAt: Long, val count: Int, val downloads: Int, val names: List<String>,
                    val joinQr: Bitmap? = null, val ssid: String = "", val pass: String = "", val step: Int = 1) : BeamUi()
    /** Getting Beam Link ready (copying the files so they stay readable, starting the server, checking it answers). */
    data class Preparing(val what: String) : BeamUi()
    data class Incoming(val offer: Offer) : BeamUi()
    data class Receiving(val from: String, val progress: Float, val count: Int) : BeamUi()
    data class Received(val from: String, val count: Int, val saved: List<Uri>, val text: String?) : BeamUi()
}

/** An incoming Beam, waiting for the user's yes or no. */
data class Offer(val id: String, val from: String, val device: String, val items: List<OfferItem>, val code: String, val text: String?) {
    val total get() = items.sumOf { it.size }
}
data class OfferItem(val name: String, val size: Long, val mime: String)

fun humanSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

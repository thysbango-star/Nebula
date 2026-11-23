package com.nebula.assistant.beam

import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Beam's encryption between two Nebula devices: a fresh P-256 key pair per transfer (ECDH), the shared secret
 * hashed into an AES-256-GCM key, files sent in authenticated 64 KB frames. Both screens show the same 6-digit
 * code so people can check nobody is in the middle. Nothing is stored afterwards.
 */
object BeamSecurity {
    private val rnd = SecureRandom()

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
    fun pub(kp: KeyPair): String = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

    class Session(mine: KeyPair, theirs: String) {
        private val secret: ByteArray
        val code: String
        private val key: SecretKeySpec
        private var sendCounter = 0L
        private var recvCounter = 0L
        init {
            val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(theirs, Base64.NO_WRAP)))
            secret = KeyAgreement.getInstance("ECDH").apply { init(mine.private); doPhase(other, true) }.generateSecret()
            val k = MessageDigest.getInstance("SHA-256").digest("nebula-beam-key".toByteArray() + secret)
            key = SecretKeySpec(k, "AES")
            val c = MessageDigest.getInstance("SHA-256").digest("nebula-beam-code".toByteArray() + secret)
            code = "%06d".format((((c[0].toInt() and 0xff) shl 16) or ((c[1].toInt() and 0xff) shl 8) or (c[2].toInt() and 0xff)) % 1_000_000)
        }

        private fun nonce(n: Long, dir: Int) = ByteBuffer.allocate(12).putInt(dir).putLong(n).array()

        /** Writes one encrypted frame: [length][ciphertext+tag]. A zero-length frame ends a file. */
        fun writeFrame(out: DataOutputStream, data: ByteArray, len: Int) {
            if (len == 0) { out.writeInt(0); return }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendCounter++, 1))) }
            val ct = c.doFinal(data, 0, len)
            out.writeInt(ct.size); out.write(ct)
        }

        /** Reads one frame; null at the end of a file. Throws if the data was tampered with. */
        fun readFrame(inp: DataInputStream): ByteArray? {
            val n = inp.readInt()
            if (n == 0) return null
            require(n in 1..(1 shl 20)) { "bad frame" }
            val ct = ByteArray(n); inp.readFully(ct)
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(recvCounter++, 1))) }.doFinal(ct)
        }
    }

    /** Unguessable token for Beam Link URLs (128 bits). */
    fun token(): String { val b = ByteArray(16); rnd.nextBytes(b); return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
}

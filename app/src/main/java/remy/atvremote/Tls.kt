package remy.atvremote

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

// pairing on 6467 teaches the box our client cert, the remote channel on 6466 then authenticates with it
object Tls {
    private const val ALIAS = "atvremote-client"
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // RSA only: the pairing secret hashes the raw modulus/exponent of both certs
    private fun ensureKey() {
        if (keyStore().containsAlias(ALIAS)) return
        // conscrypt signs the TLS handshake through a raw RSA Cipher, without DECRYPT + encryption paddings
        // keystore fails it with INCOMPATIBLE_PADDING_MODE
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=atvremote"))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    // the box's cert is self-signed, so no validation. could pin it at pairing time
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    fun connect(host: String, port: Int): SSLSocket {
        ensureKey()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore(), null) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(TrustAll), SecureRandom()) }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(java.net.InetSocketAddress(host, port), 3000)
        s.startHandshake()
        return s
    }

    private fun outer() = Pb().int(1, 2).int(2, 200)  // protocol_version 2, STATUS_OK
    private fun hexEncoding() = Pb().int(1, 3).int(2, 6)  // ENCODING_TYPE_HEXADECIMAL, 6 symbols

    private fun expect(s: SSLSocket, field: Int): Map<Int, Any> {
        val m = Pb.read(s.inputStream)
        if (m.long(2) != 200L) error("pairing refused (status ${m.long(2)})")
        return m.sub(field) ?: error("unexpected pairing message")
    }

    // stops once the TV shows its code, finishPairing carries on with the same socket
    fun startPairing(host: String): SSLSocket {
        val s = connect(host, PAIRING_PORT)
        Pb.write(s.outputStream, outer().msg(10, Pb().str(1, "atvremote").str(2, Build.MODEL)))
        expect(s, 11)
        Pb.write(s.outputStream, outer().msg(20, Pb().msg(1, hexEncoding()).int(3, 1)))  // ROLE_TYPE_INPUT
        expect(s, 20)
        Pb.write(s.outputStream, outer().msg(30, Pb().msg(1, hexEncoding()).int(2, 1)))
        expect(s, 31)
        return s
    }

    fun finishPairing(s: SSLSocket, code: String) = s.use {
        val client = keyStore().getCertificate(ALIAS).publicKey as RSAPublicKey
        val server = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val h = MessageDigest.getInstance("SHA-256")
        listOf(client.modulus, client.publicExponent, server.modulus, server.publicExponent).forEach { h.update(unsigned(it)) }
        h.update(code.substring(2).hexToByteArray())
        val secret = h.digest()
        if (secret[0] != code.substring(0, 2).toInt(16).toByte()) error("wrong code")
        Pb.write(s.outputStream, outer().msg(40, Pb().bytes(1, secret)))
        expect(s, 41)
    }

    // big-endian magnitude without the sign byte, or the hash won't match the TV's
    private fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it }
}

package com.callagent.gateway.sip

import android.content.Context
import java.io.File

/**
 * Where the imported certificate authorities live.
 *
 * The file is in the app's private storage: the CAs a gateway trusts are
 * part of its configuration, and nothing else on the device has any business
 * reading them.  One file holds every imported CA, PEM blocks concatenated —
 * a second import appends rather than replaces.
 *
 * The storage is an interface so the file handling can be tested on a plain
 * JVM, against a temporary directory, without an Android context.
 */
interface CaStorage {

    /** The imported PEM text, or null when nothing has been imported. */
    fun read(): ByteArray?

    /** Append PEM blocks to the imported set, keeping what was there. */
    fun append(pem: ByteArray)

    fun clear()

    fun isEmpty(): Boolean
}

/** The imported CAs as a single PEM file. */
class FileCaStorage(private val file: File) : CaStorage {

    override fun read(): ByteArray? =
        file.takeIf { it.isFile && it.length() > 0L }?.readBytes()

    override fun append(pem: ByteArray) {
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readBytes() else ByteArray(0)
        val separator = if (existing.isEmpty() || existing.last() == '\n'.code.toByte()) {
            ByteArray(0)
        } else {
            "\n".toByteArray()
        }
        file.writeBytes(existing + separator + pem)
    }

    override fun clear() {
        file.delete()
    }

    override fun isEmpty(): Boolean = !file.isFile || file.length() == 0L
}

/**
 * The app's own store for imported certificate authorities.
 */
object SipCaStore {

    private const val DIR = "gsm2sip-tls"
    private const val FILE = "ca.pem"

    fun storage(context: Context): CaStorage =
        FileCaStorage(File(File(context.filesDir, DIR), FILE))

    fun hasImportedCa(context: Context): Boolean = !storage(context).isEmpty()

    fun appendPem(context: Context, pem: ByteArray) = storage(context).append(pem)

    fun clear(context: Context) = storage(context).clear()
}

/**
 * The trust in force for the current connection.
 *
 * The platform trust store is consulted on every handshake; the imported
 * certificates are read from storage once and cached until the file changes,
 * so a reconnect does not re-read and re-parse them.  A newly imported CA
 * takes effect on the next connect, which is what the user expects after
 * pressing "import" and watching the status line change.
 */
object SipTrustProvider {

    @Volatile
    private var cachedBytes: ByteArray? = null

    @Volatile
    private var cachedTrust: SipTrust? = null

    fun current(storage: CaStorage): SipTrust {
        val pem = storage.read()
        val cached = cachedTrust
        if (cached != null && sameBytes(cachedBytes, pem)) return cached
        val trust = if (pem == null) SipTrust.platformOnly() else SipTrust.fromPem(pem)
        cachedBytes = pem
        cachedTrust = trust
        return trust
    }

    fun current(context: Context): SipTrust = current(SipCaStore.storage(context))

    /** Forget the cache — the next call re-reads the file. */
    fun invalidate() {
        cachedBytes = null
        cachedTrust = null
    }

    private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }
}

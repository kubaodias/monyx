package com.monyx.update

import java.io.File
import java.security.MessageDigest

/**
 * Everything the phone checks about a downloaded APK that does not need Android
 * to answer. Kept free of Android types so the JVM tests can reach all of it.
 *
 * The order is cheapest first and each one names what went wrong, because the
 * installer's own refusal — "App not installed" — says nothing at all.
 */
object ApkChecks {

    enum class Problem { SIZE, CHECKSUM, PACKAGE, NOT_NEWER, SIGNER }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The bytes are the ones the server published. The server row is what
     * scripts/release.mjs verified in the bucket, so a URL that was used to
     * overwrite the object fails here rather than at the installer.
     */
    fun verifyBytes(file: File, expectedSize: Long, expectedSha256: String): Problem? {
        if (file.length() != expectedSize) return Problem.SIZE
        if (!sha256Hex(file).equals(expectedSha256, ignoreCase = true)) return Problem.CHECKSUM
        return null
    }

    /**
     * What the archive says about itself, against what is installed.
     *
     * Signers are compared as sets of SHA-256 digests of each certificate. Equal
     * sets, not overlap: an APK that adds a signer is not the app that is
     * installed, and Android would refuse it anyway — this only refuses first and
     * says why.
     */
    fun verifyIdentity(
        archivePackage: String?,
        archiveVersionCode: Long,
        archiveSigners: Set<String>,
        installedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
    ): Problem? {
        if (archivePackage != installedPackage) return Problem.PACKAGE
        if (archiveVersionCode <= installedVersionCode) return Problem.NOT_NEWER
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) return Problem.SIGNER
        return null
    }

    fun certificateDigest(encoded: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}

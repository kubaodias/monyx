package com.monyx

import com.monyx.update.ApkChecks
import com.monyx.update.ApkChecks.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * What the phone refuses before the installer gets a say. The installer refuses
 * most of these too, but with "App not installed" and nothing else — these are
 * what let the dialog say which thing went wrong.
 */
class ApkChecksTest {

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("apk", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test
    fun `sha256 of a known input`() {
        val file = tempFile("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkChecks.sha256Hex(file))
    }

    @Test
    fun `sha256 streams past one buffer`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ApkChecks.sha256Hex(tempFile(bytes)))
    }

    @Test
    fun `the published bytes pass, in either case of hex`() {
        val file = tempFile("abc".toByteArray())
        assertNull(ApkChecks.verifyBytes(file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertNull(ApkChecks.verifyBytes(file, 3, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    @Test
    fun `a truncated download is a size problem before it is a checksum problem`() {
        val file = tempFile("ab".toByteArray())
        assertEquals(Problem.SIZE, ApkChecks.verifyBytes(file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test
    fun `same length different bytes is a checksum problem`() {
        val file = tempFile("abd".toByteArray())
        assertEquals(Problem.CHECKSUM, ApkChecks.verifyBytes(file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    private val release = setOf("23fb74cc2db098f45fd3d30ab3700b3644cc3b6d2a8ea03b8384b8f9bba71285")

    private fun identity(
        pkg: String? = "com.monyx",
        code: Long = 48,
        signers: Set<String> = release,
    ) = ApkChecks.verifyIdentity(pkg, code, signers, "com.monyx", 47, release)

    @Test
    fun `a newer build of this app with this key passes`() {
        assertNull(identity())
    }

    @Test
    fun `another package is refused, including the debug build`() {
        assertEquals(Problem.PACKAGE, identity(pkg = "com.monyx.debug"))
        assertEquals(Problem.PACKAGE, identity(pkg = null))
    }

    @Test
    fun `the same or an older version is refused`() {
        assertEquals(Problem.NOT_NEWER, identity(code = 47))
        assertEquals(Problem.NOT_NEWER, identity(code = 46))
    }

    @Test
    fun `a different key, an extra key or no key is refused`() {
        assertEquals(Problem.SIGNER, identity(signers = setOf("0".repeat(64))))
        assertEquals(Problem.SIGNER, identity(signers = release + "1".repeat(64)))
        assertEquals(Problem.SIGNER, identity(signers = emptySet()))
    }

    @Test
    fun `certificate digest is lowercase hex sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkChecks.certificateDigest("abc".toByteArray()),
        )
    }
}

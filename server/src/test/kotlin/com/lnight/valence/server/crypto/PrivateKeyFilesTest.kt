package com.lnight.valence.server.crypto

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PrivateKeyFilesTest {
    @Test
    fun `private key files are owner-only`() {
        val directory = Files.createTempDirectory("valence-key-files-")
        val keyFile = PrivateKeyFiles.write(directory, "private.key", "fixture")

        try {
            val fileStore = Files.getFileStore(directory)
            if (fileStore.supportsFileAttributeView("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
            } else {
                val view = Files.getFileAttributeView(keyFile, java.nio.file.attribute.AclFileAttributeView::class.java)
                assertEquals(1, view.acl.size)
                assertEquals(Files.getOwner(directory), view.acl.single().principal())
            }
        } finally {
            Files.deleteIfExists(keyFile)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `the development key tool does not print private key contents`() {
        val directory = Files.createTempDirectory("valence-key-tool-")
        val originalOutput = System.out
        val captured = ByteArrayOutputStream()

        try {
            System.setOut(PrintStream(captured, true, Charsets.UTF_8))
            generateCertificateKeys(arrayOf(directory.toString()))

            val output = captured.toString(Charsets.UTF_8)
            val trustRootKey = Files.readString(directory.resolve("trust-root-private.key"))
            val serverKey = Files.readString(directory.resolve("server-signing-private.key"))
            assertFalse(output.contains(trustRootKey))
            assertFalse(output.contains(serverKey))
        } finally {
            System.setOut(originalOutput)
            Files.deleteIfExists(directory.resolve("trust-root-private.key"))
            Files.deleteIfExists(directory.resolve("server-signing-private.key"))
            Files.deleteIfExists(directory.resolve("server-certificate.txt"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `private key files cannot be written inside a git worktree`() {
        val worktree = Files.createTempDirectory("valence-key-worktree-")
        val keyDirectory = Files.createDirectories(worktree.resolve("keys"))
        Files.createFile(worktree.resolve(".git"))

        try {
            assertFailsWith<IllegalArgumentException> {
                PrivateKeyFiles.write(keyDirectory, "private.key", "fixture")
            }
        } finally {
            Files.deleteIfExists(worktree.resolve(".git"))
            Files.deleteIfExists(keyDirectory)
            Files.deleteIfExists(worktree)
        }
    }
}

package com.lnight.valence.server.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

internal object PrivateKeyFiles {
    fun write(
        directory: Path,
        fileName: String,
        value: String,
    ): Path {
        require(fileName == Path.of(fileName).fileName.toString()) { "The key file name must not contain a path" }
        val outputDirectory = directory.toRealPath()
        require(Files.isDirectory(outputDirectory)) { "The key output path must be a directory" }
        requireOutsideGitWorktree(outputDirectory)

        val fileStore = Files.getFileStore(outputDirectory)
        val supportsPosix = fileStore.supportsFileAttributeView("posix")
        val supportsAcl = fileStore.supportsFileAttributeView("acl")
        check(supportsPosix || supportsAcl) { "The key output file system does not support private file permissions" }

        val keyFile = outputDirectory.resolve(fileName)
        if (supportsPosix) {
            Files.createFile(
                keyFile,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"),
                ),
            )
        } else {
            Files.createFile(keyFile)
        }

        var complete = false
        try {
            if (supportsAcl) restrictAcl(keyFile, outputDirectory)
            Files.writeString(keyFile, value, Charsets.UTF_8)
            complete = true
            return keyFile
        } finally {
            if (!complete) Files.deleteIfExists(keyFile)
        }
    }

    private fun restrictAcl(
        keyFile: Path,
        directory: Path,
    ) {
        val view =
            checkNotNull(Files.getFileAttributeView(keyFile, AclFileAttributeView::class.java)) {
                "The key output file system does not support private file permissions"
            }
        val owner = Files.getOwner(directory)
        val ownerEntry =
            AclEntry
                .newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
        view.acl = listOf(ownerEntry)
    }

    private fun requireOutsideGitWorktree(directory: Path) {
        var ancestor: Path? = directory
        while (ancestor != null) {
            require(!Files.exists(ancestor.resolve(".git"))) { "Private keys must be stored outside a Git worktree" }
            ancestor = ancestor.parent
        }
    }
}

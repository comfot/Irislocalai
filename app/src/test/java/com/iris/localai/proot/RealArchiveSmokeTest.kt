package com.iris.localai.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RealArchiveSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract official alpine minirootfs`() {
        val archive = File(System.getenv("ALPINE_ARCHIVE") ?: "")
        assumeTrue(archive.isFile)

        val target = File(tmp.root, "rootfs").apply { mkdirs() }
        RootfsInstaller(ProotPaths(File(tmp.root, "proot"))).extractTar(archive, target)

        assertTrue(ProotPaths.isUsableRootfs(target))
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString())
        assertTrue(File(target, "etc/alpine-release").isFile)
        assertTrue(File(target, "bin/busybox").canExecute())
    }
}

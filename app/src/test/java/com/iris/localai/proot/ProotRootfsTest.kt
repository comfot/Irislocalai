package com.iris.localai.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ProotRootfsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `installs alpine style rootfs with absolute symlink`() {
        val archive = File(tmp.root, "rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./", '5', ByteArray(0))
            out.writeTarEntry("./bin/", '5', ByteArray(0))
            out.writeTarEntry("./bin/busybox", '0', "busybox".toByteArray())
            out.writeTarEntry("./bin/sh", '2', ByteArray(0), linkName = "/bin/busybox")
            out.writeTarEntry("./etc/", '5', ByteArray(0))
            out.writeTarEntry("./etc/os-release", '0', "ID=alpine".toByteArray())
            out.write(ByteArray(1024))
        }

        val paths = ProotPaths(File(tmp.root, "proot"))
        val installer = RootfsInstaller(paths)
        val rootfs = installer.install("alpine-test", RootfsSource.LocalFile(archive))

        assertTrue(ProotPaths.isUsableRootfs(rootfs))
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(rootfs, "bin/sh").toPath()).toString())
        assertEquals("ID=alpine", File(rootfs, "etc/os-release").readText())
        assertTrue(File(rootfs, "bin/busybox").canExecute())
        assertTrue(File(rootfs, "workspace").isDirectory)
        assertTrue(File(rootfs, "etc/resolv.conf").isFile)
        assertEquals("/bin/sh", ProotPaths.rootfsShell(rootfs))
    }

    @Test
    fun `rejects path traversal entries`() {
        val archive = File(tmp.root, "evil.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("../escape.txt", '0', "nope".toByteArray())
            out.write(ByteArray(1024))
        }
        val target = File(tmp.root, "out").apply { mkdirs() }

        val error = runCatching {
            RootfsInstaller(ProotPaths(File(tmp.root, "proot"))).extractTar(archive, target)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    @Test
    fun `plain tar archive is supported`() {
        val archive = File(tmp.root, "rootfs.tar")
        archive.outputStream().use { out ->
            out.writeTarEntry("bin/", '5', ByteArray(0))
            out.writeTarEntry("bin/sh", '0', "sh".toByteArray())
            out.write(ByteArray(1024))
        }
        val target = File(tmp.root, "out").apply { mkdirs() }

        RootfsInstaller(ProotPaths(File(tmp.root, "proot"))).extractTar(archive, target)

        assertEquals("sh", File(target, "bin/sh").readText())
    }
}

private fun OutputStream.writeTarEntry(
    name: String,
    type: Char,
    data: ByteArray,
    linkName: String = "",
) {
    val header = ByteArray(512)
    header.putText(0, 100, name)
    header.putOctal(100, 8, 0b111_101_101L)
    header.putOctal(108, 8, 0)
    header.putOctal(116, 8, 0)
    header.putOctal(124, 12, data.size.toLong())
    header.putOctal(136, 12, 0)
    header.putText(156, 1, type.toString())
    header.putText(157, 100, linkName)
    header.putText(257, 6, "ustar")
    header.putText(263, 2, "00")

    header.fill(' '.code.toByte(), 148, 156)
    val checksum = header.sumOf { it.toInt() and 0xff }
    header.putText(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")

    write(header)
    if (data.isNotEmpty()) {
        write(data)
        val padding = (512 - data.size % 512) % 512
        if (padding > 0) write(ByteArray(padding))
    }
}

private fun ByteArray.putText(offset: Int, length: Int, value: String) {
    val bytes = value.toByteArray()
    bytes.copyInto(this, offset, 0, minOf(bytes.size, length))
}

private fun ByteArray.putOctal(offset: Int, length: Int, value: Long) {
    putText(offset, length, value.toString(8).padStart(length - 1, '0') + "\u0000")
}

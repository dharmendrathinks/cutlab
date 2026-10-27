package com.dhasharma.cutlab.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhasharma.cutlab.model.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectStoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = ProjectStore(context)
    private fun temporary(name: String) = File(context.cacheDir,"${newId()}-$name")

    @Test fun demoArchiveRoundTripAndCheckpointRecovery() {
        val original = store.createDemo()
        val archive = temporary("portable.zip")
        var imported: Project? = null
        try {
            assertEquals(original,store.load(original.id))
            assertTrue(original.assets.all { File(it.path).isFile })
            store.exportArchive(original,archive)
            val restored = store.importArchive(Uri.fromFile(archive))
            imported = restored
            assertNotEquals(original.id,restored.id)
            assertEquals(original.durationUs(),restored.durationUs())
            assertTrue(restored.assets.all { File(it.path).isFile && it.path.contains(restored.id) })
            assertArrayEquals(File(original.assets[0].path).readBytes(),File(restored.assets[0].path).readBytes())
            store.save(original.copy(name = "Saved change"))
            File(context.filesDir,"projects/${original.id}/project.json").writeText("interrupted")
            assertEquals(original,store.load(original.id))
        } finally { store.delete(original.id); imported?.let { store.delete(it.id) }; archive.delete() }
    }

    @Test fun importsActualVideoAndOwnsItAfterOriginalIsDeleted() {
        val project = Project(name = "Media test").also(store::save)
        val source = temporary("reference.mp4")
        try {
            instrumentation.context.assets.open("reference.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val asset = store.importMedia(project.id,Uri.fromFile(source))
            source.delete()
            assertEquals(AssetKind.VIDEO,asset.kind)
            assertTrue(asset.durationUs > 0)
            assertTrue(asset.width > 0 && asset.height > 0)
            assertTrue(File(asset.path).isFile)
            val saved = project.copy(assets = listOf(asset),tracks = listOf(Track(clips = listOf(Clip(assetId = asset.id,outUs = asset.durationUs)))))
            store.save(saved); assertEquals(saved,store.load(saved.id))
        } finally { source.delete(); store.delete(project.id) }
    }

    @Test fun rejectsTraversalAndInvalidMediaWithoutLeavingFiles() {
        val project = Project().also(store::save)
        val source = temporary("fake.mp4").apply { writeText("not a video") }
        val archive = temporary("attack.zip")
        try {
            assertTrue(runCatching { store.importMedia(project.id,Uri.fromFile(source)) }.isFailure)
            assertTrue(File(context.filesDir,"projects/${project.id}/media").listFiles().orEmpty().isEmpty())
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../escaped.txt")); zip.write("escape".toByteArray()); zip.closeEntry()
            }
            assertTrue(runCatching { store.importArchive(Uri.fromFile(archive)) }.isFailure)
            assertFalse(File(context.filesDir,"projects/escaped.txt").exists())
        } finally { source.delete(); archive.delete(); store.delete(project.id) }
    }

    @Test fun portableArchiveRetainsMissingMediaReferences() {
        val project = store.createDemo()
        val archive = temporary("missing.zip")
        var imported: Project? = null
        try {
            File(project.assets.first().path).delete()
            store.exportArchive(project,archive)
            val restored = store.importArchive(Uri.fromFile(archive))
            imported = restored
            assertEquals(3,restored.assets.size)
            assertFalse(File(restored.assets.first().path).exists())
            assertEquals(project.tracks,restored.tracks)
        } finally { store.delete(project.id); imported?.let { store.delete(it.id) }; archive.delete() }
    }
    @Test fun rejectsOversizedJsonAndCleansStaging() {
        val archive = temporary("oversized.zip")
        val before = File(context.filesDir,"projects").listFiles().orEmpty().map { it.name }.toSet()
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                val block = ByteArray(65536) { 32 }
                repeat((ProjectFiles.MAX_JSON_BYTES/block.size).toInt()+1) { zip.write(block) }
                zip.closeEntry()
            }
            assertTrue(runCatching { store.importArchive(Uri.fromFile(archive)) }.exceptionOrNull()?.message.orEmpty().contains("16 MB"))
            assertEquals(before,File(context.filesDir,"projects").listFiles().orEmpty().map { it.name }.toSet())
        } finally { archive.delete() }
    }
}

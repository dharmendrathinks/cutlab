package com.dhasharma.cutlab.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** File-only persistence, shared with the Android store and JVM recovery tests. */
object ProjectFiles {
    const val MAX_JSON_BYTES: Long = 16L*1024*1024
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(project: Project): String = json.encodeToString(project)
    fun decode(text: String): Project = json.decodeFromString<Project>(text).also {
        require(it.schemaVersion == 1) { "Unsupported project version ${it.schemaVersion}" }
        require(it.width in 1..8192 && it.height in 1..8192) { "Invalid canvas size" }
        val limit = 24L*60*60*1_000_000
        val clips = it.tracks.flatMap { t -> t.clips }
        require(it.assets.size <= 10000 && clips.size <= 10000) { "Project has too many assets or clips" }
        require(clips.map { c -> c.id }.distinct().size == clips.size && it.assets.map { a -> a.id }.distinct().size == it.assets.size) { "Duplicate clip or media identifiers" }
        require(clips.all { c -> c.startUs in 0..limit && c.inUs in 0..limit && c.outUs > c.inUs && c.outUs <= limit && c.speed.isFinite() && c.speed > 0 }) { "Invalid clip timing" }
    }
    fun read(file: File): Project {
        require(file.length() <= MAX_JSON_BYTES) { "Project metadata exceeds 16 MB limit" }
        return decode(file.readText())
    }
    @Synchronized fun save(directory: File, project: Project) {
        directory.mkdirs()
        val current = File(directory,"project.json")
        val temporary = File(directory,"project.json.tmp")
        val encoded = encode(project)
        decode(encoded) // Reject invalid state before replacing any recoverable document.
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Project metadata exceeds 16 MB limit" }
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        if (current.exists()) {
            // Never replace a useful recovery checkpoint with a damaged current file.
            if (runCatching { read(current) }.isSuccess)
                Files.copy(current.toPath(),File(directory,"project.previous.json").toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(temporary.toPath(),current.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun load(directory: File): Project {
        val primary = runCatching { read(File(directory,"project.json")) }
        return primary.getOrElse { failure ->
            runCatching { read(File(directory,"project.previous.json")) }.getOrElse { throw IllegalStateException("Project cannot be read and no valid recovery checkpoint is available",failure) }
        }
    }
    fun safeChild(parent: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "Invalid relative path" }
        val root = parent.canonicalFile
        val file = File(root,relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Path escapes project directory" }
        return file
    }
}

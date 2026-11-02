package idp.sharedkernel.erasure.adapter

import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.ErasureJournalUnavailableException
import idp.sharedkernel.erasure.erasureOf
import idp.sharedkernel.erasure.key
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.DSYNC
import java.nio.file.StandardOpenOption.WRITE

/** For the local environment and tests: a file outside the database volume, one key a line. */
class FileErasureJournal(
    private val file: Path,
) : ErasureJournal {
    /** The file lock keeps two processes on one volume from interleaving lines, and a JVM may hold it only once. */
    override fun record(erasure: Erasure) {
        val line = ByteBuffer.wrap("${erasure.key()}\n".toByteArray())
        guarded {
            synchronized(FileErasureJournal::class.java) {
                FileChannel.open(file, CREATE, WRITE, APPEND, DSYNC).use { channel ->
                    channel.lock().use {
                        while (line.hasRemaining()) channel.write(line)
                    }
                }
            }
        }
    }

    /**
     * A line without its newline is a write that crashed. Its caller failed and records it again. A missing directory is an
     * error and not an empty journal, so a wrong path cannot make a restore skip every erasure.
     */
    override fun entries(): Set<Erasure> =
        guarded {
            if (!Files.isDirectory(file.toAbsolutePath().parent)) throw IOException("No directory for $file")
            if (Files.notExists(file)) return@guarded emptySet()
            Files
                .readString(file)
                .split('\n')
                .dropLast(1)
                .map(::erasureOf)
                .toSet()
        }

    private inline fun <T> guarded(call: () -> T): T =
        try {
            call()
        } catch (e: IOException) {
            throw ErasureJournalUnavailableException("The erasure journal file is unavailable", e)
        }
}

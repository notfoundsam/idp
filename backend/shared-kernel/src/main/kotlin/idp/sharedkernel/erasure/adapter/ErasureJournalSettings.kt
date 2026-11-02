package idp.sharedkernel.erasure.adapter

import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.adapter.aws.S3ErasureJournal
import java.nio.file.Path

/** Exactly one of the bucket and the file: two journals would each miss what the other recorded. */
class ErasureJournalSettings(
    private val bucket: String,
    private val region: String,
    private val file: String,
) {
    fun journal(): ErasureJournal {
        require(bucket.isBlank() != file.isBlank()) { "The erasure journal needs either a bucket or a file" }
        return if (bucket.isNotBlank()) S3ErasureJournal.create(region, bucket) else FileErasureJournal(Path.of(file))
    }
}

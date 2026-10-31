package idp.sharedkernel.scheduling

import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger(JobScheduler::class.java)

interface ScheduledJob {
    /** Its key in `scheduled_locks`, shared by every worker. */
    val name: String
    val interval: Duration

    /** A step longer than the lease needs `lease.renew()` between its steps, or a second worker starts the job. */
    fun run(lease: JobLease)
}

/** Every worker calls `tick` on its own timer, and `scheduled_locks` picks the one that runs each due job. */
class JobScheduler(
    private val jobs: List<ScheduledJob>,
    private val locks: JobLocks,
) {
    init {
        val names = jobs.map { it.name }
        require(names.size == names.toSet().size) { "A job name is its lock, so it must be unique: $names" }
    }

    fun tick() {
        jobs.forEach(::runIfDue)
    }

    private fun runIfDue(job: ScheduledJob) {
        runCatching {
            val lease = locks.acquire(job.name) ?: return
            try {
                job.run(lease)
            } finally {
                lease.release(job.interval)
            }
        }.onFailure { log.error("Scheduled job {} failed", job.name, it) }
    }
}

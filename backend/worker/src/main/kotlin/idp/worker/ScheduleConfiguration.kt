package idp.worker

import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.IdempotentHandler
import idp.sharedkernel.outbox.OutboxCleanupJob
import idp.sharedkernel.outbox.OutboxDispatcher
import idp.sharedkernel.outbox.ProcessedEventsCleanup
import idp.sharedkernel.scheduling.JobLocks
import idp.sharedkernel.scheduling.JobScheduler
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.scheduling.TenantCleanupJob
import idp.sharedkernel.scheduling.TenantJobRunner
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ScheduleConfiguration {
    @Bean
    fun idempotentHandler(transaction: TenantTransaction) = IdempotentHandler(transaction)

    @Bean
    fun outboxDispatcher(
        platform: PlatformTransaction,
        idempotent: IdempotentHandler,
        handlers: ObjectProvider<EventHandler>,
    ) = OutboxDispatcher(platform, idempotent, handlers.orderedStream().toList())

    @Bean
    fun processedEventsCleanup(dispatcher: OutboxDispatcher) = ProcessedEventsCleanup(dispatcher.redeliveryWindow)

    @Bean
    fun jobScheduler(
        platform: PlatformTransaction,
        transaction: TenantTransaction,
        cleanups: ObjectProvider<TenantCleanup>,
    ): JobScheduler {
        val tenants = TenantJobRunner(platform, transaction)
        val jobs = listOf(OutboxCleanupJob(platform)) + cleanups.orderedStream().toList().map { TenantCleanupJob(it, tenants) }
        return JobScheduler(jobs, JobLocks(platform))
    }
}

/** One batch per poll, so a stopping worker finishes its batch and leaves the rest to the others. */
@Component
class WorkerTimers(
    private val dispatcher: OutboxDispatcher,
    private val jobs: JobScheduler,
) {
    @Scheduled(fixedDelayString = "\${idp.outbox.poll-interval}")
    fun pollOutbox() {
        dispatcher.dispatch()
    }

    @Scheduled(fixedDelayString = "\${idp.jobs.tick-interval}")
    fun runDueJobs() {
        jobs.tick()
    }
}

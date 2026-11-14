package idp.worker

import idp.audit.application.AuditService
import idp.authentication.application.AuthenticationService
import idp.federation.application.FederationService
import idp.identity.application.IdentityService
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.adapter.KeyManagementSettings
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.adapter.ErasureJournalSettings
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.tenancy.application.TenancyService
import idp.worker.crypto.KekMoveJob
import idp.worker.crypto.PreviousKekJob
import idp.worker.handlers.EmailChangeMailHandler
import idp.worker.handlers.EmailChangedMailHandler
import idp.worker.handlers.ErasureJournalHandler
import idp.worker.handlers.InvitationMailHandler
import idp.worker.handlers.OpsServiceProviderHandler
import idp.worker.handlers.PasswordResetMailHandler
import idp.worker.handlers.ReinviteAdminHandler
import idp.worker.handlers.TenantSuspendedHandler
import idp.worker.mail.MailAddress
import idp.worker.mail.MailDispatcher
import idp.worker.mail.MailRecipients
import idp.worker.mail.MailTemplates
import idp.worker.offboarding.TenantOffboardingProcess
import idp.worker.provisioning.TenantProvisioningProcess
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mail.javamail.JavaMailSender
import java.time.Duration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {
    @Bean
    fun platformTransaction(dataSource: DataSource) = PlatformTransaction(dataSource)

    @Bean
    fun tenantTransaction(dataSource: DataSource) = TenantTransaction(dataSource)

    @Bean
    fun keyManagement(
        @Value("\${idp.kms.new-keys}") newKeys: String,
        @Value("\${idp.kms.openbao.address}") openBaoAddress: String,
        @Value("\${idp.kms.openbao.token}") openBaoToken: String,
        @Value("\${idp.kms.aws.region}") awsRegion: String,
        @Value("\${idp.kms.aws.key-policy}") awsKeyPolicy: String,
    ): KeyManagementService = KeyManagementSettings(newKeys, openBaoAddress, openBaoToken, awsRegion, awsKeyPolicy).service()

    @Bean
    fun keyHierarchy(
        kms: KeyManagementService,
        transaction: TenantTransaction,
        platform: PlatformTransaction,
        @Value("\${idp.kms.key-cache-ttl}") ttl: Duration,
    ) = KeyHierarchy(kms, transaction, platform, ttl)

    @Bean
    fun kekMoveJob(
        tenancy: TenancyService,
        kms: KeyManagementService,
        keys: KeyHierarchy,
        transaction: TenantTransaction,
        audit: AuditLog,
        @Value("\${idp.kms.new-keys}") newKeys: String,
        @Value("\${idp.kms.previous-kek-retention}") retention: Duration,
        failures: Failures,
    ): ScheduledJob = KekMoveJob(tenancy, kms, keys, transaction, audit, newKeys, retention, failures)

    @Bean
    fun previousKekJob(
        tenancy: TenancyService,
        kms: KeyManagementService,
        transaction: TenantTransaction,
        audit: AuditLog,
        failures: Failures,
    ): ScheduledJob = PreviousKekJob(tenancy, kms, transaction, audit, failures)

    @Bean
    fun auditLog(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
    ): AuditLog = AuditService(transaction, keys)

    @Bean
    fun tenancyService(
        platform: PlatformTransaction,
        audit: AuditLog,
        kms: KeyManagementService,
        @Value("\${idp.kms.platform-kek-uri}") platformKekUri: String,
        @Value("\${idp.tenants.base-domain}") baseDomain: String,
    ) = TenancyService(platform, audit, OutboxPublisher(), FieldCipher.platform(kms, platformKekUri), baseDomain)

    @Bean
    fun identityService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
    ) = IdentityService(transaction, keys, audit, OutboxPublisher())

    @Bean
    fun federationService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
        identity: IdentityService,
    ) = FederationService(transaction, keys, audit, identity)

    @Bean
    fun samlRequestCleanup(federation: FederationService): TenantCleanup = federation.requestCleanup

    @Bean
    fun invitationCleanup(identity: IdentityService): TenantCleanup = identity.invitationCleanup

    @Bean
    fun recoveryCleanup(identity: IdentityService): TenantCleanup = identity.recoveryCleanup

    @Bean
    fun passkeyChallengeCleanup(identity: IdentityService): TenantCleanup = identity.passkeyChallengeCleanup

    /** `worker` starts no flow. The two credential services are here only because the service reads factors through them. */
    @Bean
    fun authenticationService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
        identity: IdentityService,
    ) = AuthenticationService(transaction, keys, audit, identity, TotpService(transaction, keys, audit), PasskeyService(transaction, audit))

    @Bean
    fun flowCleanup(authentication: AuthenticationService): TenantCleanup = authentication.flowCleanup

    @Bean
    fun sessionCleanup(authentication: AuthenticationService): TenantCleanup = authentication.sessionCleanup

    @Bean
    fun tenantSuspendedHandler(authentication: AuthenticationService) = TenantSuspendedHandler(authentication)

    @Bean
    fun mailDispatcher(
        sender: JavaMailSender,
        identity: IdentityService,
        @Value("\${idp.mail.from}") from: String,
    ) = MailDispatcher(sender, mailRecipients(identity), MailTemplates(), from)

    @Bean
    fun invitationMailHandler(
        identity: IdentityService,
        tenancy: TenancyService,
        mail: MailDispatcher,
    ) = InvitationMailHandler(identity, tenancy, mail)

    @Bean
    fun passwordResetMailHandler(
        identity: IdentityService,
        tenancy: TenancyService,
        mail: MailDispatcher,
    ) = PasswordResetMailHandler(identity, tenancy, mail)

    @Bean
    fun emailChangeMailHandler(
        identity: IdentityService,
        tenancy: TenancyService,
        mail: MailDispatcher,
    ) = EmailChangeMailHandler(identity, tenancy, mail)

    @Bean
    fun emailChangedMailHandler(
        identity: IdentityService,
        tenancy: TenancyService,
        mail: MailDispatcher,
    ) = EmailChangedMailHandler(identity, tenancy, mail)

    @Bean
    fun reinviteAdminHandler(
        tenancy: TenancyService,
        identity: IdentityService,
        authentication: AuthenticationService,
        transaction: TenantTransaction,
    ) = ReinviteAdminHandler(tenancy, identity, authentication, transaction)

    @Bean
    fun opsServiceProviderHandler(
        federation: FederationService,
        authentication: AuthenticationService,
        identity: IdentityService,
        @Value("\${idp.tenants.base-domain}") baseDomain: String,
    ) = OpsServiceProviderHandler(federation, authentication, identity, baseDomain)

    @Bean
    fun erasureJournal(
        @Value("\${idp.erasures.s3.bucket}") bucket: String,
        @Value("\${idp.erasures.s3.region}") region: String,
        @Value("\${idp.erasures.file}") file: String,
    ): ErasureJournal = ErasureJournalSettings(bucket, region, file).journal()

    @Bean
    fun erasureJournalHandler(journal: ErasureJournal) = ErasureJournalHandler(journal)

    /** No pool and no bean: provisioning is rare, and a second `DataSource` bean would switch off the runtime one. */
    @Bean
    fun tenantProvisioningProcess(
        tenancy: TenancyService,
        kms: KeyManagementService,
        keys: KeyHierarchy,
        identity: IdentityService,
        federation: FederationService,
        @Value("\${idp.provisioner.url}") url: String,
        @Value("\${idp.provisioner.username}") username: String,
        @Value("\${idp.provisioner.password}") password: String,
    ): TenantProvisioningProcess {
        val provisioner = DriverManagerDataSource(url, username, password)
        return TenantProvisioningProcess(
            tenancy,
            TenantSchemaFactory(provisioner),
            TenantMigrationRunner(provisioner),
            kms,
            keys,
            identity,
            federation,
        )
    }

    /** Through the provisioner for the same reason as [tenantProvisioningProcess]: only `idp_owner` drops a schema and a role. */
    @Bean
    fun tenantOffboardingProcess(
        tenancy: TenancyService,
        kms: KeyManagementService,
        journal: ErasureJournal,
        @Value("\${idp.provisioner.url}") url: String,
        @Value("\${idp.provisioner.username}") username: String,
        @Value("\${idp.provisioner.password}") password: String,
    ) = TenantOffboardingProcess(tenancy, TenantSchemaFactory(DriverManagerDataSource(url, username, password)), kms, journal)

    private fun mailRecipients(identity: IdentityService) =
        MailRecipients { userId, which ->
            when (which) {
                MailAddress.CURRENT -> identity.emailOf(userId)
                MailAddress.PENDING -> identity.pendingEmailOf(userId)
                MailAddress.PREVIOUS -> identity.previousEmailOf(userId)
            }
        }
}

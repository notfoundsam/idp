package idp.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaPackage
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.CompositeArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.SecurityConfig

/** Every rule takes the root package, so the same rule runs on the code and on its fixture. */
object ArchitectureRules {
    val contexts = listOf("tenancy", "identity", "authentication", "federation", "audit")
    val audiences = listOf("login", "account", "admin", "operator")

    val contextGraph =
        mapOf(
            "tenancy" to emptySet(),
            "identity" to emptySet(),
            "audit" to emptySet(),
            "authentication" to setOf("identity"),
            "federation" to setOf("authentication", "identity"),
        )

    /** Tables shared by design list `sharedkernel` as an owner. Every jOOQ table must appear here. */
    val tableOwners =
        mapOf(
            "Tenants" to listOf("tenancy", "sharedkernel"),
            "TenantDomains" to listOf("tenancy", "sharedkernel"),
            "TenantApplications" to listOf("tenancy"),
            "OperatorAudit" to listOf("audit"),
            "AuditEntries" to listOf("audit"),
            "TenantKeys" to listOf("sharedkernel"),
            "UserKeys" to listOf("sharedkernel"),
            "Outbox" to listOf("sharedkernel"),
            "ProcessedEvents" to listOf("sharedkernel"),
            "ScheduledLocks" to listOf("sharedkernel"),
            "Users" to listOf("identity"),
            "UserProfiles" to listOf("identity"),
            "Invitations" to listOf("identity"),
            "PasswordCredentials" to listOf("identity"),
            "TotpCredentials" to listOf("identity"),
            "RecoveryCodes" to listOf("identity"),
            "PasskeyCredentials" to listOf("identity"),
            "PasskeyChallenges" to listOf("identity"),
            "TenantPolicies" to listOf("authentication"),
            "SsoSessions" to listOf("authentication"),
            "Flows" to listOf("authentication"),
        )

    /** Names below the root package. Reaching a message without a principal is a decision, so it is written down here. */
    val publicMessages = emptySet<String>()

    private const val JOOQ_SCHEMAS = """db\.jooq\.(platform|tenant)\.tables"""
    private const val CONTROLLER = "org.springframework.stereotype.Controller"

    private fun composite(rules: List<ArchRule>): ArchRule =
        rules.drop(1).fold(CompositeArchRule.of(rules.first())) { all, rule -> all.and(rule) }

    private fun contextPackages(root: String) = contexts.map { "$root.$it.." }.toTypedArray()

    private fun audiencePackages(root: String) = audiences.map { "$root.audience.$it.." }.toTypedArray()

    private fun contextApi(root: String): ArchRule =
        composite(
            contexts.map { context ->
                noClasses()
                    .that()
                    .resideOutsideOfPackage("$root.$context..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("$root.$context.domain..", "$root.$context.adapters..")
            },
        )

    private fun contextGraph(root: String): ArchRule =
        composite(
            contextGraph.flatMap { (context, allowed) ->
                (contexts - context - allowed).map { other ->
                    noClasses()
                        .that()
                        .resideInAPackage("$root.$context..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("$root.$other..")
                }
            },
        )

    private fun sharedKernelIndependent(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage("$root.sharedkernel..", "$root.db..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*contextPackages(root), "$root.audience..")

    private fun domainPurity(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAPackage("$root..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "org.jooq..", "java.sql..", "javax.sql..")

    private fun awsSdk(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAPackage("$root..")
            .and()
            .resideOutsideOfPackage("$root.sharedkernel..adapter.aws..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("software.amazon..")

    private fun contextsIgnoreAudiences(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage(*contextPackages(root))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$root.audience..")

    private fun audienceIsolation(root: String): ArchRule =
        composite(
            audiences.map { audience ->
                noClasses()
                    .that()
                    .resideInAPackage("$root.audience.$audience..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(*audiencePackages(root).filterNot { it.endsWith(".$audience..") }.toTypedArray())
            },
        )

    private fun deployableAudiences(root: String): ArchRule =
        composite(
            listOf(
                noClasses()
                    .that()
                    .resideInAPackage("$root.app..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("$root.audience.operator.."),
                noClasses()
                    .that()
                    .resideInAPackage("$root.ops..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("$root.audience.login..", "$root.audience.account..", "$root.audience.admin.."),
            ),
        )

    private fun noControllersInContexts(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage(*contextPackages(root))
            .and()
            .resideOutsideOfPackage("$root.federation.adapters.saml..")
            .should()
            .beMetaAnnotatedWith(CONTROLLER)

    private fun workerAndMigratorIsolation(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage("$root.worker..", "$root.migrator..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("$root.audience..", "$root.sharedkernel.bus..")

    private fun noMessagesInContexts(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage(*contextPackages(root))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$root.sharedkernel.bus..")

    private fun throttleOnlyInApp(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage("$root.ops..", "$root.worker..", "$root.migrator..", "$root.audience.operator..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$root.sharedkernel.throttle..")

    /** A `SecurityConfig` holds data only, so an audience declares it as an object or with a no-argument constructor. */
    private fun instanceOf(config: JavaClass): SecurityConfig {
        val type = config.reflect()
        val singleton = type.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
        return (singleton ?: type.getDeclaredConstructor().newInstance()) as SecurityConfig
    }

    private fun securityConfigsOf(audience: JavaPackage): List<SecurityConfig> =
        audience.classesInPackageTree
            .filter { it.isAssignableTo(SecurityConfig::class.java) && !it.isInterface }
            .map(::instanceOf)

    private fun audienceOf(
        message: JavaClass,
        root: String,
    ): JavaPackage {
        val name = message.packageName.removePrefix("$root.audience.").substringBefore('.')
        return generateSequence(message.getPackage()) { it.parent.orElse(null) }.first { it.name == "$root.audience.$name" }
    }

    private fun listedInSecurityConfig(root: String) =
        object : ArchCondition<JavaClass>("be listed in the SecurityConfig of their audience") {
            override fun check(
                message: JavaClass,
                events: ConditionEvents,
            ) {
                val listed = securityConfigsOf(audienceOf(message, root)).flatMap { it.permissions.keys }
                if (listed.none { it.java.name == message.name }) {
                    events.add(SimpleConditionEvent.violated(message, "${message.name} is not in a SecurityConfig of its audience"))
                }
            }
        }

    private fun audienceMessages(root: String): ArchRule =
        classes()
            .that()
            .resideInAPackage("$root.audience..")
            .and()
            .areAssignableTo(Message::class.java)
            .and()
            .areNotInterfaces()
            .should(listedInSecurityConfig(root))

    private fun publicListed(root: String) =
        object : ArchCondition<JavaClass>("make public only the messages in publicMessages") {
            override fun check(
                config: JavaClass,
                events: ConditionEvents,
            ) {
                val public = instanceOf(config).permissions.filterValues { it == null }.keys
                val listed = publicMessages.map { "$root.$it" }.toSet()
                (public.map { it.java.name } - listed).forEach {
                    events.add(SimpleConditionEvent.violated(config, "$it is public but missing from publicMessages"))
                }
            }
        }

    private fun publicMessagesListed(root: String): ArchRule =
        classes()
            .that()
            .resideInAPackage("$root.audience..")
            .and()
            .areAssignableTo(SecurityConfig::class.java)
            .and()
            .areNotInterfaces()
            .should(publicListed(root))

    private fun jooqUsage(root: String): ArchRule =
        noClasses()
            .that()
            .resideInAnyPackage("$root.audience..", "$root..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.jooq..")

    private fun tableOwner(root: String): ArchRule =
        composite(
            tableOwners.map { (table, owners) ->
                val allowed = owners.map { if (it == "sharedkernel") "$root.$it.." else "$root.$it.adapters.persistence.." }
                noClasses()
                    .that()
                    .resideOutsideOfPackages(*allowed.toTypedArray(), "$root.db..")
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching("""${Regex.escape(root)}\.$JOOQ_SCHEMAS\.(records\.)?$table(Record)?""")
            },
        )

    private fun tableUnmapped(root: String): ArchRule =
        classes()
            .that()
            .haveNameMatching("""${Regex.escape(root)}\.$JOOQ_SCHEMAS\.[^.]+""")
            .should()
            .haveNameMatching(""".*\.(${tableOwners.keys.joinToString("|")})(\$.*)?""")

    val all: Map<String, (String) -> ArchRule> =
        mapOf(
            "contextapi" to ::contextApi,
            "contextgraph" to ::contextGraph,
            "sharedkernelindependent" to ::sharedKernelIndependent,
            "domainpurity" to ::domainPurity,
            "awssdk" to ::awsSdk,
            "contextsignoreaudiences" to ::contextsIgnoreAudiences,
            "audienceisolation" to ::audienceIsolation,
            "deployableaudiences" to ::deployableAudiences,
            "nocontrollers" to ::noControllersInContexts,
            "workerisolation" to ::workerAndMigratorIsolation,
            "contextmessages" to ::noMessagesInContexts,
            "throttleonlyinapp" to ::throttleOnlyInApp,
            "audiencemessages" to ::audienceMessages,
            "publicmessages" to ::publicMessagesListed,
            "jooqusage" to ::jooqUsage,
            "tableowner" to ::tableOwner,
            "tableunmapped" to ::tableUnmapped,
        ).mapValues { (_, rule) -> { root: String -> rule(root).allowEmptyShould(true) } }
}

package idp.architecture

import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.CompositeArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

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
            "TenantKeys" to listOf("sharedkernel"),
            "UserKeys" to listOf("sharedkernel"),
            "Outbox" to listOf("sharedkernel"),
            "Users" to listOf("identity"),
        )

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
            "jooqusage" to ::jooqUsage,
            "tableowner" to ::tableOwner,
            "tableunmapped" to ::tableUnmapped,
        ).mapValues { (_, rule) -> { root: String -> rule(root).allowEmptyShould(true) } }
}

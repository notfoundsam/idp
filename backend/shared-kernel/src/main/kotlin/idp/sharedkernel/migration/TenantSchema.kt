package idp.sharedkernel.migration

import java.util.UUID

/** The schema and its role share one name, built only from a UUID, so it is safe inside SQL text. */
data class TenantSchema(
    val id: UUID,
) {
    val name: String = "tenant_" + id.toString().replace("-", "")
}

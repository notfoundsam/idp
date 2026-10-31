package idp.sharedkernel.persistence

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table

class VersionConflictException(
    table: String,
    row: Any?,
    expected: Long,
) : RuntimeException("Row $row of $table is no longer at version $expected")

/** The exception leaves the transaction, so it rolls back everything the aggregate changed with this row. */
class VersionedUpdate<R : Record, ID>(
    private val table: Table<R>,
    private val id: Field<ID>,
    private val version: Field<Long>,
) {
    fun execute(
        dsl: DSLContext,
        row: ID,
        expected: Long,
        changes: Map<out Field<*>, Any?>,
    ): Long {
        val next = expected + 1
        val updated =
            dsl
                .update(table)
                .set(changes)
                .set(version, next)
                .where(id.eq(row), version.eq(expected))
                .execute()
        if (updated == 0) throw VersionConflictException(table.name, row, expected)
        return next
    }
}

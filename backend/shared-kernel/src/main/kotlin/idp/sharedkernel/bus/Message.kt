package idp.sharedkernel.bus

import kotlin.reflect.KClass

enum class TransactionScope {
    TENANT,
    PLATFORM,

    /** For work too slow to hold a PgBouncer connection, such as hashing a password with Argon2id. */
    NONE,
}

sealed interface Message<R> {
    val transaction: TransactionScope get() = TransactionScope.TENANT
}

interface MessageHandler<M : Message<R>, R> {
    val messageType: KClass<M>

    fun handle(message: M): R
}

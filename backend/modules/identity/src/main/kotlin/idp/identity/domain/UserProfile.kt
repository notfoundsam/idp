package idp.identity.domain

import java.util.UUID

internal class UserProfile(
    val userId: UUID,
    nameCiphertext: ByteArray?,
    phoneCiphertext: ByteArray?,
    val version: Long,
) {
    var nameCiphertext = nameCiphertext
        private set
    var phoneCiphertext = phoneCiphertext
        private set

    fun update(
        nameCiphertext: ByteArray?,
        phoneCiphertext: ByteArray?,
    ) {
        this.nameCiphertext = nameCiphertext
        this.phoneCiphertext = phoneCiphertext
    }

    companion object {
        fun empty(userId: UUID) = UserProfile(userId, nameCiphertext = null, phoneCiphertext = null, version = 0)
    }
}

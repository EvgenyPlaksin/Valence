package com.lnight.valence.server.domain

object MailboxIdFormat {
    private val pattern = Regex("""mbx_[a-z2-7]{26}""")

    fun isValid(value: String): Boolean = pattern.matches(value)
}

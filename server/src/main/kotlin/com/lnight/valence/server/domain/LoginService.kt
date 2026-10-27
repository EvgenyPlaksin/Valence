package com.lnight.valence.server.domain

import com.lnight.valence.protocol.SignedMessages

class LoginService(
    private val challenges: ChallengeService,
    private val keyVerifier: PublicKeyVerifier,
    private val store: MailboxStore,
    private val tokens: AccessTokenIssuer,
) {
    fun login(login: Login): LoginResult {
        val rejection = challengeRejection(login) ?: structureRejection(login) ?: credentialsRejection(login)
        return if (rejection != null) {
            LoginResult.Rejected(rejection)
        } else {
            LoginResult.LoggedIn(tokens.issue(login.mailboxId))
        }
    }

    private fun challengeRejection(login: Login): LoginRejection? =
        when (challenges.verify(login.challenge)) {
            ChallengeCheck.Valid -> null
            ChallengeCheck.Malformed -> LoginRejection.MALFORMED
            ChallengeCheck.Forged, ChallengeCheck.Expired -> LoginRejection.INVALID_CHALLENGE
        }

    private fun structureRejection(login: Login): LoginRejection? =
        if (MailboxIdFormat.isValid(login.mailboxId) && login.signature.size == WireSizes.SIGNATURE) {
            null
        } else {
            LoginRejection.MALFORMED
        }

    private fun credentialsRejection(login: Login): LoginRejection? {
        val mailbox = store.find(login.mailboxId) ?: return LoginRejection.INVALID_CREDENTIALS
        val message = SignedMessages.login(login.challenge, login.mailboxId)
        return if (keyVerifier.verifySignature(mailbox.identityKey, message, login.signature)) {
            null
        } else {
            LoginRejection.INVALID_CREDENTIALS
        }
    }
}

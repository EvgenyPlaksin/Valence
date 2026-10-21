package com.lnight.valence.server.domain

sealed interface ChallengeCheck {
    data object Valid : ChallengeCheck

    data object Malformed : ChallengeCheck

    data object Forged : ChallengeCheck

    data object Expired : ChallengeCheck
}

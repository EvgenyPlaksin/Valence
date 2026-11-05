package com.lnight.valence.server

import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.RegistrationService

class ServerServices(
    val challenges: ChallengeService,
    val registration: RegistrationService,
    val login: LoginService,
    val keys: KeyManagementService,
    val bundles: PreKeyBundleService,
    val tokenVerifier: AccessTokenVerifier,
)

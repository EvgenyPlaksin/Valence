package com.lnight.valence.protocol

object ProtocolRoutes {
    const val SEND_MESSAGE = "/v1/messages/{to}"
    const val PRE_KEYS = "/v1/prekeys/{mailboxId}"
    const val SENDER_CERTIFICATE = "/v1/sender-certificate"
    const val AUTH_CHALLENGE = "/v1/auth/challenge"
    const val REGISTER = "/v1/register"
    const val AUTH_TOKEN = "/v1/auth/token"
    const val KEYS_STATUS = "/v1/keys/status"
    const val KEYS_SIGNED = "/v1/keys/signed"
    const val KEYS_ONE_TIME = "/v1/keys/one-time"
}

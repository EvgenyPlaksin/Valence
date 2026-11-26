package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.ErrorCode
import com.lnight.valence.protocol.frame.ErrorFrame
import com.lnight.valence.protocol.frame.MessageDeliverFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.AuthChallengeResponse
import com.lnight.valence.protocol.http.AuthTokenRequest
import com.lnight.valence.protocol.http.KeyStatusResponse
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.PreKeyBundleResponse
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.protocol.http.SendMessageRequest
import com.lnight.valence.protocol.http.SenderCertificateResponse
import com.lnight.valence.protocol.http.SignedPreKeyDto
import com.lnight.valence.protocol.http.UpdateDeliveryKeyRequest
import com.lnight.valence.protocol.http.UploadOneTimeKyberPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import com.lnight.valence.server.crypto.FullStackCertificateFixtures
import com.lnight.valence.server.crypto.FullStackClientKeys
import com.lnight.valence.server.crypto.unsignedToken
import com.lnight.valence.server.crypto.verifyPreKeySignature
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.RateLimitPolicy
import io.ktor.server.application.ApplicationStopPreparing
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass", "LongMethod", "MagicNumber", "TooManyFunctions")
class ServerFullStackIntegrationTest {
    private val activeNodes = mutableListOf<ServerFullStackNode>()

    @BeforeEach
    fun resetDatabase() {
        ServerFullStackContainers.clearDatabase()
    }

    @AfterEach
    fun stopNodes() {
        activeNodes.asReversed().forEach(ServerFullStackNode::close)
        activeNodes.clear()
    }

    @Test
    fun registrationLoginBundleCertificateAndOpaqueDeliveryWorkOverRealHttpAndWebSocket() {
        val certificates = FullStackCertificateFixtures()
        val node = node(certificates = certificates)
        val sender = FullStackClientKeys(SENDER_ID)
        val recipient = FullStackClientKeys(RECIPIENT_ID)
        val senderToken = register(node, sender, version = 1)
        val recipientToken = register(node, recipient, version = 2)

        assertEquals(
            KeyStatusResponse(oneTimeRemaining = 2, oneTimeKyberRemaining = 2),
            keyStatus(node, recipientToken),
        )
        val loginToken = login(node, recipient)
        assertEquals(
            HttpResponseStatus.OK,
            call(node, "GET", ProtocolRoutes.KEYS_STATUS, token = loginToken).statusCode(),
        )

        val bundleResponse = bundle(node, recipient)
        assertEquals(HttpResponseStatus.OK, bundleResponse.statusCode())
        val bundle = decode(PreKeyBundleResponse.serializer(), bundleResponse.body())
        assertEquals(10, bundle.oneTimePreKey?.id)
        assertEquals(20, bundle.kyberPreKey.id)
        verifyPreKeySignature(
            Base64Url.decode(bundle.identityKey),
            Base64Url.decode(bundle.signedPreKey.pub),
            Base64Url.decode(bundle.signedPreKey.sig),
        )
        verifyPreKeySignature(
            Base64Url.decode(bundle.identityKey),
            Base64Url.decode(bundle.kyberPreKey.pub),
            Base64Url.decode(bundle.kyberPreKey.sig),
        )
        assertEquals(
            KeyStatusResponse(oneTimeRemaining = 1, oneTimeKyberRemaining = 1),
            keyStatus(node, recipientToken),
        )

        val certificateResponse =
            call(
                node,
                "GET",
                ProtocolRoutes.SENDER_CERTIFICATE,
                token = senderToken,
            )
        assertEquals(HttpResponseStatus.OK, certificateResponse.statusCode())
        val senderCertificate =
            decode(SenderCertificateResponse.serializer(), certificateResponse.body())
        certificates.verifySenderCertificate(Base64Url.decode(senderCertificate.certificate), Instant.now())

        val socket = connect(node, recipientToken)
        try {
            socket.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
            val firstEnvelope = byteArrayOf(0, 1, 2, 3, 4, 5)
            assertEquals(HttpResponseStatus.CREATED, sendMessage(node, recipient, firstEnvelope).statusCode())
            val firstDelivery = socket.receiveFrame<MessageDeliverFrame>()
            assertEquals(1L, firstDelivery.serverSeq)
            assertContentEquals(firstEnvelope, Base64Url.decode(firstDelivery.envelope))
            socket.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 999)))
            awaitUntil { !node.hasStoredMessages(RECIPIENT_ID) }
        } finally {
            socket.closeNormally()
        }

        val secondEnvelope = byteArrayOf(9, 8, 7, 6)
        assertEquals(HttpResponseStatus.CREATED, sendMessage(node, recipient, secondEnvelope).statusCode())
        val reconnect = connect(node, recipientToken)
        try {
            reconnect.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 1)))
            val replay = reconnect.receiveFrame<MessageDeliverFrame>()
            assertEquals(2L, replay.serverSeq)
            assertContentEquals(secondEnvelope, Base64Url.decode(replay.envelope))
            reconnect.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 2)))
            awaitUntil { !node.hasStoredMessages(RECIPIENT_ID) }
        } finally {
            reconnect.closeNormally()
        }
    }

    @Test
    fun oneTimePrekeysDrainIndependentlyAndHttpKeyManagementEnforcesSignaturesAndCollisions() {
        val node = node()
        val owner = FullStackClientKeys(RECIPIENT_ID)
        val token = register(node, owner)

        val first = bundle(node, owner).body().let { decode(PreKeyBundleResponse.serializer(), it) }
        val second = bundle(node, owner).body().let { decode(PreKeyBundleResponse.serializer(), it) }
        val exhausted = bundle(node, owner).body().let { decode(PreKeyBundleResponse.serializer(), it) }
        assertEquals(10, first.oneTimePreKey?.id)
        assertEquals(20, first.kyberPreKey.id)
        assertEquals(11, second.oneTimePreKey?.id)
        assertEquals(21, second.kyberPreKey.id)
        assertEquals(null, exhausted.oneTimePreKey)
        assertEquals(1, exhausted.kyberPreKey.id)
        assertEquals(KeyStatusResponse(oneTimeRemaining = 0, oneTimeKyberRemaining = 0), keyStatus(node, token))

        val ecUpload =
            UploadOneTimePreKeysRequest(oneTimePreKeys = listOf(owner.newOneTimeEc(30)))
        assertEquals(
            HttpResponseStatus.OK,
            call(
                node,
                "POST",
                ProtocolRoutes.KEYS_ONE_TIME,
                json(ecUpload, UploadOneTimePreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )
        val ecOnlyBundle = bundle(node, owner).body().let { decode(PreKeyBundleResponse.serializer(), it) }
        assertEquals(30, ecOnlyBundle.oneTimePreKey?.id)
        assertEquals(1, ecOnlyBundle.kyberPreKey.id)

        val kyberUpload =
            UploadOneTimeKyberPreKeysRequest(oneTimeKyberPreKeys = listOf(owner.newOneTimeKyber(31)))
        assertEquals(
            HttpResponseStatus.OK,
            call(
                node,
                "POST",
                ProtocolRoutes.KEYS_KYBER_ONE_TIME,
                json(kyberUpload, UploadOneTimeKyberPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )
        val kyberIdCollision =
            ReplaceSignedPreKeysRequest(
                signedPreKey = owner.newSignedEc(2),
                kyberPreKey = owner.newSignedKyber(31),
            )
        assertEquals(
            HttpResponseStatus.CONFLICT,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_SIGNED,
                json(kyberIdCollision, ReplaceSignedPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )
        val ecIdCollision =
            ReplaceSignedPreKeysRequest(
                signedPreKey = owner.newSignedEc(1),
                kyberPreKey = owner.newSignedKyber(3),
            )
        assertEquals(
            HttpResponseStatus.CONFLICT,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_SIGNED,
                json(ecIdCollision, ReplaceSignedPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )
        val kyberOnlyBundle = bundle(node, owner).body().let { decode(PreKeyBundleResponse.serializer(), it) }
        assertEquals(null, kyberOnlyBundle.oneTimePreKey)
        assertEquals(31, kyberOnlyBundle.kyberPreKey.id)

        val lastResortCollision =
            UploadOneTimeKyberPreKeysRequest(oneTimeKyberPreKeys = listOf(owner.newOneTimeKyber(1)))
        assertEquals(
            HttpResponseStatus.CONFLICT,
            call(
                node,
                "POST",
                ProtocolRoutes.KEYS_KYBER_ONE_TIME,
                json(lastResortCollision, UploadOneTimeKyberPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )

        val invalidSignature =
            UploadOneTimeKyberPreKeysRequest(
                oneTimeKyberPreKeys =
                    listOf(
                        KyberPreKeyDto(
                            32,
                            owner.newOneTimeKyber(32).pub,
                            Base64Url.encode(ByteArray(64)),
                        ),
                    ),
            )
        assertEquals(
            HttpResponseStatus.BAD_REQUEST,
            call(
                node,
                "POST",
                ProtocolRoutes.KEYS_KYBER_ONE_TIME,
                json(invalidSignature, UploadOneTimeKyberPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )

        val validReplacement =
            ReplaceSignedPreKeysRequest(
                signedPreKey = owner.newSignedEc(2),
                kyberPreKey = owner.newSignedKyber(3),
            )
        assertEquals(
            HttpResponseStatus.NO_CONTENT,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_SIGNED,
                json(validReplacement, ReplaceSignedPreKeysRequest.serializer()),
                token,
            ).statusCode(),
        )
    }

    @Test
    fun deliveryKeyRotationFencesBothHttpPathsAndRejectsBadAuthenticationAndVersions() {
        val node = node()
        val recipient = FullStackClientKeys(RECIPIENT_ID)
        val token = register(node, recipient)
        val newDeliveryKey = ByteArray(16).also(SecureRandom()::nextBytes)
        val newHash = MessageDigest.getInstance("SHA-256").digest(newDeliveryKey)

        val update =
            UpdateDeliveryKeyRequest(
                v = 2,
                deliveryKeyHash = Base64Url.encode(newHash),
            )
        assertEquals(
            HttpResponseStatus.NO_CONTENT,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_DELIVERY,
                json(update, UpdateDeliveryKeyRequest.serializer()),
                token,
            ).statusCode(),
        )
        assertEquals(
            HttpResponseStatus.NO_CONTENT,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_DELIVERY,
                json(update.copy(v = 1), UpdateDeliveryKeyRequest.serializer()),
                token,
            ).statusCode(),
        )

        val oldSend = sendMessage(node, recipient, byteArrayOf(1))
        assertEquals(HttpResponseStatus.UNAUTHORIZED, oldSend.statusCode())
        assertEquals(HttpResponseStatus.UNAUTHORIZED, bundle(node, recipient).statusCode())
        assertEquals(KeyStatusResponse(oneTimeRemaining = 2, oneTimeKyberRemaining = 2), keyStatus(node, token))

        assertEquals(
            HttpResponseStatus.CREATED,
            sendMessage(node, recipient, byteArrayOf(2), newDeliveryKey).statusCode(),
        )
        assertEquals(HttpResponseStatus.OK, bundle(node, recipient, newDeliveryKey).statusCode())
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(node, "GET", ProtocolRoutes.KEYS_STATUS).statusCode(),
        )
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(node, "GET", ProtocolRoutes.KEYS_STATUS, token = unsignedToken(RECIPIENT_ID)).statusCode(),
        )
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(node, "GET", ProtocolRoutes.KEYS_STATUS, token = node.expiredTokenFor(RECIPIENT_ID)).statusCode(),
        )
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(node, "GET", ProtocolRoutes.KEYS_STATUS, token = node.tokenFor(UNKNOWN_ID)).statusCode(),
        )

        val forgedProofKeys = FullStackClientKeys(UNKNOWN_ID)
        val forgedProofChallenge = challenge(node)
        val forgedProofRequest =
            forgedProofKeys
                .registration(forgedProofChallenge.challenge)
                .copy(proof = Base64Url.encode(ByteArray(64)))
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(
                node,
                "POST",
                ProtocolRoutes.REGISTER,
                json(forgedProofRequest, RegisterRequest.serializer()),
            ).statusCode(),
        )
        val forgedPreKeyChallenge = challenge(node)
        val validProofInvalidKey =
            forgedProofKeys
                .registration(forgedPreKeyChallenge.challenge)
                .copy(
                    kyberPreKey =
                        forgedProofKeys
                            .newSignedKyber(2)
                            .copy(sig = Base64Url.encode(ByteArray(64))),
                )
        assertEquals(
            HttpResponseStatus.UNAUTHORIZED,
            call(
                node,
                "POST",
                ProtocolRoutes.REGISTER,
                json(validProofInvalidKey, RegisterRequest.serializer()),
            ).statusCode(),
        )

        for (version in listOf(0, 3)) {
            val invalidVersion = update.copy(v = version)
            assertEquals(
                HttpResponseStatus.BAD_REQUEST,
                call(
                    node,
                    "PUT",
                    ProtocolRoutes.KEYS_DELIVERY,
                    json(invalidVersion, UpdateDeliveryKeyRequest.serializer()),
                    token,
                ).statusCode(),
            )
            assertEquals(
                HttpResponseStatus.BAD_REQUEST,
                sendMessage(node, recipient, byteArrayOf(3), newDeliveryKey, version).statusCode(),
            )
        }
        assertEquals(
            HttpResponseStatus.BAD_REQUEST,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_DELIVERY,
                """{"v":2,"deliveryKeyHash":"${Base64Url.encode(newHash)}="}""",
                token,
            ).statusCode(),
        )
        val wrongLength = update.copy(deliveryKeyHash = Base64Url.encode(ByteArray(31)))
        assertEquals(
            HttpResponseStatus.BAD_REQUEST,
            call(
                node,
                "PUT",
                ProtocolRoutes.KEYS_DELIVERY,
                json(wrongLength, UpdateDeliveryKeyRequest.serializer()),
                token,
            ).statusCode(),
        )
    }

    @Test
    fun twoNodesDeliverOverRedisAndFenceSupersededSockets() {
        val certificates = FullStackCertificateFixtures()
        val tokenSigningKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val redisConfiguration = ServerFullStackContainers.redisConfiguration()
        val firstNode = node(redis = redisConfiguration, certificates = certificates, tokenKey = tokenSigningKey)
        val secondNode = node(redis = redisConfiguration, certificates = certificates, tokenKey = tokenSigningKey)
        val sender = FullStackClientKeys(SENDER_ID)
        val recipient = FullStackClientKeys(RECIPIENT_ID)
        register(firstNode, sender)
        val recipientToken = register(secondNode, recipient)
        awaitRedisConnected(firstNode)
        awaitRedisConnected(secondNode)

        val firstSocket = connect(secondNode, recipientToken)
        firstSocket.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
        val firstEnvelope = byteArrayOf(1, 3, 3, 7)
        assertEquals(HttpResponseStatus.CREATED, sendMessage(firstNode, recipient, firstEnvelope).statusCode())
        val firstDelivery = firstSocket.receiveFrame<MessageDeliverFrame>()
        assertEquals(1L, firstDelivery.serverSeq)
        assertContentEquals(firstEnvelope, Base64Url.decode(firstDelivery.envelope))
        firstSocket.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 1)))
        awaitUntil { !secondNode.hasStoredMessages(RECIPIENT_ID) }
        firstSocket.closeNormally()

        val secondEnvelope = byteArrayOf(4, 2, 4, 2)
        assertEquals(HttpResponseStatus.CREATED, sendMessage(firstNode, recipient, secondEnvelope).statusCode())
        val reconnect = connect(secondNode, recipientToken)
        reconnect.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 1)))
        val replay = reconnect.receiveFrame<MessageDeliverFrame>()
        assertEquals(2L, replay.serverSeq)
        assertContentEquals(secondEnvelope, Base64Url.decode(replay.envelope))
        reconnect.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 2)))
        awaitUntil { !secondNode.hasStoredMessages(RECIPIENT_ID) }

        val replacement = connect(firstNode, recipientToken)
        try {
            assertEquals(4000, reconnect.receiveClose().statusCode)
            replacement.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 2)))
        } finally {
            replacement.closeNormally()
            reconnect.closeNormally()
        }
    }

    @Test
    fun redisOutageFallsBackToPollingAndSubscribersRecover() {
        val isolatedRedis = ServerFullStackContainers.isolatedRedis()
        isolatedRedis.start()
        try {
            val certificates = FullStackCertificateFixtures()
            val tokenSigningKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val redisConfiguration =
                RedisConfiguration(
                    "redis://127.0.0.1:${isolatedRedis.getMappedPort(6379)}",
                    "valence:isolated-outage:${System.nanoTime()}",
                )
            val senderNode = node(redis = redisConfiguration, certificates = certificates, tokenKey = tokenSigningKey)
            val receiverNode = node(redis = redisConfiguration, certificates = certificates, tokenKey = tokenSigningKey)
            val sender = FullStackClientKeys(SENDER_ID)
            val recipient = FullStackClientKeys(RECIPIENT_ID)
            register(senderNode, sender)
            val token = register(receiverNode, recipient)
            awaitRedisConnected(senderNode)
            awaitRedisConnected(receiverNode)
            val socket = connect(receiverNode, token)
            try {
                socket.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
                isolatedRedis.stop()
                awaitRedisDisconnected(receiverNode)

                val envelope = byteArrayOf(5, 1, 5, 1)
                assertEquals(HttpResponseStatus.CREATED, sendMessage(senderNode, recipient, envelope).statusCode())
                val delivered = socket.receiveFrame<MessageDeliverFrame>(timeoutSeconds = 60)
                assertEquals(1L, delivered.serverSeq)
                assertContentEquals(envelope, Base64Url.decode(delivered.envelope))

                isolatedRedis.start()
                awaitRedisConnected(senderNode, timeoutSeconds = 60)
                awaitRedisConnected(receiverNode, timeoutSeconds = 60)
            } finally {
                socket.closeNormally()
            }
        } finally {
            isolatedRedis.stop()
        }
    }

    @Test
    fun rateLimitsMetricsAndWireSizeErrorsAreVisibleOverTheRealServer() {
        val policies =
            RateLimitPolicies(
                challengePerAddress = RateLimitPolicy(burst = 1, refillPerSecond = 0.01),
                socketFrames = RateLimitPolicy(burst = 10, refillPerSecond = 0.1),
            )
        val node = node(policies = policies, metricsToken = METRICS_TOKEN)
        val limitedIp = mapOf("X-Forwarded-For" to "198.51.100.201")
        val firstChallenge = call(node, "POST", ProtocolRoutes.AUTH_CHALLENGE, headers = limitedIp)
        assertEquals(HttpResponseStatus.OK, firstChallenge.statusCode())
        val limitedChallenge = call(node, "POST", ProtocolRoutes.AUTH_CHALLENGE, headers = limitedIp)
        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS, limitedChallenge.statusCode())
        assertNotNull(limitedChallenge.headers().firstValue("Retry-After").orElse(null))

        val recipient = FullStackClientKeys(RECIPIENT_ID)
        register(node, recipient, ip = "198.51.100.202")

        val metricsUnauthorized = call(node, "GET", "/metrics")
        assertEquals(HttpResponseStatus.UNAUTHORIZED, metricsUnauthorized.statusCode())
        val metrics =
            call(node, "GET", "/metrics", token = METRICS_TOKEN)
        assertEquals(HttpResponseStatus.OK, metrics.statusCode())
        assertTrue(metrics.body().contains("valence_hikari_connections_max 10"))
        assertTrue(metrics.body().contains("""scope="challenge",status="429""""))

        val oversizedSend =
            call(
                node,
                "POST",
                ProtocolRoutes.SEND_MESSAGE.replace("{to}", RECIPIENT_ID),
                json(
                    SendMessageRequest(envelope = Base64Url.encode(ByteArray(80_000) { 1 })),
                    SendMessageRequest.serializer(),
                ),
                headers =
                    mapOf(
                        ProtocolHeaders.DELIVERY_KEY to Base64Url.encode(recipient.deliveryKey),
                        "X-Forwarded-For" to "198.51.100.203",
                    ),
            )
        assertEquals(HttpResponseStatus.PAYLOAD_TOO_LARGE, oversizedSend.statusCode())

        node.seedInbox(RECIPIENT_ID, 1_000)
        val quotaRejection = sendMessage(node, recipient, byteArrayOf(4))
        assertEquals(HttpResponseStatus.TOO_MANY_REQUESTS, quotaRejection.statusCode())
        assertEquals("300", quotaRejection.headers().firstValue("Retry-After").orElse(null))

        val invalidVersions = listOf(0, 3)
        for (version in invalidVersions) {
            val versionIp = "198.51.100.${210 + version}"
            val challenge = challenge(node, versionIp)
            val invalidRegistration = recipient.registration(challenge.challenge, version)
            assertEquals(
                HttpResponseStatus.BAD_REQUEST,
                call(
                    node,
                    "POST",
                    ProtocolRoutes.REGISTER,
                    json(invalidRegistration, RegisterRequest.serializer()),
                    headers = mapOf("X-Forwarded-For" to versionIp),
                ).statusCode(),
            )
        }

        val socketErrorToken = register(node, FullStackClientKeys(SENDER_ID))
        val socketErrors = connect(node, socketErrorToken)
        try {
            socketErrors.send("not-json")
            assertEquals(ErrorCode.INVALID_FRAME, socketErrors.receiveFrame<ErrorFrame>().code)
            socketErrors.sendBinary(byteArrayOf(1, 2, 3))
            assertEquals(ErrorCode.INVALID_FRAME, socketErrors.receiveFrame<ErrorFrame>().code)
            socketErrors.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 1)))
            assertEquals(ErrorCode.INVALID_FRAME, socketErrors.receiveFrame<ErrorFrame>().code)
            socketErrors.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
            socketErrors.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
            assertEquals(ErrorCode.INVALID_FRAME, socketErrors.receiveFrame<ErrorFrame>().code)
            socketErrors.send("x".repeat(5_000))
            assertEquals(1009, socketErrors.receiveClose().statusCode)
        } finally {
            socketErrors.closeNormally()
        }

        val frameLimitNode =
            node(
                policies =
                    RateLimitPolicies(
                        socketFrames = RateLimitPolicy(burst = 2, refillPerSecond = 0.1),
                    ),
            )
        val frameLimitToken = register(frameLimitNode, FullStackClientKeys(FRAME_LIMIT_ID))
        val frameLimitedSocket = connect(frameLimitNode, frameLimitToken)
        try {
            frameLimitedSocket.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))
            frameLimitedSocket.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 0)))
            frameLimitedSocket.send(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 0)))
            val limitedFrame = frameLimitedSocket.receiveFrame<ErrorFrame>()
            assertEquals(ErrorCode.RATE_LIMITED, limitedFrame.code)
            assertNotNull(limitedFrame.retryAfterMs)
        } finally {
            frameLimitedSocket.closeNormally()
        }
    }

    @Test
    fun nettyStopMarksReadinessUnavailableAndClosesSocketsWithGoingAway() {
        val node = node()
        val recipient = FullStackClientKeys(RECIPIENT_ID)
        val token = register(node, recipient)
        val existingSocket = connect(node, token)
        existingSocket.send(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0)))

        val stopPreparing = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        node.application.monitor.subscribe(ApplicationStopPreparing) {
            stopPreparing.countDown()
            check(releaseStop.await(10, TimeUnit.SECONDS))
        }
        val stopping =
            CompletableFuture.runAsync {
                node.stop()
            }
        try {
            assertTrue(stopPreparing.await(10, TimeUnit.SECONDS))
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, call(node, "GET", "/ready").statusCode())
            assertEquals(HttpResponseStatus.OK, call(node, "GET", "/health").statusCode())
            assertEquals(1001, existingSocket.receiveClose().statusCode)

            val rejectedSocket = connect(node, token)
            assertEquals(1001, rejectedSocket.receiveClose().statusCode)
        } finally {
            releaseStop.countDown()
            stopping.get(10, TimeUnit.SECONDS)
            existingSocket.closeNormally()
        }
    }

    private fun node(
        redis: RedisConfiguration? = ServerFullStackContainers.redisConfiguration(),
        policies: RateLimitPolicies = RateLimitPolicies(),
        metricsToken: String? = null,
        certificates: FullStackCertificateFixtures = FullStackCertificateFixtures(),
        tokenKey: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    ): ServerFullStackNode =
        ServerFullStackNode(
            databaseConfiguration = ServerFullStackContainers.databaseConfiguration(),
            redisConfiguration = redis,
            rateLimitPolicies = policies,
            metricsToken = metricsToken,
            certificateFixtures = certificates,
            tokenSigningKey = tokenKey,
        ).also {
            activeNodes += it
            it.start()
        }

    private fun register(
        node: ServerFullStackNode,
        keys: FullStackClientKeys,
        version: Int = 2,
        ip: String = "198.51.100.101",
    ): String {
        val challenge = challenge(node, ip)
        val response =
            call(
                node,
                "POST",
                ProtocolRoutes.REGISTER,
                json(keys.registration(challenge.challenge, version), RegisterRequest.serializer()),
                headers = mapOf("X-Forwarded-For" to ip),
            )
        assertEquals(HttpResponseStatus.CREATED, response.statusCode(), response.body())
        val accessToken = decode(AccessTokenResponse.serializer(), response.body())
        assertEquals(2, accessToken.v)
        return accessToken.accessToken
    }

    private fun login(
        node: ServerFullStackNode,
        keys: FullStackClientKeys,
    ): String {
        val challenge = challenge(node)
        val request =
            AuthTokenRequest(
                mailboxId = keys.mailboxId,
                challenge = challenge.challenge,
                signature = keys.loginSignature(challenge.challenge),
            )
        val response =
            call(
                node,
                "POST",
                ProtocolRoutes.AUTH_TOKEN,
                json(request, AuthTokenRequest.serializer()),
            )
        assertEquals(HttpResponseStatus.OK, response.statusCode(), response.body())
        return decode(AccessTokenResponse.serializer(), response.body()).accessToken
    }

    private fun challenge(
        node: ServerFullStackNode,
        ip: String = "198.51.100.101",
    ): AuthChallengeResponse {
        val response =
            call(
                node,
                "POST",
                ProtocolRoutes.AUTH_CHALLENGE,
                headers = mapOf("X-Forwarded-For" to ip),
            )
        assertEquals(HttpResponseStatus.OK, response.statusCode())
        return decode(AuthChallengeResponse.serializer(), response.body())
    }

    private fun keyStatus(
        node: ServerFullStackNode,
        token: String,
    ): KeyStatusResponse =
        decode(
            KeyStatusResponse.serializer(),
            call(node, "GET", ProtocolRoutes.KEYS_STATUS, token = token).body(),
        )

    private fun bundle(
        node: ServerFullStackNode,
        recipient: FullStackClientKeys,
        deliveryKey: ByteArray = recipient.deliveryKey,
    ): HttpResponse<String> =
        call(
            node,
            "GET",
            ProtocolRoutes.PRE_KEYS.replace("{mailboxId}", recipient.mailboxId),
            headers = mapOf(ProtocolHeaders.DELIVERY_KEY to Base64Url.encode(deliveryKey)),
        )

    private fun sendMessage(
        node: ServerFullStackNode,
        recipient: FullStackClientKeys,
        envelope: ByteArray,
        deliveryKey: ByteArray = recipient.deliveryKey,
        version: Int = 2,
    ): HttpResponse<String> =
        call(
            node,
            "POST",
            ProtocolRoutes.SEND_MESSAGE.replace("{to}", recipient.mailboxId),
            json(
                SendMessageRequest(v = version, envelope = Base64Url.encode(envelope)),
                SendMessageRequest.serializer(),
            ),
            headers = mapOf(ProtocolHeaders.DELIVERY_KEY to Base64Url.encode(deliveryKey)),
        )

    private fun connect(
        node: ServerFullStackNode,
        token: String,
    ): SocketProbe {
        val probe = SocketProbe()
        HttpClient
            .newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .buildAsync(URI.create("${node.webSocketUrl}${ProtocolRoutes.SOCKET}"), probe)
            .get(10, TimeUnit.SECONDS)
        return probe
    }

    private fun awaitRedisConnected(
        node: ServerFullStackNode,
        timeoutSeconds: Long = 15,
    ) {
        awaitUntil(timeoutSeconds = timeoutSeconds) {
            node.crossNodeNotifier.gauges()["valence_redis_subscriber_connected"] == 1L
        }
    }

    private fun awaitRedisDisconnected(node: ServerFullStackNode) {
        awaitUntil(timeoutSeconds = 10) {
            node.crossNodeNotifier.gauges()["valence_redis_subscriber_connected"] == 0L
        }
    }

    private fun awaitUntil(
        timeoutSeconds: Long = 10,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition was not satisfied within $timeoutSeconds seconds" }
            Thread.sleep(20)
        }
    }

    private fun call(
        node: ServerFullStackNode,
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("${node.baseUrl}$path"))
                .timeout(Duration.ofSeconds(20))
        if (body != null) builder.header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return httpClient.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun <T> json(
        value: T,
        serializer: KSerializer<T>,
    ) = ProtocolJson.json.encodeToString(serializer, value)

    private fun <T> decode(
        serializer: KSerializer<T>,
        body: String,
    ) = ProtocolJson.json.decodeFromString(serializer, body)

    private inline fun <reified T> SocketProbe.receiveFrame(timeoutSeconds: Long = 10): T {
        val text = receiveText(timeoutSeconds)
        val frame = ProtocolJson.decodeServerFrame(text)
        check(frame is T) { "Unexpected server frame type: ${frame::class.simpleName}" }
        return frame
    }

    private companion object {
        const val SENDER_ID = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
        const val RECIPIENT_ID = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val UNKNOWN_ID = "mbx_eeeeeeeeeeeeeeeeeeeeeeeeee"
        const val FRAME_LIMIT_ID = "mbx_iiiiiiiiiiiiiiiiiiiiiiiiii"
        const val METRICS_TOKEN = "full-stack-metrics-token-0001"
        val httpClient: HttpClient = HttpClient.newHttpClient()

        @BeforeAll
        @JvmStatic
        fun startContainers() {
            ServerFullStackContainers.start()
        }

        @AfterAll
        @JvmStatic
        fun stopContainers() {
            ServerFullStackContainers.stop()
        }
    }
}

@Suppress("MagicNumber")
private object HttpResponseStatus {
    const val OK = 200
    const val CREATED = 201
    const val NO_CONTENT = 204
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val CONFLICT = 409
    const val PAYLOAD_TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val SERVICE_UNAVAILABLE = 503
}

@Suppress("MagicNumber")
private class SocketProbe : WebSocket.Listener {
    private val textFragments = StringBuilder()
    private val texts = LinkedBlockingQueue<String>()
    private val closes = LinkedBlockingQueue<SocketClose>()
    private val isClosed =
        java.util.concurrent.atomic
            .AtomicBoolean()

    @Synchronized
    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletableFuture<*>? {
        textFragments.append(data)
        if (last) {
            texts.offer(textFragments.toString())
            textFragments.setLength(0)
        }
        webSocket.request(1)
        return null
    }

    override fun onBinary(
        webSocket: WebSocket,
        data: java.nio.ByteBuffer,
        last: Boolean,
    ): CompletableFuture<*>? {
        webSocket.request(1)
        return null
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletableFuture<*>? {
        isClosed.set(true)
        closes.offer(SocketClose(statusCode, reason))
        return null
    }

    override fun onError(
        webSocket: WebSocket,
        error: Throwable,
    ) {
        isClosed.set(true)
        closes.offer(SocketClose(-1, error.javaClass.simpleName))
    }

    fun send(text: String) {
        webSocket.sendText(text, true).get(10, TimeUnit.SECONDS)
    }

    fun sendBinary(bytes: ByteArray) {
        webSocket.sendBinary(java.nio.ByteBuffer.wrap(bytes), true).get(10, TimeUnit.SECONDS)
    }

    fun receiveText(timeoutSeconds: Long): String =
        checkNotNull(texts.poll(timeoutSeconds, TimeUnit.SECONDS)) {
            "No WebSocket text frame arrived within $timeoutSeconds seconds"
        }

    fun receiveClose(): SocketClose =
        checkNotNull(closes.poll(10, TimeUnit.SECONDS)) { "WebSocket did not close within 10 seconds" }

    fun closeNormally() {
        if (isClosed.compareAndSet(false, true)) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    private lateinit var webSocket: WebSocket

    override fun onOpen(webSocket: WebSocket) {
        this.webSocket = webSocket
        webSocket.request(1)
    }
}

private data class SocketClose(
    val statusCode: Int,
    val reason: String,
)

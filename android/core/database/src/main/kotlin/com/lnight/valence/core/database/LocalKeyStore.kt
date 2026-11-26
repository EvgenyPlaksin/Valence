package com.lnight.valence.core.database

interface LocalKeyStore {
    suspend fun putIdentityKey(key: IdentityKeyStorage)

    suspend fun putSignedPreKey(preKey: SignedPreKeyStorage)

    suspend fun replaceLastResortKyberPreKey(
        preKey: KyberPreKeyStorage,
        retiredAtMillis: Long,
    )

    suspend fun putOneTimePreKeys(preKeys: List<OneTimePreKeyStorage>)

    suspend fun getOneTimePreKey(preKeyId: Int): OneTimePreKeyStorage?

    suspend fun getKyberPreKey(preKeyId: Int): KyberPreKeyStorage?

    suspend fun getDeliveryKey(): DeliveryKeyStorage?

    suspend fun putDeliveryKey(key: DeliveryKeyStorage)

    suspend fun getSenderCertificate(): SenderCertificateStorage?

    suspend fun putSenderCertificate(certificate: SenderCertificateStorage)

    suspend fun deleteOneTimePreKey(preKeyId: Int)

    suspend fun deleteOneTimeKyberPreKey(preKeyId: Int)

    suspend fun purgeRetiredKyberPreKeys(retiredBeforeMillis: Long): Int
}

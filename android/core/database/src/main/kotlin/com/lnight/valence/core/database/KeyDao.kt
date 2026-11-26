package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface KeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentityKey(entity: IdentityKeyEntity)

    @Query("SELECT * FROM identity_key WHERE id = 1")
    suspend fun getIdentityKey(): IdentityKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignedPreKey(entity: SignedPreKeyEntity)

    @Query("SELECT * FROM signed_prekey WHERE prekey_id = :preKeyId")
    suspend fun getSignedPreKey(preKeyId: Int): SignedPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKyberPreKey(entity: KyberPreKeyEntity)

    @Query("SELECT * FROM kyber_prekey WHERE prekey_id = :preKeyId")
    suspend fun getKyberPreKey(preKeyId: Int): KyberPreKeyEntity?

    @Query("SELECT * FROM kyber_prekey WHERE role = 0 AND retired_at IS NULL LIMIT 1")
    suspend fun getActiveLastResortKyberPreKey(): KyberPreKeyEntity?

    @Query("UPDATE kyber_prekey SET retired_at = :retiredAtMillis WHERE role = 0 AND retired_at IS NULL")
    suspend fun retireActiveLastResortKyberPreKeys(retiredAtMillis: Long)

    @Transaction
    suspend fun replaceLastResortKyberPreKey(
        newKey: KyberPreKeyEntity,
        retiredAtMillis: Long,
    ) {
        retireActiveLastResortKyberPreKeys(retiredAtMillis)
        insertKyberPreKey(newKey)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOneTimePreKeys(entities: List<OneTimePreKeyEntity>)

    @Query("SELECT * FROM one_time_prekey WHERE prekey_id = :preKeyId")
    suspend fun getOneTimePreKey(preKeyId: Int): OneTimePreKeyEntity?

    @Query("DELETE FROM one_time_prekey WHERE prekey_id = :preKeyId")
    suspend fun deleteOneTimePreKey(preKeyId: Int): Int

    @Query("DELETE FROM kyber_prekey WHERE prekey_id = :preKeyId AND role = 1")
    suspend fun deleteOneTimeKyberPreKey(preKeyId: Int): Int

    @Query("DELETE FROM kyber_prekey WHERE role = 0 AND retired_at IS NOT NULL AND retired_at < :retiredBeforeMillis")
    suspend fun purgeRetiredKyberPreKeys(retiredBeforeMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveryKey(entity: DeliveryKeyEntity)

    @Query("SELECT * FROM delivery_key WHERE id = 1")
    suspend fun getDeliveryKey(): DeliveryKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSenderCertificate(entity: SenderCertificateEntity)

    @Query("SELECT * FROM sender_certificate WHERE id = 1")
    suspend fun getSenderCertificate(): SenderCertificateEntity?
}

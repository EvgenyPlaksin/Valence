package com.lnight.valence.core.database

import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.MailboxId
import javax.inject.Inject

internal class ContactEntityMapper
    @Inject
    constructor() {
        fun toDomain(entity: ContactEntity): Contact =
            Contact(
                id = ContactId(entity.contactId),
                mailboxId = MailboxId(entity.mailboxId),
                displayName = entity.displayName,
                createdAtMillis = entity.createdAtMillis,
            )

        fun toEntity(
            domain: Contact,
            keys: ContactKeyStorage,
            trustState: Int = 0,
        ): ContactEntity =
            ContactEntity(
                contactId = domain.id.value,
                mailboxId = domain.mailboxId.value,
                displayName = domain.displayName,
                identityKey = keys.identityKey.copyBytes(),
                deliveryKey = keys.deliveryKey.copyBytes(),
                trustState = trustState,
                safetyNumberHash = keys.safetyNumberHash?.copyBytes(),
                createdAtMillis = domain.createdAtMillis,
            )
    }

package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.ServerIdMappingEntity
import com.example.data.local.entity.SyncCursorEntity
import java.util.UUID

/**
 * Strict protocol-v1 boundary. Local numeric identifiers are never serialized as server IDs.
 * A missing mapping is a hard conversion failure; callers must leave the mutation pending.
 */
class ProtocolV1MappingException(message: String) : IllegalStateException(message)

data class ProtocolV1Mutation(
    val mutationId: String,
    val entityType: String,
    val action: String,
    val idempotencyKey: String,
    val expectedVersion: Long?,
    val payload: Map<String, Any?>
)

interface ServerIdResolver {
    suspend fun requireServerId(entityType: String, localId: String): String
}

class ProtocolV1Adapter(
    private val resolver: ServerIdResolver
) {
    suspend fun mapReference(entityType: String, localId: String): String {
        require(entityType.isNotBlank()) { "entityType tsy maintsy feno." }
        require(localId.isNotBlank()) { "localId tsy maintsy feno." }
        return resolver.requireServerId(entityType, localId)
    }

    suspend fun convert(
        mutationId: String,
        entityType: String,
        action: String,
        idempotencyKey: String,
        expectedVersion: Long?,
        payload: Map<String, Any?>,
        references: Map<String, Pair<String, String>>
    ): ProtocolV1Mutation {
        require(mutationId.isNotBlank() && idempotencyKey.isNotBlank()) {
            "mutationId sy idempotencyKey tsy maintsy feno."
        }
        require(expectedVersion == null || expectedVersion >= 0L) {
            "expectedVersion tsy mety."
        }
        val converted = payload.toMutableMap()
        references.forEach { (field, reference) ->
            val (entityTypeForReference, localId) = reference
            converted[field] = mapReference(entityTypeForReference, localId)
        }
        return ProtocolV1Mutation(
            mutationId = mutationId,
            entityType = entityType,
            action = action,
            idempotencyKey = idempotencyKey,
            expectedVersion = expectedVersion,
            payload = converted
        )
    }
}

/**
 * Mapping changes and the pull cursor are committed as one Room transaction.
 * An invalid/partial pull must never call commitPull.
 */
class RoomSyncStateStore(private val database: FanevaDatabase) : ServerIdResolver {
    override suspend fun requireServerId(entityType: String, localId: String): String {
        val mapping = database.serverIdMappingDao().getMapping(entityType, localId)
            ?: throw ProtocolV1MappingException("Mapping server tsy hita: $entityType/$localId")
        if (mapping.serverId.isBlank() || mapping.serverVersion < 0L) {
            throw ProtocolV1MappingException("Mapping server tsy manan-kery: $entityType/$localId")
        }
        return mapping.serverId
    }

    suspend fun commitPull(
        mappings: List<ServerIdMappingEntity>,
        nextCursor: Long,
        syncedAt: Long = System.currentTimeMillis()
    ) {
        require(nextCursor >= 0L) { "Cursor négatif tsy azo ekena." }
        require(mappings.all {
            it.entityType.isNotBlank() && it.localId.isNotBlank() &&
                it.serverId.isNotBlank() && it.serverVersion >= 0L
        }) { "Pull mapping tsy feno na tsy manan-kery." }
        require(mappings.map { it.serverId }.distinct().size == mappings.size) {
            "Server ID miverimberina ao anatin'ny pull."
        }

        database.withTransaction {
            val cursorDao = database.syncCursorDao()
            val current = cursorDao.getCursor() ?: SyncCursorEntity()
            require(nextCursor >= current.lastCursor) { "Cursor mihemotra: pull nolavina." }

            mappings.forEach { mapping ->
                database.serverIdMappingDao().upsertMapping(
                    mapping.copy(updatedAt = syncedAt)
                )
            }
            cursorDao.saveCursor(current.copy(
                lastCursor = nextCursor,
                lastSyncTimestamp = syncedAt
            ))
        }
    }

    companion object {
        fun newStableId(): String = UUID.randomUUID().toString()
    }
}

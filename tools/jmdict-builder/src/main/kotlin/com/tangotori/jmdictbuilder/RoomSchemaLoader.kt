package com.tangotori.jmdictbuilder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads the JSON Room exports when `exportSchema = true` and extracts:
 *   - identityHash — what room_master_table must contain
 *   - setupQueries — the canonical CREATE TABLE / INSERT INTO statements
 *     that Room would issue on first open, in order.
 *
 * Re-using these guarantees the prepackaged DB matches whatever Room expects.
 */
object RoomSchemaLoader {
    private val parser = Json { ignoreUnknownKeys = true }

    fun load(schemaFile: File): RoomSchema {
        val root = parser.decodeFromString<SchemaRoot>(schemaFile.readText())
        val db = root.database
        val createStatements = mutableListOf<String>()
        for (entity in db.entities) {
            createStatements += entity.createSql.replace("\${TABLE_NAME}", entity.tableName)
            for (idx in entity.indices) {
                createStatements += idx.createSql.replace("\${TABLE_NAME}", entity.tableName)
            }
        }
        // Room itself always emits these two as the last setup statements.
        val masterTableDdl =
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY,identity_hash TEXT)"
        val masterTableInsert =
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                "VALUES(42, '${db.identityHash}')"
        return RoomSchema(
            identityHash = db.identityHash,
            setupQueries = createStatements + masterTableDdl + masterTableInsert,
        )
    }
}

data class RoomSchema(val identityHash: String, val setupQueries: List<String>)

@Serializable
private data class SchemaRoot(val database: SchemaDatabase)

@Serializable
private data class SchemaDatabase(
    val version: Int,
    val identityHash: String,
    val entities: List<SchemaEntity>,
)

@Serializable
private data class SchemaEntity(
    val tableName: String,
    val createSql: String,
    val indices: List<SchemaIndex> = emptyList(),
)

@Serializable
private data class SchemaIndex(
    val name: String,
    @SerialName("createSql") val createSql: String,
)

package io.georocket.index.postgresql

import io.georocket.constants.ConfigConstants
import io.georocket.index.AbstractIndex
import io.georocket.index.Index
import io.georocket.query.IndexQuery
import io.georocket.storage.ChunkMeta
import io.georocket.util.UniqueID
import io.georocket.util.getAwait
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class PostgreSQLIndex private constructor(vertx: Vertx, url: String,
    username: String, password: String) : Index, AbstractIndex() {
  companion object {
    /**
     * Table and column names
     */
    private const val CHUNK_META = "chunkMeta"
    private const val DOCUMENTS = "documents"
    private const val ID = "id"
    private const val DATA = "data"

    suspend fun create(vertx: Vertx, url: String? = null, username: String? = null,
        password: String? = null): PostgreSQLIndex {
      val config = vertx.orCreateContext.config()

      val actualUrl = url ?:
        config.getString(ConfigConstants.INDEX_POSTGRESQL_URL) ?:
        throw IllegalStateException("Missing configuration item `" +
            ConfigConstants.INDEX_POSTGRESQL_URL + "'")

      val actualUsername = username ?:
        config.getString(ConfigConstants.INDEX_POSTGRESQL_USERNAME) ?:
        throw IllegalStateException("Missing configuration item `" +
            ConfigConstants.INDEX_POSTGRESQL_USERNAME + "'")

      val actualPassword = password ?:
        config.getString(ConfigConstants.INDEX_POSTGRESQL_PASSWORD) ?:
        throw IllegalStateException("Missing configuration item `" +
            ConfigConstants.INDEX_POSTGRESQL_PASSWORD + "'")

      return vertx.executeBlocking<PostgreSQLIndex> { p ->
        p.complete(PostgreSQLIndex(vertx, actualUrl, actualUsername, actualPassword))
      }.await()
    }
  }

  private val pgClient = PostgreSQLClient(vertx, url, username, password)
  private val client = pgClient.client

  private suspend fun addOrGetChunkMeta(meta: ChunkMeta): String {
    return addedChunkMetaCache.getAwait(meta) {
      val statement = "WITH new_id AS (" +
            "INSERT INTO $CHUNK_META ($ID, $DATA) VALUES ($1, $2) " +
            "ON CONFLICT DO NOTHING RETURNING $ID" +
          ") SELECT COALESCE(" +
            "(SELECT $ID FROM new_id)," +
            "(SELECT $ID from $CHUNK_META WHERE $DATA=$2)" +
          ")"

      val params = Tuple.of(UniqueID.next(), meta.toJsonObject())

      val r = client.preparedQuery(statement).execute(params).await()
      r.first().getString(0)
    }
  }

  private suspend fun findChunkMeta(id: String): ChunkMeta {
    val r = loadedChunkMetaCache.getAwait(id) {
      val statement = "SELECT $DATA FROM $CHUNK_META WHERE $ID=$1"
      val r = client.preparedQuery(statement).execute(Tuple.of(id)).await()
      r?.first()?.getJsonObject(0)?.let { createChunkMeta(it) } ?:
        throw NoSuchElementException("Could not find chunk metadata with ID `$id' in index")
    }
    return r
  }

  override suspend fun close() {
    client.close()
  }

  override suspend fun addMany(docs: Collection<Index.AddManyParam>) {
    val params = docs.map { d ->
      val chunkMetaId = addOrGetChunkMeta(d.meta)
      val copy = d.doc.copy()
      copy.put(CHUNK_META, chunkMetaId)
      Tuple.of(d.path, copy)
    }
    val statement = "INSERT INTO $DOCUMENTS ($ID, $DATA) VALUES ($1, $2)"
    client.preparedQuery(statement).executeBatch(params).await()
  }

  private suspend fun <T> streamQuery(statement: String, params: List<Any>,
      readSize: Int = 50, rowToItem: suspend (Row) -> T): Flow<T> = flow {
    pgClient.withConnection { conn ->
      val preparedStatement = conn.prepare(statement).await()
      val transaction = conn.begin().await()
      try {
        val cursor = preparedStatement.cursor(Tuple.wrap(params))
        try {
          do {
            val rows = cursor.read(readSize).await()
            emitAll(rows.map { rowToItem(it) }.asFlow())
          } while (cursor.hasMore())
        } finally {
          cursor.close()
        }
      } finally {
        transaction.commit()
      }
    }
  }

  override suspend fun getDistinctMeta(query: IndexQuery): Flow<ChunkMeta> {
    val (where, params) = PostgreSQLQueryTranslator.translate(query)
    val statement = "SELECT DISTINCT $DATA->>'$CHUNK_META' FROM $DOCUMENTS WHERE $where"
    println(statement)
    println(params)
    return streamQuery(statement, params) { findChunkMeta(it.getString(0)) }
  }

  override suspend fun getMeta(query: IndexQuery): Flow<Pair<String, ChunkMeta>> {
    val (where, params) = PostgreSQLQueryTranslator.translate(query)
    val statement = "SELECT $ID, $DATA->>'$CHUNK_META' FROM $DOCUMENTS WHERE $where"
    println(statement)
    println(params)
    return streamQuery(statement, params) { it.getString(0) to findChunkMeta(it.getString(1)) }
  }

  override suspend fun getPaths(query: IndexQuery): Flow<String> {
    val (where, params) = PostgreSQLQueryTranslator.translate(query)
    val statement = "SELECT $ID FROM $DOCUMENTS WHERE $where"
    return streamQuery(statement, params) { it.getString(0) }
  }

  override suspend fun addTags(query: IndexQuery, tags: Collection<String>) {
    TODO("Not yet implemented")
  }

  override suspend fun removeTags(query: IndexQuery, tags: Collection<String>) {
    TODO("Not yet implemented")
  }

  override suspend fun setProperties(
    query: IndexQuery,
    properties: Map<String, Any>
  ) {
    TODO("Not yet implemented")
  }

  override suspend fun removeProperties(
    query: IndexQuery,
    properties: Collection<String>
  ) {
    TODO("Not yet implemented")
  }

  override suspend fun getPropertyValues(
    query: IndexQuery,
    propertyName: String
  ): Flow<Any?> {
    TODO("Not yet implemented")
  }

  override suspend fun getAttributeValues(
    query: IndexQuery,
    attributeName: String
  ): Flow<Any?> {
    TODO("Not yet implemented")
  }

  override suspend fun delete(query: IndexQuery) {
    val (where, params) = PostgreSQLQueryTranslator.translate(query)
    val statement = "DELETE FROM $DOCUMENTS WHERE $where"
    client.preparedQuery(statement).execute(Tuple.from(params)).await()
  }

  override suspend fun delete(paths: Collection<String>) {
    val statement = "DELETE FROM $DOCUMENTS WHERE $ID=ANY($1)"
    val preparedStatement = client.preparedQuery(statement)
    for (chunk in paths.chunked(1000)) {
      val deleteParams = Tuple.of(chunk.toTypedArray())
      preparedStatement.execute(deleteParams).await()
    }
  }

  override suspend fun getCollections(): Flow<String> {
    TODO("Not yet implemented")
  }

  override suspend fun addCollection(name: String) {
    TODO("Not yet implemented")
  }

  override suspend fun existsCollection(name: String): Boolean {
    TODO("Not yet implemented")
  }

  override suspend fun deleteCollection(name: String) {
    TODO("Not yet implemented")
  }
}
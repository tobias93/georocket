package io.georocket.storage.mongodb

import com.mongodb.ConnectionString
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoDatabase
import com.mongodb.reactivestreams.client.gridfs.GridFSBucket
import com.mongodb.reactivestreams.client.gridfs.GridFSBuckets
import io.georocket.constants.ConfigConstants.EMBEDDED_MONGODB_STORAGE_PATH
import io.georocket.constants.ConfigConstants.INDEX_MONGODB_CONNECTION_STRING
import io.georocket.constants.ConfigConstants.INDEX_MONGODB_EMBEDDED
import io.georocket.constants.ConfigConstants.STORAGE_MONGODB_CONNECTION_STRING
import io.georocket.constants.ConfigConstants.STORAGE_MONGODB_EMBEDDED
import io.georocket.index.mongodb.SharedMongoClient
import io.georocket.storage.ChunkMeta
import io.georocket.storage.IndexMeta
import io.georocket.storage.indexed.IndexedStore
import io.georocket.util.PathUtils
import io.georocket.util.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactive.awaitSingleOrNull
import org.bson.BsonDocument
import org.bson.BsonString
import reactor.core.publisher.Mono
import java.nio.ByteBuffer

/**
 * Stores chunks in MongoDB
 * @author Michel Kraemer
 */
class MongoDBStore private constructor(vertx: Vertx) : IndexedStore(vertx) {
  companion object {
    suspend fun create(vertx: Vertx, connectionString: String? = null,
        storagePath: String? = null): MongoDBStore {
      val r = MongoDBStore(vertx)
      r.start(vertx, connectionString, storagePath)
      return r
    }
  }

  private lateinit var client: MongoClient
  private lateinit var db: MongoDatabase
  private lateinit var gridfs: GridFSBucket

  private suspend fun start(vertx: Vertx, connectionString: String?,
      storagePath: String?) {
    val config = vertx.orCreateContext.config()

    val embedded = config.getBoolean(STORAGE_MONGODB_EMBEDDED) ?:
      config.getBoolean(INDEX_MONGODB_EMBEDDED) ?: false
    if (embedded) {
      val actualStoragePath = storagePath ?: config.getString(
        EMBEDDED_MONGODB_STORAGE_PATH) ?:
          throw IllegalStateException("Missing configuration item `" +
              EMBEDDED_MONGODB_STORAGE_PATH + "'")
      client = SharedMongoClient.createEmbedded(vertx, actualStoragePath)
      db = client.getDatabase(SharedMongoClient.DEFAULT_EMBEDDED_DATABASE)
    } else {
      val actualConnectionString = connectionString ?:
        config.getString(STORAGE_MONGODB_CONNECTION_STRING) ?:
        config.getString(INDEX_MONGODB_CONNECTION_STRING) ?:
        throw IllegalArgumentException("Missing configuration item `" +
            STORAGE_MONGODB_CONNECTION_STRING + "' or `" +
            INDEX_MONGODB_CONNECTION_STRING + "'")
      val cs = ConnectionString(actualConnectionString)
      client = SharedMongoClient.create(cs)
      db = client.getDatabase(cs.database)
    }

    gridfs = GridFSBuckets.create(db)
  }

  override fun close() {
    client.close()
  }

  override suspend fun getOne(path: String): Buffer {
    val publisher = gridfs.downloadToPublisher(path)
    val result = Buffer.buffer()
    publisher.asFlow().collect { buf ->
      result.appendBuffer(Buffer.buffer(buf.array()))
    }
    return result
  }

  override suspend fun add(chunk: Buffer, chunkMetadata: ChunkMeta,
      indexMetadata: IndexMeta, layer: String): String {
    val path = layer.ifEmpty { "/" }
    val filename = PathUtils.join(path, indexMetadata.correlationId + UniqueID.next())
    gridfs.uploadFromPublisher(filename, Mono.just(
        ByteBuffer.wrap(chunk.byteBuf.array()))).awaitSingle()
    return filename
  }

  override suspend fun doDeleteChunks(paths: Iterable<String>) {
    for (filename in paths) {
      gridfs.find(BsonDocument("filename", BsonString(filename))).asFlow().collect { file ->
        gridfs.delete(file.objectId).awaitSingleOrNull()
      }
    }
  }
}

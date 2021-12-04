package io.georocket.index

import io.georocket.constants.ConfigConstants.DEFAULT_INDEX_MAX_BULK_SIZE
import io.georocket.constants.ConfigConstants.INDEX_MAX_BULK_SIZE
import io.georocket.index.geojson.JsonTransformer
import io.georocket.index.mongodb.MongoDBIndex
import io.georocket.index.xml.XMLTransformer
import io.georocket.storage.ChunkMeta
import io.georocket.storage.IndexMeta
import io.georocket.util.FilteredServiceLoader
import io.georocket.util.JsonStreamEvent
import io.georocket.util.MimeTypeUtils
import io.georocket.util.StreamEvent
import io.georocket.util.XMLStreamEvent
import io.georocket.util.debounce
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext

class MainIndexer private constructor(override val coroutineContext: CoroutineContext,
    private val vertx: Vertx) : CoroutineScope {
  companion object {
    private val log = LoggerFactory.getLogger(Indexer::class.java)

    suspend fun create(coroutineContext: CoroutineContext, vertx: Vertx): MainIndexer {
      val result = MainIndexer(coroutineContext, vertx)
      result.init()
      return result
    }
  }

  private lateinit var index: Index
  private val queue = ArrayDeque<Queued>()
  private val onBulkAdd = debounce(vertx) { doAddQueue() }

  /**
   * The maximum number of chunks to index in one bulk
   */
  private var maxBulkSize = 0

  /**
   * A list of [MetaIndexerFactory] objects
   */
  private lateinit var metaIndexerFactories: List<MetaIndexerFactory>

  /**
   * A list of [IndexerFactory] objects
   */
  private lateinit var indexerFactories: List<IndexerFactory>

  private suspend fun init() {
    index = MongoDBIndex.create(vertx)

    val config = vertx.orCreateContext.config()
    maxBulkSize = config.getInteger(INDEX_MAX_BULK_SIZE, DEFAULT_INDEX_MAX_BULK_SIZE)

    // load and copy all indexer factories now and not lazily to avoid
    // concurrent modifications to the service loader's internal cache
    metaIndexerFactories = FilteredServiceLoader.load(MetaIndexerFactory::class.java).toList()
    indexerFactories = FilteredServiceLoader.load(IndexerFactory::class.java).toList()
  }

  suspend fun close() {
    index.close()
  }

  suspend fun add(chunk: Buffer, chunkMeta: ChunkMeta, indexMeta: IndexMeta, path: String) {
    queue.add(Queued(chunk, chunkMeta, indexMeta, path))
    if (queue.size >= maxBulkSize) {
      doAddQueue()
    } else {
      onBulkAdd()
    }
  }

  suspend fun flushAdd() {
    doAddQueue()
  }

  private suspend fun doAddQueue() {
    val toAdd = mutableListOf<Queued>()
    while (!queue.isEmpty()) {
      toAdd.add(queue.removeFirst())
    }

    val documents = toAdd.map { queued ->
      val doc = queuedChunkToDocument(queued)
      queued.path to JsonObject(doc)
    }

    if (documents.isNotEmpty()) {
      val startTimeStamp = System.currentTimeMillis()

      index.addMany(documents)

      // log error if one of the inserts failed
      val stopTimeStamp = System.currentTimeMillis()
      log.info("Finished indexing ${documents.size} chunks in " +
          (stopTimeStamp - startTimeStamp) + " ms")
    }
  }

  private suspend fun queuedChunkToDocument(queued: Queued): Map<String, Any> {
    // call meta indexers
    val metaResults = mutableMapOf<String, Any>()
    for (metaIndexerFactory in metaIndexerFactories) {
      val metaIndexer = metaIndexerFactory.createIndexer()
      val metaResult = metaIndexer.onChunk(queued.path, queued.chunkMeta, queued.indexMeta)
      metaResults.putAll(metaResult)
    }

    // index chunks depending on the mime type
    val mimeType = queued.chunkMeta.mimeType
    val doc = if (MimeTypeUtils.belongsTo(mimeType, "application", "xml") ||
      MimeTypeUtils.belongsTo(mimeType, "text", "xml")
    ) {
      chunkToDocument(queued.chunk, queued.indexMeta.fallbackCRSString,
        XMLStreamEvent::class.java, XMLTransformer()
      )
    } else if (MimeTypeUtils.belongsTo(mimeType, "application", "json")) {
      chunkToDocument(queued.chunk, queued.indexMeta.fallbackCRSString,
        JsonStreamEvent::class.java, JsonTransformer()
      )
    } else {
      throw IllegalArgumentException("Unexpected mime type '${mimeType}' " +
          "while trying to index chunk `${queued.path}'")
    }

    // add results from meta indexers to converted document
    return doc + metaResults
  }

  private suspend fun <T : StreamEvent> chunkToDocument(chunk: Buffer,
    fallbackCRSString: String?, type: Class<T>, transformer: Transformer<T>): Map<String, Any> {
    // initialize indexers
    val indexers = indexerFactories.mapNotNull { factory ->
      factory.createIndexer(type)?.also { i ->
        if (fallbackCRSString != null && i is CRSAware) {
          i.setFallbackCRSString(fallbackCRSString)
        }
      }
    }

    // perform indexing
    transformer.transform(chunk).collect { e ->
      indexers.forEach { it.onEvent(e) }
    }

    // create the document
    val doc = mutableMapOf<String, Any>()
    indexers.forEach { indexer -> doc.putAll(indexer.makeResult()) }
    return doc
  }

  private data class Queued(val chunk: Buffer, val chunkMeta: ChunkMeta,
    val indexMeta: IndexMeta, val path: String)
}
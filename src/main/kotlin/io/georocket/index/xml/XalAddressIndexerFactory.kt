package io.georocket.index.xml

import io.georocket.index.Indexer
import io.georocket.index.IndexerFactory
import io.georocket.query.Compare
import io.georocket.query.IndexQuery
import io.georocket.query.Or
import io.georocket.query.QueryCompiler.MatchPriority
import io.georocket.query.QueryPart
import io.georocket.query.QueryPart.ComparisonOperator.EQ
import io.georocket.query.StringQueryPart
import io.georocket.util.StreamEvent
import io.georocket.util.XMLStreamEvent

/**
 * Creates instances of [XalAddressIndexer]
 * @author Michel Kraemer
 */
class XalAddressIndexerFactory : IndexerFactory {
  override fun <T : StreamEvent> createIndexer(eventType: Class<T>): Indexer<T>? {
    if (eventType.isAssignableFrom(XMLStreamEvent::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return XalAddressIndexer() as Indexer<T>
    }
    return null
  }

  override fun getQueryPriority(queryPart: QueryPart): MatchPriority {
    return when (queryPart) {
      is StringQueryPart -> MatchPriority.SHOULD
      else -> MatchPriority.NONE
    }
  }

  override fun compileQuery(queryPart: QueryPart): IndexQuery? {
    return when (queryPart) {
      is StringQueryPart -> {
        val key = queryPart.key
        if (key == null) {
          Or(XalAddressIndexer.Companion.Keys.values().map { k ->
            Compare("address.${k.key}", queryPart.value, EQ)
          })
        } else if (XalAddressIndexer.Companion.Keys.values().map { it.key }.contains(key)) {
          Compare("address.$key", queryPart.value, EQ)
        } else {
          null
        }
      }

      else -> null
    }
  }
}

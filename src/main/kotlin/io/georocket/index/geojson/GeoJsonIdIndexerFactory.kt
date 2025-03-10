package io.georocket.index.geojson

import io.georocket.index.Indexer
import io.georocket.index.IndexerFactory
import io.georocket.query.Contains
import io.georocket.query.IndexQuery
import io.georocket.query.QueryCompiler
import io.georocket.query.QueryPart
import io.georocket.util.JsonStreamEvent
import io.georocket.util.StreamEvent

/**
 * @author Tobias Dorra
 */
class GeoJsonIdIndexerFactory: IndexerFactory {
  companion object {
    const val GEOJSON_FEATURE_ID = "geoJsonFeatureId"
    const val GEOJSON_FEATURE_IDS = "geoJsonFeatureIds"
  }

  override fun <T : StreamEvent> createIndexer(eventType: Class<T>): Indexer<T>? {
    if (eventType.isAssignableFrom(JsonStreamEvent::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return GeoJsonIdIndexer() as Indexer<T>
    }
    return null
  }

  private fun getIdFromQuery(queryPart: QueryPart): String?  {
    return if (queryPart.key == null) {
      queryPart.value.toString()
    } else if (queryPart.comparisonOperator == QueryPart.ComparisonOperator.EQ && queryPart.key == GEOJSON_FEATURE_ID) {
      queryPart.value.toString()
    } else {
      null
    }
  }

  override fun getQueryPriority(queryPart: QueryPart): QueryCompiler.MatchPriority {
    val queryFor = getIdFromQuery(queryPart)
    return if (queryFor != null) {
      QueryCompiler.MatchPriority.SHOULD
    } else {
      QueryCompiler.MatchPriority.NONE
    }
  }

  override fun compileQuery(queryPart: QueryPart): IndexQuery? {
    val queryFor = getIdFromQuery(queryPart)
    return if (queryFor != null) {
      Contains(GEOJSON_FEATURE_IDS, queryPart.value)
    } else {
      null
    }
  }
}

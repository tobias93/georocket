package io.georocket.http

import io.georocket.coVerify
import io.georocket.constants.ConfigConstants
import io.georocket.index.Index
import io.georocket.index.IndexFactory
import io.georocket.storage.ChunkMeta
import io.georocket.storage.GeoJsonChunkMeta
import io.georocket.storage.Store
import io.georocket.storage.StoreFactory
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path

/**
 * Test [StoreEndpoint]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class StoreEndpointTest {
  private var port: Int = 0
  private lateinit var store: Store
  private lateinit var index: Index

  private class EndpointVerticle(private val port: Int) : CoroutineVerticle() {
    override suspend fun start() {
      val router = Router.router(vertx)
      val se = StoreEndpoint(coroutineContext, vertx)
      router.mountSubRouter("/store", se.createRouter())
      val server = vertx.createHttpServer(httpServerOptionsOf())
      server.requestHandler(router).listen(port, "localhost").await()
    }
  }

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    port = ServerSocket(0).use { it.localPort }

    store = mockk()
    mockkObject(StoreFactory)
    coEvery { StoreFactory.createStore(any()) } returns store

    index = mockk()
    mockkObject(IndexFactory)
    coEvery { IndexFactory.createIndex(any()) } returns index

    val config = json {
      obj(
        ConfigConstants.STORAGE_FILE_PATH to tempDir.toString()
      )
    }
    val options = deploymentOptionsOf(config = config)
    vertx.deployVerticle(EndpointVerticle(port), options, ctx.succeedingThenComplete())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Get a single chunk
   */
  @Test
  fun getSingleChunk(vertx: Vertx, ctx: VertxTestContext) {
    val strChunk1 = """{"type":"Polygon"}"""
    val expected = """{"type":"GeometryCollection","geometries":[$strChunk1]}"""
    val chunk1 = Buffer.buffer(strChunk1)
    val chunk1Path = "/foobar"

    val cm = GeoJsonChunkMeta("Polygon", "geometries")

    coEvery { index.getDistinctMeta(any()) } returns listOf(cm).asFlow()
    coEvery { index.getMeta(any()) } returns listOf(chunk1Path to cm).asFlow()
    coEvery { store.getOne(chunk1Path) } returns chunk1
    coEvery { store.getManyParallelBatched<Any>(any()) } answers {
      arg<Flow<Pair<String, Any>>>(0)
        .map { (path, meta) -> store.getOne(path) to meta }
    }

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response =
          client.get(port, "localhost", "/store").`as`(BodyCodec.string()).expect(ResponsePredicate.SC_OK).send()
            .await()
        assertThat(response.body()).isEqualTo(expected)
      }
      ctx.completeNow()
    }
  }
}

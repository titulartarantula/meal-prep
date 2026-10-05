package dev.mealprep.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mealprep.app.data.ApiProvider
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.cache.CacheDb
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** A fake server (route table, unknown routes → 404) + in-memory cache + a real Repository. Robolectric tests only. */
class TestEnv : Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val gates = CopyOnWriteArrayList<java.util.concurrent.CountDownLatch>()
    @Volatile var offline = false
    @Volatile var token = "tok"
    @Volatile var now: Instant = Instant.parse("2026-10-07T15:00:00Z")
    val db: CacheDb = Room.inMemoryDatabaseBuilder(context, CacheDb::class.java).allowMainThreadQueries().build()
    private val client = Http.client({ token }, readTimeoutSec = 2, longReadTimeoutSec = 2)
    val apis = ApiProvider {
        when {
            token.isBlank() -> null
            offline -> Http.api("http://127.0.0.1:1/", client)   // nothing listens on port 1 → Unreachable
            else -> Http.api(server.url("/").toString(), client)
        }
    }
    val repo = Repository(apis, db.cache(), now = { now })

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.url.encodedPath}"
                return routes[key]?.invoke()
                    ?: MockResponse.Builder().code(404).body("""{"detail":"no route $key"}""").build()
            }
        }
        server.start()
    }

    fun on(method: String, path: String, code: Int = 200, body: String = "") {
        routes["$method $path"] = { response(code, body) }
    }

    /** Successive calls get successive bodies; the last one repeats. */
    fun onSequence(method: String, path: String, bodies: List<String>) {
        val n = AtomicInteger()
        routes["$method $path"] = { response(200, bodies[minOf(n.getAndIncrement(), bodies.size - 1)]) }
    }

    fun onSlow(method: String, path: String, seconds: Long) {
        routes["$method $path"] = {
            MockResponse.Builder().code(200).body("{}").headersDelay(seconds, java.util.concurrent.TimeUnit.SECONDS).build()
        }
    }

    /** Like [on], but the response only starts after [millis] (a slow server). */
    fun onDelayed(method: String, path: String, millis: Long, body: String, code: Int = 200) {
        routes["$method $path"] = {
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
                .headersDelay(millis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }
    }

    /** The response is held until [gate] opens (the dispatcher thread blocks). */
    fun onGated(method: String, path: String, gate: java.util.concurrent.CountDownLatch, body: String) {
        gates += gate
        routes["$method $path"] = { gate.await(); response(200, body) }
    }

    fun count(method: String, path: String): Int = requests.count { it.method == method && it.url.encodedPath == path }

    fun bodies(method: String, path: String): List<String> =
        requests.filter { it.method == method && it.url.encodedPath == path }.map { it.body?.utf8() ?: "" }

    /** Wait (real time) for a request to arrive; returns the latest body. */
    suspend fun awaitBody(method: String, path: String): String = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (bodies(method, path).isEmpty()) kotlinx.coroutines.delay(10) }
        bodies(method, path).last()
    }

    private fun response(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    override fun close() { gates.forEach { it.countDown() }; server.close(); db.close() }
}

/** Wait (real time, ≤ 5 s) until the state matches — ViewModels call the fake server on real OkHttp threads. */
suspend fun <T> StateFlow<T>.await(pred: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeout(5_000) { first(pred) } }

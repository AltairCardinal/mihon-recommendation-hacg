package mihon.recommendation.hacg

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mihon.recommendation.api.ArticleMetadataUpgradeProvider
import mihon.recommendation.api.CommentOrder
import mihon.recommendation.api.CommentProvider
import mihon.recommendation.api.DocumentProvider
import mihon.recommendation.api.ImageRequestProvider
import mihon.recommendation.api.ListPurpose
import mihon.recommendation.api.RecommendationArticleRef
import mihon.recommendation.api.RecommendationBlock
import mihon.recommendation.api.RecommendationHostContext
import mihon.recommendation.api.RecommendationHttpClient
import mihon.recommendation.api.RecommendationHttpRequest
import mihon.recommendation.api.RecommendationHttpResponse
import mihon.recommendation.api.RecommendationInstanceConfig
import mihon.recommendation.api.RecommendationPagination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

class HacgHttpTest {
    @Test
    fun `factory list requests real HTTP through context and history returns just one page`() =
        runBlocking<Unit> {
            val requests = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += path
                val rss = path.endsWith("feed")
                val text =
                    if (rss) {
                        """<rss><channel><item><link>https://www.hacg.icu/wp/1.html</link><title>One</title></item></channel></rss>"""
                    } else {
                        """<article id="post-2" class="category-comic"><h2 class="entry-title"><a>Two</a></h2></article>""" +
                            """<a class="nextpostslink" href="/wp/comic.html/page/2">Next</a>"""
                    }
                exchange.responseHeaders.add("Content-Type", if (rss) "application/rss+xml" else "text/html")
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val client = HttpClient.newHttpClient()
                val provider =
                    provider { request ->
                        val local = URI("http://127.0.0.1:${server.address.port}${URI(request.url).rawPath}")
                        val response =
                            withContext(
                                Dispatchers.IO,
                            ) { client.send(HttpRequest.newBuilder(local).GET().build(), HttpResponse.BodyHandlers.ofByteArray()) }
                        RecommendationHttpResponse(response.statusCode(), request.url, response.headers().map(), response.body())
                    }
                val channel = provider.channels().single()
                assertEquals("comic", channel.id)
                assertEquals(setOf(CommentOrder.POPULAR), channel.commentOrders)
                val discovery = provider.list(channel.id, ListPurpose.DISCOVER, null)
                assertEquals(
                    "1",
                    discovery.items
                        .single()
                        .article.remoteId,
                )
                assertEquals(RecommendationPagination.Unknown, discovery.pagination)
                val history = provider.list(channel.id, ListPurpose.HISTORY, null)
                assertEquals(
                    "2",
                    history.items
                        .single()
                        .article.remoteId,
                )
                assertTrue(history.pagination is RecommendationPagination.More)
                assertEquals(listOf("/wp/comic.html/feed", "/wp/comic.html"), requests)
                provider.list(channel.id, ListPurpose.HISTORY, (history.pagination as RecommendationPagination.More).cursor)
                assertEquals("/wp/comic.html/page/2", requests.last())
                assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.list("wrong", ListPurpose.HISTORY, null) } }
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { provider.list(channel.id, ListPurpose.HISTORY, "https://foreign.invalid/") }
                }
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `popular comments retain proven sort reply identity and cursor order`() =
        runBlocking<Unit> {
            for ((order, sorting) in listOf(CommentOrder.POPULAR to "by_vote")) {
                val captured = mutableListOf<RecommendationHttpRequest>()
                val source =
                    provider { request ->
                        captured += request
                        if (request.method == "GET") {
                            response(request, "text/html", commentsHtml())
                        } else {
                            response(
                                request,
                                "application/json",
                                """{"success":true,"data":{"comment_list":${Json.encodeToString(
                                    comment("11_10"),
                                )},"is_show_load_more":false}}""",
                            )
                        }
                    } as CommentProvider
                val first = source.comments(article(), order, null)
                val page =
                    if (first.pagination is RecommendationPagination.More) {
                        source.comments(
                            article(),
                            order,
                            (first.pagination as RecommendationPagination.More).cursor,
                        )
                    } else {
                        first
                    }
                assertEquals("10", page.items.single().parentId)
                assertEquals(RecommendationPagination.End, page.pagination)
                val post = captured.last { it.method == "POST" }
                assertTrue(post.body!!.decodeToString().contains("sorting=$sorting"))
                assertTrue(post.body!!.decodeToString().contains("action=wpdLoadMoreComments"))
                assertEquals("https://www.hacg.icu/wp/1.html", post.headers["Referer"])
            }
        }

    @Test
    fun `unverified comment orders are unsupported before HTTP even if a page advertises invented values`() =
        runBlocking<Unit> {
            var requests = 0
            val source =
                provider { request ->
                    requests++
                    response(request, "text/html", commentsHtml())
                } as CommentProvider
            for (order in listOf(CommentOrder.NEWEST, CommentOrder.OLDEST)) {
                assertThrows(UnsupportedOperationException::class.java) { runBlocking { source.comments(article(), order, null) } }
            }
            assertEquals(0, requests)
        }

    @Test
    fun `unsafe endpoint malformed content rejection and cancellation never bypass host HTTP`() =
        runBlocking<Unit> {
            for ((mime, body) in listOf(
                "text/html" to "challenge",
                "application/rss+xml" to "<!DOCTYPE rss SYSTEM 'http://invalid/evil'><rss/>",
            )) {
                val source = provider { response(it, mime, body) }
                assertThrows(Exception::class.java) { runBlocking { source.list("comic", ListPurpose.DISCOVER, null) } }
            }
            val comments =
                provider {
                    response(
                        it,
                        "text/html",
                        commentsHtml().replace("/wp/ajax", "https://foreign.invalid/ajax"),
                    )
                } as CommentProvider
            assertThrows(IllegalArgumentException::class.java) { runBlocking { comments.comments(article(), CommentOrder.POPULAR, null) } }
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val slow =
                provider {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val job = launch { slow.list("comic", ListPurpose.DISCOVER, null) }
            started.await()
            job.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        }

    @Test
    fun `image request carries article referer and rejects credentials or outside declared hosts`() {
        val source = provider { error("imageRequest must not execute HTTP") } as ImageRequestProvider
        val image = source.imageRequest(article(), "https://www.hacg.icu/image.png")
        assertEquals("https://www.hacg.icu/wp/1.html", image.headers["Referer"])
        assertThrows(IllegalArgumentException::class.java) { source.imageRequest(article(), "https://user:pass@www.hacg.icu/image.png") }
        assertThrows(IllegalArgumentException::class.java) { source.imageRequest(article(), "https://unverified.invalid/image.png") }
    }

    @Test
    fun `native body preserves inline image link spoiler and drops active content`() =
        runBlocking<Unit> {
            val source =
                provider { request ->
                    response(
                        request,
                        "text/html",
                        """
                        <article id="post-1"><h1 class="entry-title">[Writer] Book</h1><div class="entry-content">
                        <p>Before <b>bold</b><a href="https://www.hacg.icu/reference">Reference</a>
                        <img src="/cover.jpg" alt="cover"/> After</p><div class="spoiler">Hidden text</div>
                        <script>evil()</script><form>secret</form><iframe src="https://unsafe.invalid"></iframe>
                        </div></article>
                        """.trimIndent(),
                    )
                }
            val document = (source as DocumentProvider).document(article())
            assertTrue(document.blocks.any { it is RecommendationBlock.Image && it.url == "https://www.hacg.icu/cover.jpg" })
            assertTrue(document.blocks.any { it is RecommendationBlock.Link && it.text == "Reference" })
            assertTrue(document.blocks.any { it is RecommendationBlock.Spoiler })
            assertFalse(document.blocks.toString().contains("evil()"))
            assertFalse(document.blocks.toString().contains("secret"))
            assertEquals(
                "Book",
                document.metadata.works
                    .single()
                    .title,
            )
            val request = (source as ImageRequestProvider).imageRequest(article(), "/cover.jpg")
            assertEquals("https://www.hacg.icu/wp/1.html", request.headers["Referer"])
            assertEquals("https://www.hacg.icu/cover.jpg", request.url)
        }

    @Test
    fun `business failure renews public anonymous session only once and foreign order cursor fails`() =
        runBlocking<Unit> {
            val calls = mutableListOf<RecommendationHttpRequest>()
            val source =
                provider { request ->
                    calls += request
                    when (calls.size) {
                        1 -> response(request, "text/html", commentsHtml())
                        2 -> response(request, "application/json", """{"success":false,"data":"nonce expired"}""")
                        3 -> response(request, "application/rss+xml", "<rss><channel/></rss>")
                        else -> response(request, "application/json", """{"success":false,"data":"denied"}""")
                    }
                } as CommentProvider
            val first = source.comments(article(), CommentOrder.POPULAR, null)
            val cursor = (first.pagination as RecommendationPagination.More).cursor
            assertThrows(Exception::class.java) { runBlocking { source.comments(article(), CommentOrder.NEWEST, cursor) } }
            assertEquals(1, calls.size, "wrong order must fail before HTTP")
            assertThrows(IllegalStateException::class.java) { runBlocking { source.comments(article(), CommentOrder.POPULAR, cursor) } }
            assertEquals(4, calls.size)
            assertEquals("https://www.hacg.icu/wp/comic.html/feed", calls[2].url)
            assertEquals(calls[1].body!!.decodeToString(), calls[3].body!!.decodeToString())
        }

    @Test
    fun `HTTP status and over limit response fail explicitly without fallback requests`() =
        runBlocking<Unit> {
            for (status in listOf(429, 200)) {
                var calls = 0
                val source =
                    provider {
                        calls++
                        RecommendationHttpResponse(
                            status,
                            it.url,
                            mapOf("Content-Type" to listOf("application/rss+xml")),
                            if (status ==
                                200
                            ) {
                                ByteArray(8 * 1024 * 1024 + 1)
                            } else {
                                byteArrayOf()
                            },
                        )
                    }
                assertThrows(IllegalStateException::class.java) { runBlocking { source.list("comic", ListPurpose.DISCOVER, null) } }
                assertEquals(1, calls)
            }
        }

    @Test
    fun `posting publisher survives RSS to document separately from writer and offline upgrades retain incoming publisher`() =
        runBlocking<Unit> {
            var calls = 0
            val source =
                provider { request ->
                    calls++
                    if (request.url.endsWith("/feed")) {
                        response(
                            request,
                            "application/rss+xml",
                            """
                            <rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><item>
                            <link>https://www.hacg.icu/wp/1.html</link><title>[Writer] Book</title>
                            <dc:creator>Uploader</dc:creator></item></channel></rss>
                            """.trimIndent(),
                        )
                    } else {
                        response(
                            request,
                            "text/html",
                            """
                            <article id="post-1"><h1 class="entry-title">[Writer] Book</h1>
                            <div class="entry-content"><p>作者:Writer</p></div></article>
                            """.trimIndent(),
                        )
                    }
                }
            val summary = source.list("comic", ListPurpose.DISCOVER, null).items.single()
            val document = (source as DocumentProvider).document(summary.article)
            assertEquals("Uploader", document.metadata.publisher)
            assertEquals(listOf("Writer"), document.metadata.credits.map { it.name })
            val cached = document.copy(metadata = document.metadata.copy(publisher = "Cached Uploader"))
            val upgraded = (source as ArticleMetadataUpgradeProvider).upgradeMetadata(summary, cached)
            assertEquals("Cached Uploader", upgraded.publisher)
            assertEquals(listOf("Writer"), upgraded.credits.map { it.name })
            assertEquals(2, calls)
        }

    private fun provider(block: suspend (RecommendationHttpRequest) -> RecommendationHttpResponse) =
        HacgFactory().create(
            RecommendationInstanceConfig("http-test"),
            object : RecommendationHostContext {
                override val http = RecommendationHttpClient(block)
            },
        )

    private fun article() = RecommendationArticleRef("1", "https://www.hacg.icu/wp/1.html")

    private fun response(
        request: RecommendationHttpRequest,
        mime: String,
        body: String,
    ) = RecommendationHttpResponse(
        200,
        request.url,
        mapOf("Content-Type" to listOf(mime)),
        body.toByteArray(),
    )

    private fun comment(id: String) =
        """
        <div class="wpd-comment" id="wpd-comm-$id"><div class="wpd-comment-author">Reader</div>
        <div class="wpd-comment-text">Text</div></div>
        """.trimIndent()

    private fun commentsHtml() =
        """
        <div id="wpdcom"><div data-comments-count="2"></div>${comment("10_0")}</div>
        <button class="wpd-load-more-submit" data-lastparentid="10"></button>
        <button class="wpdiscuz-sort-button wpdiscuz-sort-button-active" data-sorting="by_vote"></button>
        <button class="wpdiscuz-sort-button" data-sorting="by_newest"></button>
        <button class="wpdiscuz-sort-button" data-sorting="by_oldest"></button>
        <script>var wpdiscuzAjaxObj = {"wpdAjaxUrl":"/wp/ajax"};</script>
        """.trimIndent()
}

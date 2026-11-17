package mihon.recommendation.hacg.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.recommendation.api.RecommendationHttpClient
import mihon.recommendation.api.RecommendationHttpRequest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.coroutineContext
import org.w3c.dom.Element as XmlElement

internal class HacgParser(
    private val http: RecommendationHttpClient,
    private val instanceId: String,
    private val siteUrl: String = "https://www.hacg.icu/wp/",
) {
    private val base = URI(siteUrl)
    private val requests = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(): List<RecommendationArticle> =
        requests.withLock {
            parseFeed(request(resolve("comic.html/feed"), expected = "xml"))
        }

    suspend fun historyPage(cursor: String?): Pair<List<RecommendationArticle>, String?> =
        requests.withLock {
            val url = cursor?.let(::historyUrl) ?: resolve("comic.html")
            val html = Jsoup.parse(request(url, expected = "html").decodeToString(throwOnInvalidSequence = true), url)
            val elements = html.select("article[id^=post-]:not(.format-link)")
            check(elements.size <= 500) { "推荐历史列表过大" }
            check(elements.isNotEmpty() || html.select(".no-results, .not-found").isNotEmpty()) { "推荐历史列表结构已变化" }
            val items =
                elements
                    .filter { it.hasClass("category-comic") }
                    .mapNotNull { element ->
                        val link = element.selectFirst(".entry-title a") ?: return@mapNotNull null
                        val id = element.id().removePrefix("post-")
                        if (id.isEmpty() || !id.all(Char::isDigit)) return@mapNotNull null
                        RecommendationArticle(
                            id,
                            articleUrl(id),
                            link.text(),
                            summary =
                                element
                                    .selectFirst(".entry-content")
                                    ?.text()
                                    .orEmpty()
                                    .take(800),
                            published = element.selectFirst("time[datetime], .entry-date[datetime]")?.attr("datetime").orEmpty(),
                        )
                    }.distinctBy { it.id }
            val next =
                html
                    .selectFirst(".wp-pagenavi a.nextpostslink, a.nextpostslink")
                    ?.attr("href")
                    ?.takeIf(String::isNotBlank)
                    ?.let { historyUrl(URI(url).resolve(it).toString()) }
            items to next
        }

    private fun historyUrl(value: String): String {
        requireSiteUrl(value)
        val uri = URI(value)
        require(uri.query == null && uri.fragment == null && uri.path.matches(Regex("/wp/comic\\.html(?:/page/[0-9]+/?)?"))) { "历史游标已失效" }
        return uri.toString()
    }

    suspend fun article(summary: RecommendationArticle): RecommendationDocument =
        withContext(Dispatchers.IO) {
            requests.withLock {
                val url = articleUrl(summary.id)
                val html = Jsoup.parse(request(url, expected = "html").decodeToString(), url)
                val content =
                    html.selectFirst("article#post-${numericId(summary.id)} .entry-content")
                        ?: error("推荐正文结构已变化，请打开原文")
                val blocks = contentBlocks(content, url)
                check(blocks.isNotEmpty()) { "未取得推荐正文，请打开原文" }
                HacgRecommendationMetadataExtractor.extract(
                    summary.title.ifBlank {
                        html.selectFirst(".entry-title")?.text().orEmpty()
                    },
                    RecommendationDocument(blocks),
                )
            }
        }

    fun upgradeMetadata(
        summary: RecommendationArticle,
        document: RecommendationDocument,
    ): RecommendationDocument = HacgRecommendationMetadataExtractor.extract(summary.title, document)

    suspend fun comments(
        summary: RecommendationArticle,
        cursor: String?,
        sorting: String,
    ): RecommendationCommentPage =
        withContext(
            Dispatchers.IO,
        ) {
            requests.withLock {
                var effectiveCursor = cursor
                val url = articleUrl(summary.id)
                if (effectiveCursor == null) {
                    val html = Jsoup.parse(request(url, expected = "html").decodeToString(), url)
                    val declared = html.select("[data-sorting]").map { it.attr("data-sorting") }.toSet()
                    val active = html.selectFirst(".wpdiscuz-sort-button-active")?.attr("data-sorting")?.ifBlank { null } ?: "by_vote"
                    require(sorting == active || sorting in declared) { "原站未提供所选评论排序" }
                    val alternate = sorting != active
                    val items = parseComments(html)
                    check(items.isNotEmpty() || html.selectFirst("#wpdcom, [data-comments-count]") != null) { "原站评论当前不可用" }
                    val total = html.selectFirst("[data-comments-count]")?.attr("data-comments-count")?.toIntOrNull()
                    val button = html.selectFirst(".wpd-load-more-submit")
                    val more =
                        button != null ||
                            html.selectFirst("#wpdiscuzHasMoreComments")?.attr("data-is_show_load_more") == "1"
                    val next =
                        if (more || alternate) {
                            val configuration = scriptObject(html, "wpdiscuzAjaxObj")
                            val endpoint =
                                configuration.string("customAjaxUrl") ?: configuration.string("wpdAjaxUrl")
                                    ?: error("原站评论分页地址不可用")
                            json.encodeToString(
                                CommentCursor(
                                    articleId = summary.id,
                                    instanceId = instanceId,
                                    endpoint = requireSiteUrl(resolve(endpoint)),
                                    lastParentId = button?.attr("data-lastparentid").orEmpty(),
                                    sorting = sorting,
                                    offset = if (alternate) 0 else 1,
                                    reportedTotal = total,
                                ),
                            )
                        } else {
                            null
                        }
                    if (alternate) {
                        effectiveCursor = checkNotNull(next)
                    } else {
                        return@withLock RecommendationCommentPage(
                            items,
                            next,
                            total,
                            complete =
                                !more && (total == null || items.size >= total),
                        )
                    }
                }
                val page = json.decodeFromString<CommentCursor>(checkNotNull(effectiveCursor))
                check(
                    page.instanceId == instanceId && page.articleId == summary.id && page.version == 1 &&
                        page.offset in 0..MAX_COMMENT_PAGES && page.sorting == sorting,
                ) {
                    "评论游标已失效，请刷新评论"
                }
                requireSiteUrl(page.endpoint)

                suspend fun fetch(): JsonObject {
                    val body =
                        linkedMapOf(
                            "action" to "wpdLoadMoreComments",
                            "postId" to numericId(summary.id),
                            "sorting" to page.sorting,
                            "offset" to page.offset.toString(),
                            "lastParentId" to page.lastParentId,
                            "isFirstLoad" to if (page.offset == 0) "1" else "0",
                            "wpdType" to "",
                        )
                    return json
                        .parseToJsonElement(
                            request(page.endpoint, expected = "json", body = body, referer = url).decodeToString(),
                        ).jsonObject
                }
                var response = fetch()
                if (response["success"]?.jsonPrimitive?.booleanOrNull != true &&
                    response["data"]?.toString()?.contains("nonce", ignoreCase = true) == true
                ) {
                    // Renew only the ordinary public anonymous session, once. Never execute callbacks.
                    request(resolve("comic.html/feed"), expected = "xml")
                    response = fetch()
                }
                check(response["success"]?.jsonPrimitive?.booleanOrNull == true) { "原站评论读取失败，请刷新评论或打开原文" }
                val data = response["data"]?.jsonObject ?: error("原站评论响应无效")
                val commentHtml = data.string("comment_list") ?: error("原站评论响应缺少内容")
                val items = parseComments(Jsoup.parseBodyFragment(commentHtml, url))
                val moreValue =
                    data["is_show_load_more"]?.jsonPrimitive?.contentOrNull
                        ?: error("原站评论响应缺少分页状态")
                val more = moreValue == "true" || moreValue == "1"
                check(!more || items.isNotEmpty()) { "原站评论分页未返回新内容" }
                val next =
                    if (more) {
                        json.encodeToString(
                            page.copy(
                                offset = page.offset + 1,
                                lastParentId =
                                    data.string("last_parent_id") ?: page.lastParentId,
                            ),
                        )
                    } else {
                        null
                    }
                RecommendationCommentPage(items, next, page.reportedTotal, complete = !more)
            }
        }

    private fun parseFeed(bytes: ByteArray): List<RecommendationArticle> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        check(document.documentElement.tagName == "rss" && document.getElementsByTagName("channel").length > 0) {
            "推荐源未返回有效RSS"
        }
        val nodes = document.getElementsByTagName("item")
        check(nodes.length <= 500) { "推荐源列表过大" }
        return (0 until nodes.length)
            .map { index ->
                val item = nodes.item(index) as XmlElement

                fun text(tag: String) =
                    item
                        .getElementsByTagName(tag)
                        .item(0)
                        ?.textContent
                        .orEmpty()
                val link = text("link")
                val remoteId =
                    Regex("/(\\d+)\\.html(?:[?#].*)?$").find(link)?.groupValues?.get(1)
                        ?: error("推荐文章缺少稳定编号")
                val published =
                    runCatching {
                        ZonedDateTime.parse(text("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString()
                    }.getOrDefault(text("pubDate"))
                RecommendationArticle(
                    remoteId,
                    articleUrl(remoteId),
                    Jsoup.parseBodyFragment(text("title")).text(),
                    text("dc:creator"),
                    published,
                    Jsoup.parseBodyFragment(text("description")).text().take(800),
                )
            }.distinctBy { it.id }
    }

    private fun contentBlocks(
        source: Element,
        url: String,
    ): List<RecommendationBlock> {
        val original = source.clone()
        original.select("script, style, form, nav, aside, noscript").remove()
        original.select("iframe, video, audio, object, embed").forEach {
            it.replaceWith(Element("p").attr("data-recommendation-unsupported", "true").text("此嵌入内容请在原文查看"))
        }
        original.select("img").forEach { image ->
            val candidate = image.attr("data-src").ifBlank { image.attr("src") }
            recommendationHttpUrl(candidate, url)?.let { image.attr("src", it) }
        }
        original.select(".spoiler, .wpdiscuz-spoiler-wrap").forEach { it.tagName("details") }
        val shell =
            org.jsoup.nodes.Document
                .createShell(url)
        shell.body().appendChild(original)
        val safelist =
            Safelist
                .relaxed()
                .addTags("details", "summary")
                .addAttributes("p", "data-recommendation-unsupported")
        val clean = Cleaner(safelist).clean(shell).body()
        val result = mutableListOf<RecommendationBlock>()

        fun emit(
            kind: RecommendationBlockKind,
            text: String = "",
            target: String = "",
            level: Int = 0,
        ) {
            if (result.size >= MAX_BLOCKS) return
            if (text.isNotBlank() ||
                target.isNotBlank()
            ) {
                result += RecommendationBlock(kind, text.take(100_000), target, level)
            }
        }

        fun visit(
            node: Node,
            kind: RecommendationBlockKind = RecommendationBlockKind.PARAGRAPH,
            level: Int = 0,
        ) {
            if (node is TextNode) {
                emit(kind, node.text(), level = level)
                return
            }
            if (node !is Element) return
            val tag = node.normalName()
            when {
                node.hasAttr(
                    "data-recommendation-unsupported",
                ) -> {
                    emit(RecommendationBlockKind.UNSUPPORTED, node.text(), url)
                }

                tag == "details" -> {
                    emit(RecommendationBlockKind.SPOILER, node.text())
                }

                tag == "img" -> {
                    val imageUrl = recommendationHttpUrl(node.attr("src"), url)
                    if (imageUrl != null) {
                        emit(RecommendationBlockKind.IMAGE, node.attr("alt"), imageUrl)
                    } else {
                        emit(RecommendationBlockKind.UNSUPPORTED, "图片地址不可用，请在原文查看", url)
                    }
                }

                tag == "a" -> {
                    recommendationHttpUrl(node.attr("href"), url)?.let {
                        emit(RecommendationBlockKind.LINK, node.text().ifBlank { it }, it)
                    }
                        ?: emit(kind, node.text())
                    node.select("img").forEach { visit(it) }
                }

                tag.matches(
                    Regex("h[1-6]"),
                ) -> {
                    emit(RecommendationBlockKind.HEADING, node.text(), level = tag.last().digitToInt())
                }

                tag == "blockquote" -> {
                    node.childNodes().forEach { visit(it, RecommendationBlockKind.QUOTE) }
                }

                tag == "li" -> {
                    emit(kind, "•")
                    node.childNodes().forEach { visit(it, kind, level) }
                }

                tag == "br" -> {
                    Unit
                }

                else -> {
                    val text = StringBuilder()

                    fun flush() {
                        emit(kind, text.toString(), level = level)
                        text.clear()
                    }
                    node.childNodes().forEach { child ->
                        if (child is TextNode) {
                            text.append(child.text())
                        } else if (child is Element && child.normalName() in INLINE_TAGS &&
                            child.select("a, img, details, [data-recommendation-unsupported]").isEmpty()
                        ) {
                            text.append(child.text())
                        } else {
                            flush()
                            visit(child, kind, level)
                        }
                    }
                    flush()
                }
            }
        }
        visit(clean)
        if (result.size >=
            MAX_BLOCKS
        ) {
            result += RecommendationBlock(RecommendationBlockKind.UNSUPPORTED, "正文过长，其余内容请在原文查看", url)
        }
        return result
    }

    private fun parseComments(root: Element): List<RecommendationComment> {
        val elements = root.select(".wpd-comment[id^=wpd-comm-]")
        check(elements.size <= MAX_COMMENTS) { "原站单批评论过多，请打开原文" }
        return elements
            .map { element ->
                val identity = Regex("wpd-comm-(\\d+)_(\\d+)").matchEntire(element.id()) ?: error("原站评论编号无效")
                val own = element.clone().apply { select(".wpd-comment").filter { it !== this }.forEach(Element::remove) }
                val date = own.selectFirst(".wpd-comment-date")
                val body = own.selectFirst(".wpd-comment-text")?.clone()?.apply { select("script, style, form").remove() }
                RecommendationComment(
                    identity.groupValues[1],
                    identity.groupValues[2].takeUnless { it == "0" },
                    own
                        .selectFirst(".wpd-comment-author")
                        ?.text()
                        .orEmpty()
                        .take(200),
                    date?.attr("title")?.ifBlank { date.text() }.orEmpty(),
                    body?.text().orEmpty().take(65_536),
                )
            }.distinctBy { it.id }
    }

    private fun scriptObject(
        root: Element,
        name: String,
    ): JsonObject {
        val pattern = Regex("(?:var\\s+)?${Regex.escape(name)}\\s*=\\s*(\\{[^\\n]*?\\})\\s*;")
        val raw =
            root
                .select("script")
                .asSequence()
                .mapNotNull { pattern.find(it.data())?.groupValues?.get(1) }
                .firstOrNull()
                ?: error("原站评论配置不可用")
        return json.parseToJsonElement(raw).jsonObject
    }

    private suspend fun request(
        address: String,
        expected: String,
        body: Map<String, String>? = null,
        referer: String? = null,
    ): ByteArray {
        var url = requireSiteUrl(address)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            coroutineContext.ensureActive()
            val headers =
                linkedMapOf(
                    "Accept" to
                        when (expected) {
                            "xml" -> "application/rss+xml, application/xml"
                            "json" -> "application/json"
                            else -> "text/html"
                        },
                )
            referer?.let { headers["Referer"] = requireSiteUrl(it) }
            val bytes =
                body
                    ?.entries
                    ?.joinToString("&") { (key, value) ->
                        URLEncoder.encode(key, Charsets.UTF_8) + "=" +
                            URLEncoder.encode(value, Charsets.UTF_8)
                    }?.toByteArray()
            if (bytes != null) headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            val response = http.execute(RecommendationHttpRequest(url, if (body == null) "GET" else "POST", headers, bytes))
            coroutineContext.ensureActive()
            requireSiteUrl(response.url)

            fun header(name: String): String? =
                response.headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
            if (response.status in listOf(301, 302, 303, 307, 308)) {
                check(redirects < MAX_REDIRECTS) { "推荐源重定向过多" }
                url = requireSiteUrl(URI(url).resolve(checkNotNull(header("Location")) { "推荐源重定向地址缺失" }).toString())
                return@repeat
            }
            check(response.status in 200..299) { "原站返回 HTTP ${response.status}" }
            val mime = header("Content-Type").orEmpty().lowercase()
            check(
                when (expected) {
                    "xml" -> "xml" in mime
                    "json" -> "json" in mime
                    else -> "html" in mime
                },
            ) { "原站响应类型不符，可能需要验证或站点已变化" }
            check(response.body.size <= DOCUMENT_LIMIT) { "原站响应超过大小上限" }
            return response.body
        }
        error("推荐源重定向过多")
    }

    private fun numericId(value: String): String =
        value.also {
            require(it.isNotEmpty() && it.all(Char::isDigit)) { "推荐文章编号无效" }
        }

    private fun articleUrl(remoteId: String) = resolve("${numericId(remoteId)}.html")

    private fun resolve(path: String) = requireNotNull(base.resolve(path)) { "推荐地址无效" }.toString()

    private fun requireSiteUrl(url: String): String = url.also { require(sameOrigin(base.toString(), it)) { "推荐源地址不属于当前网站" } }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    @Serializable
    private data class CommentCursor(
        val version: Int = 1,
        val articleId: String,
        val instanceId: String,
        val endpoint: String,
        val lastParentId: String,
        val sorting: String = "by_vote",
        val offset: Int = 1,
        val reportedTotal: Int? = null,
    )

    companion object {
        private const val BASELINE_SIZE = 20
        private const val MAX_HISTORY_PAGES = 4
        private const val MAX_COMMENT_PAGES = 100
        private const val MAX_BLOCKS = 1000
        private const val MAX_COMMENTS = 5000
        private const val MAX_REDIRECTS = 3
        private const val DOCUMENT_LIMIT = 8L * 1024 * 1024
        private const val IMAGE_LIMIT = 8L * 1024 * 1024
        private val INLINE_TAGS = setOf("b", "strong", "i", "em", "span", "small", "u", "s", "code")
    }
}

internal fun recommendationHttpUrl(
    value: String,
    baseUrl: String,
): String? =
    runCatching {
        if (value.isBlank()) return null
        val uri = URI(baseUrl).resolve(value)
        uri.takeIf { it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null }?.toString()
    }.getOrNull()

private fun sameOrigin(
    left: String,
    right: String,
): Boolean =
    runCatching {
        val a = URI(left)
        val b = URI(right)

        fun port(uri: URI) =
            if (uri.port != -1) {
                uri.port
            } else if (uri.scheme == "https") {
                443
            } else {
                80
            }
        a.scheme in setOf("http", "https") && a.userInfo == null && b.userInfo == null &&
            a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }.getOrDefault(false)

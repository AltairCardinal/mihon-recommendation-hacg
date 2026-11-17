package mihon.recommendation.hacg

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.recommendation.api.ArticleMetadataUpgradeProvider
import mihon.recommendation.api.CommentOrder
import mihon.recommendation.api.CommentProvider
import mihon.recommendation.api.ContentRating
import mihon.recommendation.api.CreditRole
import mihon.recommendation.api.DocumentCompleteness
import mihon.recommendation.api.DocumentProvider
import mihon.recommendation.api.ImageRequestProvider
import mihon.recommendation.api.ListPurpose
import mihon.recommendation.api.RecommendationArticleRef
import mihon.recommendation.api.RecommendationArticleSummary
import mihon.recommendation.api.RecommendationBlock
import mihon.recommendation.api.RecommendationCapability
import mihon.recommendation.api.RecommendationChannel
import mihon.recommendation.api.RecommendationComment
import mihon.recommendation.api.RecommendationCredit
import mihon.recommendation.api.RecommendationDocument
import mihon.recommendation.api.RecommendationFactoryDescriptor
import mihon.recommendation.api.RecommendationHostContext
import mihon.recommendation.api.RecommendationHttpClient
import mihon.recommendation.api.RecommendationHttpRequest
import mihon.recommendation.api.RecommendationInstanceConfig
import mihon.recommendation.api.RecommendationMetadata
import mihon.recommendation.api.RecommendationPage
import mihon.recommendation.api.RecommendationPagination
import mihon.recommendation.api.RecommendationProvider
import mihon.recommendation.api.RecommendationProviderFactory
import mihon.recommendation.api.RecommendationWork
import mihon.recommendation.api.WorkKind
import mihon.recommendation.hacg.internal.HacgParser
import mihon.recommendation.hacg.internal.HacgRecommendationMetadataExtractor
import mihon.recommendation.hacg.internal.RecommendationAuthorKind
import mihon.recommendation.hacg.internal.RecommendationBlockKind
import java.net.URI
import java.time.Instant
import mihon.recommendation.hacg.internal.RecommendationArticle as ParsedArticle
import mihon.recommendation.hacg.internal.RecommendationBlock as ParsedBlock
import mihon.recommendation.hacg.internal.RecommendationDocument as ParsedDocument
import mihon.recommendation.hacg.internal.RecommendationWork as ParsedWork

/** Authenticated, no-argument factory. Instances never share a transport or private cursor. */
class HacgFactory : RecommendationProviderFactory {
    override val descriptor =
        RecommendationFactoryDescriptor(
            "hacg",
            "琉璃神社",
            "zh",
            ContentRating.ADULT,
            setOf("www.hacg.icu"),
        )

    override fun create(
        config: RecommendationInstanceConfig,
        context: RecommendationHostContext,
    ): RecommendationProvider {
        require(config.instanceId.isNotBlank()) { "Missing instance identity" }
        require(config.values.isEmpty()) { "This provider has no configurable fields" }
        return HacgProvider(config.instanceId, context.http)
    }
}

private class HacgProvider(
    private val instanceId: String,
    http: RecommendationHttpClient,
) : RecommendationProvider,
    DocumentProvider,
    ArticleMetadataUpgradeProvider,
    CommentProvider,
    ImageRequestProvider {
    private val parser = HacgParser(http, instanceId)
    private val postingPublishers = LinkedHashMap<RecommendationArticleRef, String>(16, .75f, true)
    private val json = Json { ignoreUnknownKeys = true }
    override val extractionVersion = HacgRecommendationMetadataExtractor.VERSION

    override suspend fun channels() =
        listOf(
            RecommendationChannel(
                "comic",
                "漫画",
                RecommendationCapability.entries.toSet(),
                setOf(CommentOrder.POPULAR),
            ),
        )

    override suspend fun list(
        channelId: String,
        purpose: ListPurpose,
        cursor: String?,
    ): RecommendationPage<RecommendationArticleSummary> {
        require(channelId == "comic") { "Unknown channel" }
        if (purpose == ListPurpose.DISCOVER) {
            require(cursor == null) { "Discovery has no continuation cursor" }
            return RecommendationPage(parser.discover().map(::summary), RecommendationPagination.Unknown)
        }
        val nextUrl =
            cursor?.let {
                val decoded =
                    runCatching {
                        json.decodeFromString<HistoryCursor>(
                            it,
                        )
                    }.getOrElse { throw IllegalArgumentException("历史游标已失效", it) }
                require(decoded.version == 1 && decoded.instanceId == instanceId) { "历史游标属于其他实例" }
                decoded.url
            }
        val (items, next) = parser.historyPage(nextUrl)
        return RecommendationPage(
            items.map(::summary),
            next?.let { RecommendationPagination.More(json.encodeToString(HistoryCursor(1, instanceId, it))) }
                ?: RecommendationPagination.End,
        )
    }

    override suspend fun document(article: RecommendationArticleRef): RecommendationDocument {
        val parsed = parser.article(parsedArticle(article))
        return RecommendationDocument(
            article,
            parsed.blocks.map(::toSdkBlock),
            DocumentCompleteness.FULL,
            metadata(parsed, postingPublisher(article)),
        )
    }

    override suspend fun upgradeMetadata(
        article: RecommendationArticleSummary,
        document: RecommendationDocument,
    ): RecommendationMetadata {
        require(article.article == document.article) { "Cached article identity changed" }
        parsedArticle(article.article)
        val native = ParsedDocument(document.blocks.flatMap(::toParsedBlocks))
        return metadata(
            HacgRecommendationMetadataExtractor.extract(article.title, native),
            document.metadata.publisher ?: postingPublisher(article.article),
        )
    }

    override suspend fun comments(
        article: RecommendationArticleRef,
        order: CommentOrder,
        cursor: String?,
    ): RecommendationPage<RecommendationComment> {
        if (order != CommentOrder.POPULAR) throw UnsupportedOperationException("此来源当前仅支持已核实的热门评论排序")
        val sorting = "by_vote"
        val result = parser.comments(parsedArticle(article), cursor, sorting)
        return RecommendationPage(
            result.items.map {
                RecommendationComment(it.id, it.parentId, it.author, listOf(RecommendationBlock.Paragraph(it.text)), timestamp(it.date))
            },
            result.nextCursor?.let { RecommendationPagination.More(it) }
                ?: if (result.complete) RecommendationPagination.End else RecommendationPagination.Unknown,
            result.reportedTotal?.toLong(),
        )
    }

    override fun imageRequest(
        article: RecommendationArticleRef,
        url: String,
    ): RecommendationHttpRequest {
        parsedArticle(article)
        val image = URI(article.url).resolve(url)
        require(
            image.scheme in setOf("http", "https") && image.host == "www.hacg.icu" && image.userInfo == null &&
                image.port in setOf(-1, 80, 443),
        ) { "图片域名尚未获确认权限，请打开原文" }
        return RecommendationHttpRequest(image.toString(), headers = mapOf("Referer" to article.url, "Accept" to "image/*"))
    }

    private fun parsedArticle(article: RecommendationArticleRef): ParsedArticle {
        require(article.remoteId.isNotEmpty() && article.remoteId.all(Char::isDigit)) { "Invalid article ID" }
        require(article.url == "https://www.hacg.icu/wp/${article.remoteId}.html") { "Article URL does not match this source" }
        return ParsedArticle(article.remoteId, article.url, "")
    }

    private fun summary(article: ParsedArticle): RecommendationArticleSummary {
        val reference = RecommendationArticleRef(article.id, article.url)
        if (article.author.isNotBlank()) {
            synchronized(postingPublishers) {
                postingPublishers[reference] = article.author
                while (postingPublishers.size > 256 ||
                    postingPublishers.entries.sumOf {
                        it.key.remoteId.length
                            .toLong() + it.key.url.length + it.value.length
                    } > 524_288L
                ) {
                    val oldest = postingPublishers.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
        return RecommendationArticleSummary(
            reference,
            article.title,
            article.summary,
            timestamp(article.published),
        )
    }

    private fun postingPublisher(article: RecommendationArticleRef): String? =
        synchronized(postingPublishers) { postingPublishers[article] }

    private fun metadata(
        document: ParsedDocument,
        publisher: String?,
    ): RecommendationMetadata =
        RecommendationMetadata(
            document.metadataVersion,
            document.works.mapIndexed { index, work ->
                toSdkWork(work, if (index == 0) document.containedWorks else emptyList())
            },
            document.authors.map { author ->
                RecommendationCredit(
                    author.name,
                    when (author.kind) {
                        RecommendationAuthorKind.INDIVIDUAL -> CreditRole.AUTHOR
                        RecommendationAuthorKind.GROUP -> CreditRole.CIRCLE
                        RecommendationAuthorKind.UNKNOWN -> CreditRole.UNKNOWN
                    },
                    author.aliases,
                )
            },
            publisher,
        )

    private fun toSdkWork(
        work: ParsedWork,
        included: List<ParsedWork>,
    ): RecommendationWork =
        RecommendationWork(
            work.title,
            work.aliases,
            emptyList(),
            included.map { toSdkWork(it, emptyList()) },
            WorkKind.valueOf(work.kind.name),
        )

    private fun toSdkBlock(block: ParsedBlock): RecommendationBlock =
        when (block.kind) {
            RecommendationBlockKind.IMAGE -> {
                RecommendationBlock.Image(block.url, block.text)
            }

            RecommendationBlockKind.LINK -> {
                RecommendationBlock.Link(block.text, block.url)
            }

            RecommendationBlockKind.HEADING -> {
                RecommendationBlock.Heading(block.text, block.level)
            }

            RecommendationBlockKind.QUOTE -> {
                RecommendationBlock.Quote(block.text)
            }

            RecommendationBlockKind.SPOILER -> {
                RecommendationBlock.Spoiler(
                    block.label.ifBlank {
                        "剧透内容"
                    },
                    if (block.children.isEmpty()) {
                        listOf(
                            RecommendationBlock.Paragraph(block.text),
                        )
                    } else {
                        block.children.map(::toSdkBlock)
                    },
                )
            }

            else -> {
                RecommendationBlock.Paragraph(block.text)
            }
        }

    private fun toParsedBlocks(block: RecommendationBlock): List<ParsedBlock> =
        when (block) {
            is RecommendationBlock.Paragraph -> {
                listOf(ParsedBlock(RecommendationBlockKind.PARAGRAPH, block.text))
            }

            is RecommendationBlock.Heading -> {
                listOf(ParsedBlock(RecommendationBlockKind.HEADING, block.text, level = block.level))
            }

            is RecommendationBlock.Quote -> {
                listOf(ParsedBlock(RecommendationBlockKind.QUOTE, block.text))
            }

            is RecommendationBlock.Link -> {
                listOf(ParsedBlock(RecommendationBlockKind.LINK, block.text, block.url))
            }

            is RecommendationBlock.Image -> {
                listOf(ParsedBlock(RecommendationBlockKind.IMAGE, block.description, block.url))
            }

            is RecommendationBlock.Spoiler -> {
                listOf(
                    ParsedBlock(RecommendationBlockKind.SPOILER, label = block.label, children = block.blocks.flatMap(::toParsedBlocks)),
                )
            }
        }

    private fun timestamp(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    @Serializable
    private data class HistoryCursor(
        val version: Int,
        val instanceId: String,
        val url: String,
    )
}

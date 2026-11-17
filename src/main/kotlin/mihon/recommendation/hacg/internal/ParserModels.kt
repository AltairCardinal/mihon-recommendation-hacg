package mihon.recommendation.hacg.internal

import kotlinx.serialization.Serializable

@Serializable
internal data class RecommendationArticle(
    val id: String,
    val url: String,
    val title: String,
    val author: String = "",
    val published: String = "",
    val summary: String = "",
)

internal data class RecommendationArticlePage(
    val items: List<RecommendationArticle>,
    val historyComplete: Boolean = true,
)

@Serializable
internal enum class RecommendationBlockKind { PARAGRAPH, HEADING, QUOTE, IMAGE, LINK, SPOILER, UNSUPPORTED }

@Serializable
internal data class RecommendationBlock(
    val kind: RecommendationBlockKind,
    val text: String = "",
    val url: String = "",
    val level: Int = 0,
    val label: String = "",
    val children: List<RecommendationBlock> = emptyList(),
)

@Serializable
internal data class RecommendationWork(
    val title: String,
    val searchTitle: String = title,
    val aliases: List<String> = emptyList(),
    val kind: RecommendationWorkKind = RecommendationWorkKind.UNKNOWN,
)

@Serializable
internal enum class RecommendationWorkKind { WORK, PERIODICAL, COLLECTION, UNKNOWN }

@Serializable
internal data class RecommendationAuthor(
    val name: String,
    val searchName: String = name,
    val aliases: List<String> = emptyList(),
    val kind: RecommendationAuthorKind = RecommendationAuthorKind.UNKNOWN,
)

@Serializable
internal enum class RecommendationAuthorKind { INDIVIDUAL, GROUP, UNKNOWN }

@Serializable
internal data class RecommendationDocument(
    val blocks: List<RecommendationBlock>,
    val works: List<RecommendationWork> = emptyList(),
    val authors: List<RecommendationAuthor> = emptyList(),
    val metadataVersion: Int = 0,
    val containedWorks: List<RecommendationWork> = emptyList(),
)

internal data class RecommendationComment(
    val id: String,
    val parentId: String? = null,
    val author: String,
    val date: String,
    val text: String,
)

internal data class RecommendationCommentPage(
    val items: List<RecommendationComment>,
    val nextCursor: String? = null,
    val reportedTotal: Int? = null,
    val complete: Boolean = false,
)

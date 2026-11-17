package mihon.recommendation.hacg

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.recommendation.api.ArticleMetadataUpgradeProvider
import mihon.recommendation.api.CreditRole
import mihon.recommendation.api.DocumentCompleteness
import mihon.recommendation.api.DocumentProvider
import mihon.recommendation.api.RecommendationArticleRef
import mihon.recommendation.api.RecommendationArticleSummary
import mihon.recommendation.api.RecommendationHostContext
import mihon.recommendation.api.RecommendationHttpClient
import mihon.recommendation.api.RecommendationHttpResponse
import mihon.recommendation.api.RecommendationInstanceConfig
import mihon.recommendation.api.RecommendationMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class HacgCorpusTest {
    @TestFactory
    fun `thirty original gold articles retain native metadata and offline upgrades`(): List<DynamicTest> {
        val gold = Json.parseToJsonElement(resource("gold.json")).jsonArray
        assertEquals(30, gold.size)
        val listing =
            Json
                .parseToJsonElement(resource("listing.json"))
                .jsonObject
                .getValue("snapshots")
                .jsonArray
                .flatMap { it.jsonObject.getValue("articleIds").jsonArray }
                .take(30)
                .map { it.jsonPrimitive.content }
        assertEquals(
            listing,
            gold.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            },
        )
        return gold.map { node ->
            val expected = node.jsonObject
            val id = expected.getValue("id").jsonPrimitive.content
            DynamicTest.dynamicTest("article $id") {
                runBlocking {
                    var requests = 0
                    val context =
                        object : RecommendationHostContext {
                            override val http =
                                RecommendationHttpClient { request ->
                                    requests++
                                    assertEquals("https://www.hacg.icu/wp/$id.html", request.url)
                                    RecommendationHttpResponse(
                                        200,
                                        request.url,
                                        mapOf("Content-Type" to listOf("text/html")),
                                        resource("$id.html").toByteArray(),
                                    )
                                }
                        }
                    val provider = HacgFactory().create(RecommendationInstanceConfig("sample-$id"), context)
                    val article =
                        RecommendationArticleSummary(
                            RecommendationArticleRef(id, "https://www.hacg.icu/wp/$id.html"),
                            expected.getValue("title").jsonPrimitive.content,
                            "",
                        )
                    val document = (provider as DocumentProvider).document(article.article)

                    fun assertMetadata(metadata: RecommendationMetadata) {
                        assertEquals(1, metadata.extractionVersion)
                        val works = expected.getValue("works").jsonArray
                        works.forEach { assertEquals(it.jsonObject.getValue("title"), it.jsonObject.getValue("searchTitle")) }
                        assertEquals(
                            works.map {
                                it.jsonObject
                                    .getValue("title")
                                    .jsonPrimitive.content
                            },
                            metadata.works.map { it.title },
                        )
                        assertEquals(
                            works.map {
                                it.jsonObject
                                    .getValue("kind")
                                    .jsonPrimitive.content
                            },
                            metadata.works.map { it.kind.name },
                        )
                        assertEquals(
                            works.map {
                                it.jsonObject["aliases"]
                                    ?.jsonArray
                                    ?.map { alias ->
                                        alias.jsonPrimitive.content
                                    }.orEmpty()
                            },
                            metadata.works.map { it.aliases },
                        )
                        val authors = expected.getValue("authorDetails").jsonArray
                        assertEquals(
                            authors.map {
                                it.jsonObject
                                    .getValue("name")
                                    .jsonPrimitive.content
                            },
                            metadata.credits.map { it.name },
                        )
                        assertEquals(
                            authors.map {
                                when (
                                    it.jsonObject
                                        .getValue("kind")
                                        .jsonPrimitive.content
                                ) {
                                    "INDIVIDUAL" -> CreditRole.AUTHOR
                                    "GROUP" -> CreditRole.CIRCLE
                                    else -> CreditRole.UNKNOWN
                                }
                            },
                            metadata.credits.map { it.role },
                        )
                        val aliases = expected["authorAliases"]?.jsonObject
                        assertEquals(
                            authors.map {
                                aliases
                                    ?.get(
                                        it.jsonObject
                                            .getValue("name")
                                            .jsonPrimitive.content,
                                    )?.jsonArray
                                    ?.map { alias ->
                                        alias.jsonPrimitive.content
                                    }.orEmpty()
                            },
                            metadata.credits.map { it.aliases },
                        )
                        assertEquals(
                            expected["containedWorks"]
                                ?.jsonArray
                                ?.map {
                                    it.jsonPrimitive.content
                                }.orEmpty(),
                            metadata.works.flatMap { it.includedWorks }.map { it.title },
                        )
                    }
                    assertMetadata(document.metadata)
                    assertMetadata(
                        (provider as ArticleMetadataUpgradeProvider).upgradeMetadata(
                            article,
                            document.copy(metadata = RecommendationMetadata()),
                        ),
                    )
                    assertEquals(1, requests, "offline extraction must not request source again")
                    assertEquals(DocumentCompleteness.FULL, document.completeness)
                }
            }
        }
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/hacg-30/$name")).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
}

package mihon.recommendation.hacg

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.recommendation.api.ContentRating
import mihon.recommendation.api.RecommendationHostContext
import mihon.recommendation.api.RecommendationHttpClient
import mihon.recommendation.api.RecommendationInstanceConfig
import mihon.recommendation.api.RecommendationProviderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

class HacgPackagingTest {
    @Test
    fun `plugin bundles private parsers and only loads through published SDK factory`() =
        runBlocking<Unit> {
            val file = File(System.getProperty("hacg.pluginJar"))
            JarFile(file).use { jar ->
                val entries =
                    jar
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList()
                assertFalse(
                    entries.any {
                        it.startsWith("mihon/recommendation/api/") || it.startsWith("kotlin/") ||
                            it.startsWith("kotlinx/coroutines/") ||
                            it.startsWith("mihon/desktop/") ||
                            it.startsWith("tachiyomi/")
                    },
                )
                assertTrue(entries.contains("org/jsoup/Jsoup.class"))
                assertTrue(entries.contains("kotlinx/serialization/json/Json.class"))
                assertNull(jar.manifest?.mainAttributes?.getValue("Class-Path"))
                assertFalse(entries.contains("META-INF/INDEX.LIST"))
                val descriptor =
                    Json
                        .parseToJsonElement(
                            jar.getInputStream(jar.getJarEntry("recommendation-plugin.json")).reader().readText(),
                        ).jsonObject
                assertEquals(1, descriptor.getValue("apiMinor").jsonPrimitive.int)
                val binding =
                    descriptor
                        .getValue("legacyBindings")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("hacg", binding.getValue("providerId").jsonPrimitive.content)
                assertEquals(
                    "comic",
                    binding
                        .getValue("channelMapping")
                        .jsonObject
                        .getValue("legacy")
                        .jsonPrimitive.content,
                )
            }
            val pluginLoader =
                object : URLClassLoader(arrayOf(file.toURI().toURL()), javaClass.classLoader) {
                    override fun loadClass(
                        name: String,
                        resolve: Boolean,
                    ): Class<*> =
                        synchronized(getClassLoadingLock(name)) {
                            val private = listOf("mihon.recommendation.hacg.", "org.jsoup.", "kotlinx.serialization.").any(name::startsWith)
                            if (!private) return@synchronized super.loadClass(name, resolve)
                            val loaded = findLoadedClass(name) ?: findClass(name)
                            if (resolve) resolveClass(loaded)
                            loaded
                        }
                }
            pluginLoader.use { loader ->
                val factory =
                    loader
                        .loadClass(
                            "mihon.recommendation.hacg.HacgFactory",
                        ).getDeclaredConstructor()
                        .newInstance() as RecommendationProviderFactory
                assertSame(loader, factory.javaClass.classLoader)
                assertEquals(ContentRating.ADULT, factory.descriptor.contentRating)
                assertEquals(setOf("www.hacg.icu"), factory.descriptor.allowedHosts)
                val context =
                    object : RecommendationHostContext {
                        override val http = RecommendationHttpClient { error("no HTTP during construction") }
                    }
                val first = factory.create(RecommendationInstanceConfig("a"), context)
                val second = factory.create(RecommendationInstanceConfig("b"), context)
                assertNotSame(first, second)
                assertEquals("comic", first.channels().single().id)
            }
        }
}

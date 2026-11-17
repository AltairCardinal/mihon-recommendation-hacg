package mihon.recommendation.hacg.internal

/** Site bibliographic conventions, shared by downloaded pages and legacy native-body caches. */
internal object HacgRecommendationMetadataExtractor {
    const val VERSION = 1

    fun extract(
        title: String,
        document: RecommendationDocument,
    ): RecommendationDocument {
        val lines =
            document.blocks
                .filter {
                    it.kind in
                        setOf(
                            RecommendationBlockKind.PARAGRAPH,
                            RecommendationBlockKind.HEADING,
                            RecommendationBlockKind.QUOTE,
                            RecommendationBlockKind.LINK,
                        )
                }.flatMap { it.text.lines() }
                .map(String::trim)
                .filter(String::isNotBlank)
        val text = lines.joinToString("\n")
        val fields = fields(text)
        val credit = CREDIT_PREFIX.find(title.trim())
        val prefix = credit?.groupValues?.get(1).orEmpty()
        val rawWork = credit?.groupValues?.get(2) ?: title.trim()
        val publishers = fields.filter { it.label in PUBLISHER_LABELS }.map { it.value }
        val authorFields = fields.filter { it.label in AUTHOR_LABELS }
        val groupFields = fields.filter { it.label in GROUP_LABELS }
        val authors =
            when {
                authorFields.isNotEmpty() -> {
                    authorFields.flatMap { parseAuthors(it.value) }
                }

                parenthesizedPerson(
                    prefix,
                ) != null -> {
                    listOf(
                        RecommendationAuthor(parenthesizedPerson(prefix)!!, kind = RecommendationAuthorKind.INDIVIDUAL),
                    )
                }

                groupFields.isNotEmpty() -> {
                    groupFields.flatMap { field ->
                        CreatorNameNormalizer.splitNames(field.value).map {
                            RecommendationAuthor(cleanCredit(it), kind = RecommendationAuthorKind.GROUP)
                        }
                    }
                }

                prefix.isNotBlank() && publishers.none { publisherMatches(prefix, it) } -> {
                    CreatorNameNormalizer.splitNames(prefix).map { RecommendationAuthor(cleanCredit(it)) }
                }

                else -> {
                    emptyList()
                }
            }.filter { validCredit(it.name) && publishers.none { publisher -> publisherMatches(it.name, publisher) } }
                .distinctBy { CreatorNameNormalizer.normalize(it.name) }

        // A post title alone is not enough: a credit prefix or explicit bibliography is needed.
        if (credit == null && authors.isEmpty() && fields.none { it.label == "作品名" }) {
            return document.copy(
                works = emptyList(),
                authors = emptyList(),
                containedWorks = emptyList(),
                metadataVersion = VERSION,
            )
        }
        val collection =
            text.contains("総集編") && lines.any { it.contains("掲載内容") } &&
                (Regex("本[0-9０-９]+冊").containsMatchIn(text) || text.contains("既刊"))
        val periodical = ISSUE.find(rawWork)
        val kind =
            when {
                periodical != null -> RecommendationWorkKind.PERIODICAL
                collection -> RecommendationWorkKind.COLLECTION
                else -> RecommendationWorkKind.WORK
            }
        val (workTitle, headerAliases) =
            if (periodical != null) {
                periodical.groupValues[1].trim() to listOf(periodical.groupValues[2].trim()).filter(String::isNotBlank)
            } else {
                splitWorkTitle(cleanWorkTitle(rawWork), lines)
            }
        val aliases =
            linkedSetOf<String>()
                .apply {
                    addAll(headerAliases)
                    fields.filter { it.label == "作品名" }.forEach { field ->
                        val (name, declaredAliases) = splitWorkTitle(cleanWorkTitle(field.value), emptyList())
                        if (name == workTitle) addAll(declaredAliases)
                    }
                    lines.forEachIndexed { index, line ->
                        val candidate = cleanWorkTitle(CREDIT_PREFIX.find(line)?.groupValues?.get(2) ?: line)
                        val (name, inlineAliases) = splitWorkTitle(candidate, emptyList())
                        if (name == workTitle) {
                            addAll(inlineAliases)
                            lines.getOrNull(index + 1)?.let { next ->
                                PARENTHESIZED_ALIAS
                                    .matchEntire(
                                        next,
                                    )?.groupValues
                                    ?.get(1)
                                    ?.takeIf(::isTranslatedTitle)
                                    ?.let(::add)
                            }
                        }
                    }
                    // Explicit translated-volume notices identify the same collected series, not new works.
                    TRANSLATED_COLLECTION_NOTICE.findAll(text).forEach { match ->
                        cleanWorkTitle(match.groupValues[1]).takeIf(::isTranslatedTitle)?.let(::add)
                    }
                    // A translator-labelled collection heading is evidence of a source-supplied alias.
                    lines.forEach { line ->
                        TRANSLATED_COMPILATION
                            .matchEntire(
                                line,
                            )?.groupValues
                            ?.get(1)
                            ?.trim()
                            ?.takeIf(::isTranslatedTitle)
                            ?.let(::add)
                    }
                }.map(String::trim)
                .filter { it.isNotBlank() && it != workTitle }
                .distinct()
        val works =
            workTitle
                .takeIf(String::isNotBlank)
                ?.let {
                    listOf(RecommendationWork(it, aliases = aliases, kind = kind))
                }.orEmpty()
        val contained =
            if (collection) {
                val marker = lines.indexOfFirst { it.contains("掲載内容") }
                lines
                    .drop(marker + 1)
                    .takeWhile { it.startsWith("・") || it.startsWith("•") }
                    .map {
                        it
                            .removePrefix(
                                "・",
                            ).removePrefix("•")
                            .trim()
                            .replace(Regex("[（(][0-9０-９]+ページ[^）)]*[）)]$"), "")
                            .trim()
                    }.filter(String::isNotBlank)
                    .distinct()
                    .map {
                        RecommendationWork(it, kind = RecommendationWorkKind.WORK)
                    }
            } else {
                emptyList()
            }
        return document.copy(works = works, authors = authors, containedWorks = contained, metadataVersion = VERSION)
    }

    private fun fields(text: String): List<Field> {
        val matches = FIELD_START.findAll(text).toList()
        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val newline = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
            val end = minOf(matches.getOrNull(index + 1)?.range?.first ?: text.length, newline)
            val value = text.substring(start, end).trim()
            value.takeIf(String::isNotBlank)?.let { Field(match.groupValues[1], it) }
        }
    }

    private fun parseAuthors(raw: String): List<RecommendationAuthor> {
        val value = raw.trim()
        val bracket = CREDIT_PREFIX.find(value)
        val credited = bracket?.groupValues?.get(1) ?: value
        val person = parenthesizedPerson(credited)
        if (person != null) return listOf(RecommendationAuthor(person, kind = RecommendationAuthorKind.INDIVIDUAL))
        val aliases =
            bracket
                ?.groupValues
                ?.get(2)
                ?.trim()
                ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z .'-]{0,79}")) }
        return CreatorNameNormalizer.splitNames(credited).mapNotNull { token ->
            val name = cleanCredit(token)
            if (!validCredit(name)) return@mapNotNull null
            val kind =
                if (ORGANIZATION_SHAPED.containsMatchIn(
                        name,
                    )
                ) {
                    RecommendationAuthorKind.UNKNOWN
                } else {
                    RecommendationAuthorKind.INDIVIDUAL
                }
            RecommendationAuthor(name, aliases = if (aliases != null) listOf(aliases) else emptyList(), kind = kind)
        }
    }

    private fun splitWorkTitle(
        raw: String,
        lines: List<String>,
    ): Pair<String, List<String>> {
        val explicit = TITLE_WITH_PARENTHESIZED_ALIAS.matchEntire(raw)
        if (explicit != null && isTranslatedTitle(explicit.groupValues[2])) {
            return explicit.groupValues[1].trim() to listOf(explicit.groupValues[2].trim())
        }
        // Some bilingual headings use whitespace, while the body repeats the exact original on its own line.
        val original =
            lines
                .asSequence()
                .map(::cleanWorkTitle)
                .filter { candidate ->
                    candidate.length >= 4 && KANA.containsMatchIn(candidate) && raw.startsWith("$candidate ") &&
                        isTranslatedTitle(raw.removePrefix(candidate).trim())
                }.maxByOrNull(String::length)
        if (original != null) return original to listOf(raw.removePrefix(original).trim())
        return raw to emptyList()
    }

    private fun cleanWorkTitle(raw: String): String =
        raw
            .trim()
            .replace(EDITION, "")
            .replace(TRAILING_RANGE, "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cleanCredit(raw: String) =
        raw
            .trim()
            .removeSurrounding("[", "]")
            .removeSurrounding("【", "】")
            .trim()

    private fun parenthesizedPerson(raw: String): String? =
        CIRCLE_AND_PERSON
            .matchEntire(raw.trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { validCredit(it) && !ORGANIZATION_SHAPED.containsMatchIn(it) }

    private fun validCredit(raw: String): Boolean =
        raw.isNotBlank() && raw.length <= 100 &&
            !EDITORIAL_OR_PUBLISHER.containsMatchIn(raw)

    private fun publisherMatches(
        name: String,
        publisher: String,
    ): Boolean {
        val key = CreatorNameNormalizer.normalize(name).replace(" ", "")
        return key.length >= 2 && CreatorNameNormalizer.normalize(publisher).replace(" ", "").contains(key)
    }

    private fun isTranslatedTitle(raw: String): Boolean =
        raw.length in 2..160 && HAN.containsMatchIn(raw) && !KANA.containsMatchIn(raw) &&
            !Regex("^(?:[0-9０-９]+|全[0-9０-９]+|[0-9０-９]+ページ)").containsMatchIn(raw)

    private data class Field(
        val label: String,
        val value: String,
    )

    private val AUTHOR_LABELS = setOf("作者", "作家", "著者", "漫画作者")
    private val GROUP_LABELS = setOf("社团名", "社團名", "サークル名")
    private val PUBLISHER_LABELS = setOf("出版社名", "出版社", "出版", "ブランド")
    private val FIELD_LABELS =
        AUTHOR_LABELS + GROUP_LABELS + PUBLISHER_LABELS +
            setOf(
                "作品名",
                "系列名",
                "シリーズ名",
                "汉化",
                "漢化",
                "译者",
                "翻译",
                "标签",
                "レーベル",
                "发售日",
                "発売日",
                "配信開始日",
                "年龄指定",
                "作品形式",
                "文件形式",
                "支持的语言",
                "页数",
                "ページ数",
                "題材",
                "ジャンル",
                "ファイル容量",
                "利用期限",
                "作品内容",
                "分类",
                "活动",
                "定価",
                "メディア",
                "ISBN-13",
            )
    private val FIELD_START =
        Regex(
            "(?:^|\\s)(${FIELD_LABELS.sortedByDescending(String::length).joinToString("|") {
                Regex.escape(it)
            }})\\s*(?:[:：]\\s*|[\\t ]+)",
            RegexOption.MULTILINE,
        )
    private val CREDIT_PREFIX = Regex("^[\\[［【]([^\\]］】]+)[\\]］】]\\s*(.*)$")
    private val CIRCLE_AND_PERSON = Regex(".+?[（(]([^（）()]+)[）)]$")
    private val ORGANIZATION_SHAPED = Regex("(?:家|社|団|團|組|サークル|スタジオ|Studio|Works)$", RegexOption.IGNORE_CASE)
    private val EDITORIAL_OR_PUBLISHER = Regex("編集部|编辑部|編輯部|出版社|出版公司|株式会社|有限会社")
    private val ISSUE = Regex("^(.+?[0-9０-９]{4}年[0-9０-９]{1,2}月号)(?:\\s+(.+))?$")
    private val PARENTHESIZED_ALIAS = Regex("^[（(]([^（）()]+)[）)]$")
    private val TITLE_WITH_PARENTHESIZED_ALIAS = Regex("^(.+?)\\s*[（(]([^（）()]+)[）)]$")
    private val EDITION = Regex("【[^】]*(?:特典|限定|電子版|デジタル|単行本版)[^】]*】")
    private val TRAILING_RANGE =
        Regex(
            "(?:\\s*[（(]\\s*[0-9０-９]+\\s*[）)]?\\s*[-~～]\\s*" +
                "(?:[（(])?[0-9０-９]+\\s*[）)]|\\s+[0-9０-９]+\\s*[-~～]\\s*[0-9０-９]+)\\s*$",
        )
    private val TRANSLATED_COLLECTION_NOTICE = Regex("本作品收录了《([^《》]+)》")
    private val TRANSLATED_COMPILATION = Regex("^(.+?)汉化合集\\s*[0-9０-９]+\\s*[-~～]\\s*[0-9０-９]+$")
    private val KANA = Regex("[\\u3040-\\u30ff]")
    private val HAN = Regex("[\\u3400-\\u9fff]")
}

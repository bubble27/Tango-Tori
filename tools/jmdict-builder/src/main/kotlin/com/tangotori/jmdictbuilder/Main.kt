package com.tangotori.jmdictbuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/**
 * Build the bundled JMdict SQLite for Tango Tori.
 *
 *   1. Resolve the latest jmdict-simplified release on GitHub, pick the
 *      English-only full archive (`jmdict-eng-*.json.zip`).
 *   2. Download + unzip to a cache dir.
 *   3. Parse it (kotlinx.serialization, single in-memory load — JSON is ~30 MB
 *      decompressed, parses to maybe 500 MB live heap; run gradle with
 *      `-Dorg.gradle.jvmargs=-Xmx2g` if your environment is tight).
 *   4. Read the Room identity hash + DDL from
 *      `app/schemas/com.tangotori.app.data.db.JmdictDatabase/1.json`.
 *   5. Write `app/src/main/assets/jmdict.db` with the matching schema and
 *      `room_master_table` row so Room accepts the prepackaged copy.
 *
 * JLPT levels are not present in JMdict and are out of scope for the v1
 * pipeline; `jlptLevel` is left null for every row. The badge slot in the
 * expanded word card just won't render until a JLPT source is wired in
 * later — see TODO at [resolveJlptLevel].
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val rootDir = File(System.getProperty("user.dir")).absoluteFile
    val cacheDir = rootDir.resolve("tools/jmdict-builder/.cache").apply { mkdirs() }
    val schemaDir = rootDir.resolve("app/schemas/com.tangotori.app.data.db.JmdictDatabase")
    // Pick the highest-numbered exported schema. Lets us bump the Room db
    // version (we're on v2 for the kanji_dic table) without editing this path.
    val schemaJson = schemaDir.listFiles()
        ?.filter { it.name.endsWith(".json") }
        ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
        ?: schemaDir.resolve("1.json")
    val outputDb = rootDir.resolve("app/src/main/assets/jmdict.db")

    println("[jmdict-builder] root=$rootDir")
    println("[jmdict-builder] cache=$cacheDir")
    println("[jmdict-builder] output=$outputDb")

    if (!schemaJson.exists()) {
        System.err.println(
            "[jmdict-builder] schema JSON not found at $schemaJson — " +
                "run `./gradlew :app:kspDebugKotlin` first so Room exports it.",
        )
        exitProcess(2)
    }
    val schema = RoomSchemaLoader.load(schemaJson)
    println("[jmdict-builder] schema identityHash=${schema.identityHash}, " +
        "${schema.setupQueries.size} setup queries")

    val jsonFile = if (opts.localJson != null) {
        File(opts.localJson).also {
            require(it.exists()) { "Local JSON $it not found." }
        }
    } else {
        downloadJmdictJson(cacheDir)
    }
    println("[jmdict-builder] reading ${jsonFile.name} (${jsonFile.length() / 1_048_576} MB)")

    val jmdict = jsonFile.inputStream().use { input ->
        JSON.decodeFromStream<JmdictSimplified>(input)
    }
    println(
        "[jmdict-builder] parsed jmdict version=${jmdict.version}, " +
            "languages=${jmdict.languages}, words=${jmdict.words.size}",
    )

    val jlptIndex = loadJlptIndex(rootDir, cacheDir)
    if (jlptIndex.isEmpty()) {
        println(
            "[jmdict-builder] JLPT index empty — JLPT badges will not appear.\n" +
                "  To enable, do one of:\n" +
                "    1) Drop CSV files at tools/jmdict-builder/jlpt/{n5,n4,n3,n2,n1}.csv\n" +
                "       Format: `kanji,reading[,gloss]` per line, # for comments.\n" +
                "    2) Set JLPT_URL_PATTERN env var to a URL template using {level},\n" +
                "       e.g. `https://example.com/jlpt_{level}.csv` (must resolve to a\n" +
                "       CSV/TSV file fetched for level=n1..n5).",
        )
    } else {
        println("[jmdict-builder] JLPT index loaded: ${jlptIndex.size} surface→level mappings.")
    }

    val kanjidic = downloadKanjidicJson(cacheDir)?.let { file ->
        println("[jmdict-builder] reading ${file.name} (${file.length() / 1_048_576} MB)")
        file.inputStream().use { input ->
            JSON.decodeFromStream<Kanjidic2Root>(input)
        }.also {
            println("[jmdict-builder] parsed kanjidic version=${it.version}, characters=${it.characters.size}")
        }
    }
    if (kanjidic == null) {
        println("[jmdict-builder] WARN: kanjidic asset missing — kanji_dic table will be empty.")
    }

    if (outputDb.exists()) outputDb.delete()
    outputDb.parentFile.mkdirs()

    writeDatabase(outputDb, schema, jmdict, jlptIndex, kanjidic)
    println("[jmdict-builder] wrote ${outputDb.length() / 1_048_576} MB → $outputDb")
}

// ----- JLPT vocab loading ----------------------------------------------------

/**
 * Pulls a per-level vocab list from one of three sources, in priority order:
 *  1. Local CSV files at `tools/jmdict-builder/jlpt/{n5..n1}.csv` (user-supplied).
 *  2. Cached downloads in `.cache/jlpt_{n5..n1}.csv` from a prior run.
 *  3. Fresh download from `JLPT_URL_PATTERN` env var or the default
 *     jonsafari/jlpt-resources URL.
 *
 * CSV format: `kanji,reading[,gloss...]`. Lines starting with `#` are ignored.
 * Both kanji and reading are indexed; on collision, the easier level (smaller
 * N number) wins. Returns an empty map if no source yielded any data.
 */
private fun loadJlptIndex(rootDir: File, cacheDir: File): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    // Default source: jamsinclair/open-anki-jlpt-decks. Format per file:
    //   expression,reading,meaning,tags,guid
    // First column is the kanji surface, second is the hiragana reading.
    val urlPattern = System.getenv("JLPT_URL_PATTERN")
        ?: "https://raw.githubusercontent.com/jamsinclair/open-anki-jlpt-decks/main/src/{level}.csv"
    for (level in listOf("N5", "N4", "N3", "N2", "N1")) {
        val lines = loadJlptLevelLines(level, rootDir, cacheDir, urlPattern)
        if (lines.isNullOrEmpty()) continue
        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            // Skip the CSV header row (e.g. `expression,reading,meaning,…`).
            // We detect it by the first column being plain ASCII rather than
            // Japanese script — covers both common header variants.
            if (lineIdx == 0 && trimmed.first().code < 0x80) continue
            val parts = trimmed.split(',', '\t').map { it.trim() }
            // Index up to the first two columns (kanji, reading). putIfAbsent
            // means the easier level (we walk N5→N1) wins on conflict.
            for (form in parts.take(2)) {
                if (form.isNotEmpty()) map.putIfAbsent(form, level)
            }
        }
    }
    return map
}

private fun loadJlptLevelLines(
    level: String,
    rootDir: File,
    cacheDir: File,
    urlPattern: String,
): List<String>? {
    val local = rootDir.resolve("tools/jmdict-builder/jlpt/${level.lowercase()}.csv")
    if (local.exists()) {
        println("[jmdict-builder] JLPT $level ← $local")
        return local.readLines()
    }
    val cache = cacheDir.resolve("jlpt_${level.lowercase()}.csv")
    if (!cache.exists()) {
        val url = urlPattern.replace("{level}", level.lowercase())
        try {
            println("[jmdict-builder] downloading JLPT $level from $url")
            downloadTo(url, cache)
        } catch (e: Exception) {
            println("[jmdict-builder] WARN: JLPT $level unavailable from $url — ${e.message}")
            return null
        }
    }
    return runCatching { cache.readLines() }.getOrNull()
}

// ----- jmdict-simplified resolution + download -------------------------------

private const val GITHUB_LATEST =
    "https://api.github.com/repos/scriptin/jmdict-simplified/releases/latest"

private val http: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private val JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private data class Args(val localJson: String? = null)

private fun parseArgs(args: Array<String>): Args {
    var i = 0
    var local: String? = null
    while (i < args.size) {
        when (args[i]) {
            "--local-json" -> {
                require(i + 1 < args.size) { "--local-json needs a path" }
                local = args[i + 1]; i += 2
            }
            else -> {
                System.err.println("[jmdict-builder] unknown arg ${args[i]}")
                exitProcess(2)
            }
        }
    }
    return Args(localJson = local)
}

private fun downloadKanjidicJson(cacheDir: File): File? {
    println("[jmdict-builder] fetching latest jmdict-simplified release info (kanjidic asset)")
    val release = runCatching {
        JSON.decodeFromString<GithubRelease>(httpGet(GITHUB_LATEST).bodyAsString())
    }.getOrElse {
        println("[jmdict-builder] WARN: couldn't query GitHub release for kanjidic — ${it.message}")
        return null
    }
    val asset = release.assets.firstOrNull { a ->
        a.name.startsWith("kanjidic2-en-") && a.name.endsWith(".json.zip")
    } ?: run {
        println("[jmdict-builder] WARN: no kanjidic2-en-*.json.zip asset in ${release.tagName}")
        return null
    }
    val zipFile = cacheDir.resolve(asset.name)
    if (!zipFile.exists() || zipFile.length() != asset.size) {
        println("[jmdict-builder] downloading ${asset.name} (${asset.size / 1_048_576} MB)")
        runCatching { downloadTo(asset.browserDownloadUrl, zipFile) }.getOrElse {
            println("[jmdict-builder] WARN: kanjidic download failed — ${it.message}")
            return null
        }
    } else {
        println("[jmdict-builder] cached ${asset.name}")
    }
    return unzipJson(zipFile, cacheDir)
}

private fun downloadJmdictJson(cacheDir: File): File {
    println("[jmdict-builder] fetching latest jmdict-simplified release info")
    val releaseJson = httpGet(GITHUB_LATEST).bodyAsString()
    val release = JSON.decodeFromString<GithubRelease>(releaseJson)
    // Pick the full English-only archive — `jmdict-eng-X.Y.Z.json.zip`, NOT
    // `jmdict-eng-common-…` (that's the smaller common-only subset).
    val asset = release.assets.firstOrNull { a ->
        a.name.startsWith("jmdict-eng-") &&
            !a.name.startsWith("jmdict-eng-common") &&
            a.name.endsWith(".json.zip")
    } ?: error(
        "Couldn't find jmdict-eng-*.json.zip asset in release ${release.tagName}. " +
            "Available: ${release.assets.map { it.name }}",
    )
    val zipFile = cacheDir.resolve(asset.name)
    if (!zipFile.exists() || zipFile.length() != asset.size) {
        println("[jmdict-builder] downloading ${asset.name} (${asset.size / 1_048_576} MB)")
        downloadTo(asset.browserDownloadUrl, zipFile)
    } else {
        println("[jmdict-builder] cached ${asset.name}")
    }
    return unzipJson(zipFile, cacheDir)
}

private fun unzipJson(zipFile: File, cacheDir: File): File {
    ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".json")) {
                val out = cacheDir.resolve(entry.name)
                Files.copy(zin, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("[jmdict-builder] extracted ${out.name} (${out.length() / 1_048_576} MB)")
                return out
            }
            entry = zin.nextEntry
        }
    }
    error("No .json entry inside $zipFile")
}

private fun httpGet(url: String): HttpResponse<InputStream> {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "tango-tori-jmdict-builder/1.0")
        .header("Accept", "application/vnd.github+json")
        .GET()
        .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
    if (resp.statusCode() / 100 != 2) {
        error("HTTP ${resp.statusCode()} on $url")
    }
    return resp
}

private fun HttpResponse<InputStream>.bodyAsString(): String = body().use { it.readBytes().decodeToString() }

private fun downloadTo(url: String, dest: File) {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "tango-tori-jmdict-builder/1.0")
        .GET()
        .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(Path.of(dest.absolutePath)))
    if (resp.statusCode() / 100 != 2) {
        // Don't leave a half-written/error-body file in the cache — the next
        // run would happily read it instead of re-fetching.
        dest.delete()
        error("HTTP ${resp.statusCode()} downloading $url")
    }
}

// ----- JLPT (stub) -----------------------------------------------------------

/**
 * Look up the JLPT level for an entry. Prefer matching by kanji form first
 * (e.g. 入る), falling back to kana (e.g. はいる) so kana-only entries still
 * get tagged. Returns null when nothing matches.
 */
private fun resolveJlptLevel(entry: SimpleWord, jlpt: Map<String, String>): String? {
    if (jlpt.isEmpty()) return null
    return entry.kanji.firstNotNullOfOrNull { jlpt[it.text] }
        ?: entry.kana.firstNotNullOfOrNull { jlpt[it.text] }
}

// ----- Database writer -------------------------------------------------------

private fun writeDatabase(
    outputDb: File,
    schema: RoomSchema,
    jmdict: JmdictSimplified,
    jlpt: Map<String, String>,
    kanjidic: Kanjidic2Root?,
) {
    DriverManager.getConnection("jdbc:sqlite:${outputDb.absolutePath}").use { conn ->
        // PRAGMA changes must run outside any transaction. Set tuning first
        // while autoCommit is still on, THEN flip to manual commit for bulk
        // inserts.
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = OFF")
            st.execute("PRAGMA synchronous = OFF")
            st.execute("PRAGMA temp_store = MEMORY")
            st.execute("PRAGMA cache_size = -200000") // ~200 MB page cache
        }
        conn.autoCommit = false

        // Apply Room's DDL + master-table insert verbatim — guarantees the
        // identityHash check passes when the app opens the prepackaged DB.
        conn.createStatement().use { st ->
            for (q in schema.setupQueries) st.executeUpdate(q)
        }
        conn.commit()

        insertAll(conn, jmdict, jlpt)
        conn.commit()

        if (kanjidic != null) {
            insertKanjiDic(conn, kanjidic)
            conn.commit()
        }

        // Compact + analyze for fast lookups on device.
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("ANALYZE")
            st.execute("VACUUM")
        }
    }
}

private fun insertAll(
    conn: Connection,
    jmdict: JmdictSimplified,
    jlpt: Map<String, String>,
) {
    val insertEntry = conn.prepareStatement(
        "INSERT INTO entries(id, isCommon, jlptLevel) VALUES(?, ?, ?)",
    )
    val insertKanji = conn.prepareStatement(
        "INSERT INTO kanji(entryId, text, info, priority) VALUES(?, ?, ?, ?)",
    )
    val insertReading = conn.prepareStatement(
        "INSERT INTO readings(entryId, text, info, priority, noKanji) VALUES(?, ?, ?, ?, ?)",
    )
    val insertSense = conn.prepareStatement(
        "INSERT INTO senses(entryId, orderIndex, partOfSpeech, misc, field, dialect) " +
            "VALUES(?, ?, ?, ?, ?, ?)",
        java.sql.Statement.RETURN_GENERATED_KEYS,
    )
    val insertGloss = conn.prepareStatement(
        "INSERT INTO glosses(senseId, text, language) VALUES(?, ?, ?)",
    )

    var n = 0
    val total = jmdict.words.size
    for (word in jmdict.words) {
        val entryId = word.id.toLongOrNull()
            ?: error("Non-numeric word id ${word.id}")
        val isCommon = word.kanji.any { it.common } || word.kana.any { it.common }
        insertEntry.setLong(1, entryId)
        insertEntry.setInt(2, if (isCommon) 1 else 0)
        val level = resolveJlptLevel(word, jlpt)
        if (level == null) insertEntry.setNull(3, java.sql.Types.VARCHAR)
        else insertEntry.setString(3, level)
        insertEntry.executeUpdate()

        for (k in word.kanji) {
            insertKanji.setLong(1, entryId)
            insertKanji.setString(2, k.text)
            insertKanji.setStringOrNull(3, k.tags.joinToStringOrNull())
            insertKanji.setStringOrNull(4, null)
            insertKanji.executeUpdate()
        }
        for (r in word.kana) {
            insertReading.setLong(1, entryId)
            insertReading.setString(2, r.text)
            insertReading.setStringOrNull(3, r.tags.joinToStringOrNull())
            insertReading.setStringOrNull(4, null)
            // jmdict-simplified `appliesToKanji: ["*"]` means "all kanji forms";
            // anything else (including empty) means restricted/no-kanji reading.
            val noKanji = r.appliesToKanji.isEmpty() ||
                (r.appliesToKanji.size == 1 && r.appliesToKanji[0] != "*")
            insertReading.setInt(5, if (noKanji) 1 else 0)
            insertReading.executeUpdate()
        }
        for ((idx, sense) in word.sense.withIndex()) {
            insertSense.setLong(1, entryId)
            insertSense.setInt(2, idx)
            insertSense.setString(3, sense.partOfSpeech.joinToString(";"))
            insertSense.setStringOrNull(4, sense.misc.joinToStringOrNull())
            insertSense.setStringOrNull(5, sense.field.joinToStringOrNull())
            insertSense.setStringOrNull(6, sense.dialect.joinToStringOrNull())
            insertSense.executeUpdate()
            val senseId = insertSense.generatedKeys.use { rs ->
                check(rs.next()) { "no generated senseId" }
                rs.getLong(1)
            }
            for (g in sense.gloss) {
                insertGloss.setLong(1, senseId)
                insertGloss.setString(2, g.text)
                insertGloss.setString(3, g.lang)
                insertGloss.executeUpdate()
            }
        }
        n++
        if (n % 10_000 == 0) {
            println("[jmdict-builder] inserted $n / $total entries")
            conn.commit()
        }
    }
    println("[jmdict-builder] inserted $n / $total entries (done)")

    insertEntry.close(); insertKanji.close(); insertReading.close()
    insertSense.close(); insertGloss.close()
}

private fun insertKanjiDic(conn: Connection, kanjidic: Kanjidic2Root) {
    val ps = conn.prepareStatement(
        "INSERT OR REPLACE INTO kanji_dic(character, meanings, readings) VALUES(?, ?, ?)",
    )
    var n = 0
    var skipped = 0
    for (c in kanjidic.characters) {
        val groups = c.readingMeaning?.groups.orEmpty()
        val meanings = groups
            .flatMap { g -> g.meanings.filter { it.lang == "en" }.map { it.value } }
            .distinct()
            .joinToString(";")
        // Kun / on readings, normalized to hiragana with the okurigana-separator
        // dot stripped. This is the input to the reading-splitter in
        // KanjiBreakdownHtmlBuilder, which tries to align a compound word
        // reading like "なかま" against per-kanji readings like {"なか"} & {"ま"}.
        val readings = groups
            .flatMap { g -> g.readings }
            .filter { it.type == "ja_kun" || it.type == "ja_on" }
            .flatMap { normalizeKanjiReadings(it.value) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(";")
        if (meanings.isEmpty()) {
            skipped++
            continue
        }
        ps.setString(1, c.literal)
        ps.setString(2, meanings)
        ps.setString(3, readings)
        ps.executeUpdate()
        n++
    }
    ps.close()
    println("[jmdict-builder] inserted $n kanji_dic rows (skipped $skipped with no English meanings)")
}

/**
 * Kanjidic kun-yomi entries use `.` to mark the boundary between the
 * kanji-bound part and the okurigana — e.g. `かま.える` (構): bound = `かま`,
 * okurigana = `える`, full kun reading = `かまえる`.
 *
 * For the reading splitter we want BOTH:
 *   - the bound form (`かま`) so compounds like 身構える (みがま+える) split
 *     across kanji: rendaku of `かま` → `がま`.
 *   - the full form (`かまえる`) so standalone verb forms still match.
 *
 * On-yomi values don't carry the dot; only katakana → hiragana conversion
 * and hyphen stripping applies. We always emit the full form; the bound
 * form is added only when a dot is present.
 */
private fun normalizeKanjiReadings(raw: String): List<String> {
    val hira = raw.replace("-", "").map { c ->
        if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c
    }.joinToString("")
    val full = hira.replace(".", "")
    val results = mutableListOf<String>()
    if (full.isNotEmpty()) results += full
    val dot = hira.indexOf('.')
    if (dot > 0) {
        val bound = hira.substring(0, dot)
        if (bound.isNotEmpty() && bound != full) results += bound
    }
    return results
}

private fun java.sql.PreparedStatement.setStringOrNull(idx: Int, value: String?) {
    if (value == null) setNull(idx, java.sql.Types.VARCHAR) else setString(idx, value)
}

private fun List<String>.joinToStringOrNull(): String? =
    if (isEmpty()) null else joinToString(";")

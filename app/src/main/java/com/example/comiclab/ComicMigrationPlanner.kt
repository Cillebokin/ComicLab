package com.example.comiclab

import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

enum class ComicMigrationMatchReason {
    EXACT_NAME,
    CONTAINS_MARKER,
    COMPONENT_ALIAS,
    KANA_ROMAJI
}

data class ComicMigrationDirectoryMatch(
    val directory: File,
    val reason: ComicMigrationMatchReason
)

data class ComicMigrationTargetPlan(
    val comicFile: File,
    val startMarker: String,
    val directories: List<File>
)

enum class ComicMigrationCopyStatus {
    PREVIEW_ONLY,
    COPIED,
    ALREADY_EXISTS,
    CANCELLED,
    FAILED
}

data class ComicMigrationCopyResult(
    val status: ComicMigrationCopyStatus,
    val error: String? = null
)

class ComicMigrationPlanner(
    val allowFileCopy: Boolean = false,
    private val isComicFile: (File) -> Boolean = { file ->
        ReaderFileDetector.isSupported(file)
    }
) {

    fun scanComicFilesRecursively(
        rootDirectory: File,
        workerExecutor: ExecutorService? = null
    ): List<File> {
        ensurePreparationNotInterrupted()
        if (!rootDirectory.isDirectory) {
            throw IOException("Source directory is unavailable")
        }

        val pending = ArrayDeque<File>()
        val visitedDirectories = mutableSetOf<String>()
        val visitedFiles = mutableSetOf<String>()
        val comics = mutableListOf<File>()
        pending.add(rootDirectory)

        while (pending.isNotEmpty()) {
            ensurePreparationNotInterrupted()
            val currentLevel = mutableListOf<File>()
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                if (directory.isDirectory && visitedDirectories.add(directory.stablePath())) {
                    currentLevel.add(directory)
                }
            }

            val childrenByDirectory = mapWithWorkerPool(
                currentLevel,
                workerExecutor
            ) { directory ->
                ensurePreparationNotInterrupted()
                readChildren(directory).sortedWith(filePathComparator)
            }

            childrenByDirectory.forEach { children ->
                children.filter { it.isDirectory }.forEach { child ->
                    ensurePreparationNotInterrupted()
                    if (child.isSameOrDescendantOf(rootDirectory)) {
                        pending.add(child)
                    }
                }

                val files = children.filter {
                    it.isFile && it.isSameOrDescendantOf(rootDirectory)
                }
                val supportedComicFiles = mapWithWorkerPool(files, workerExecutor) { file ->
                    ensurePreparationNotInterrupted()
                    file.takeIf { runCatching { isComicFile(file) }.getOrDefault(false) }
                }
                supportedComicFiles.filterNotNull().forEach { file ->
                    if (visitedFiles.add(file.stablePath())) {
                        comics.add(file)
                    }
                }
            }
        }

        return comics.sortedWith(filePathComparator)
    }

    fun findMatchingDirectories(
        rootDirectory: File,
        startMarker: String,
        excludedDirectory: File? = null
    ): List<ComicMigrationDirectoryMatch> {
        if (!rootDirectory.isDirectory) {
            throw IOException("Destination tree is unavailable")
        }
        if (startMarker.isBlank()) {
            return emptyList()
        }

        return scanDestinationDirectories(rootDirectory, excludedDirectory)
            .mapNotNull { directory ->
                findMatchReason(startMarker, directory.name)?.let { reason ->
                    ComicMigrationDirectoryMatch(directory, reason)
                }
            }
            .sortedWith(compareBy(filePathComparator) { it.directory })
    }

    fun prepareTargetPlans(
        comicFiles: List<File>,
        rootDirectory: File,
        defaultDirectory: File,
        excludedDirectory: File? = null,
        markerForFile: (File) -> String,
        workerExecutor: ExecutorService? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ComicMigrationTargetPlan> {
        ensurePreparationNotInterrupted()
        if (!rootDirectory.isDirectory) {
            throw IOException("Destination tree is unavailable")
        }

        val comicsWithMarkers = mapWithWorkerPool(comicFiles, workerExecutor) { file ->
            ensurePreparationNotInterrupted()
            file to markerForFile(file)
        }
        val candidateDirectories = if (comicsWithMarkers.any { it.second.isNotBlank() }) {
            scanDestinationDirectories(rootDirectory, excludedDirectory, workerExecutor)
                .sortedWith(filePathComparator)
        } else {
            emptyList()
        }

        if (comicsWithMarkers.isEmpty()) {
            return emptyList()
        }

        onProgress(0, comicsWithMarkers.size)
        val completed = AtomicInteger()
        return mapWithWorkerPool(comicsWithMarkers, workerExecutor) { (comicFile, marker) ->
            val matches = candidateDirectories.mapNotNull { directory ->
                ensurePreparationNotInterrupted()
                findMatchReason(marker, directory.name)?.let { reason ->
                    ComicMigrationDirectoryMatch(directory, reason)
                }
            }
            val plan = ComicMigrationTargetPlan(
                comicFile = comicFile,
                startMarker = marker,
                directories = targetDirectoryChoices(matches, defaultDirectory)
            )
            onProgress(completed.incrementAndGet(), comicsWithMarkers.size)
            plan
        }
    }

    fun targetDirectoryChoices(
        matches: List<ComicMigrationDirectoryMatch>,
        defaultDirectory: File
    ): List<File> = matches.map { it.directory } + defaultDirectory

    fun copyComicFile(
        sourceFile: File,
        targetDirectory: File,
        shouldCancel: () -> Boolean = { false }
    ): ComicMigrationCopyResult {
        if (!sourceFile.isFile || !targetDirectory.isDirectory) {
            return ComicMigrationCopyResult(ComicMigrationCopyStatus.FAILED)
        }
        if (!allowFileCopy) {
            return ComicMigrationCopyResult(ComicMigrationCopyStatus.PREVIEW_ONLY)
        }

        val destinationFile = File(targetDirectory, sourceFile.name)
        if (destinationFile.exists()) {
            return ComicMigrationCopyResult(ComicMigrationCopyStatus.ALREADY_EXISTS)
        }

        var temporaryPath: java.nio.file.Path? = null
        return try {
            if (isCopyCancelled(shouldCancel)) {
                throw ComicMigrationCopyCancelledException()
            }
            val tempPath = Files.createTempFile(targetDirectory.toPath(), ".cl-", ".part")
            temporaryPath = tempPath
            sourceFile.inputStream().use { input ->
                Files.newOutputStream(tempPath).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        if (isCopyCancelled(shouldCancel)) {
                            throw ComicMigrationCopyCancelledException()
                        }
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            break
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            if (isCopyCancelled(shouldCancel)) {
                throw ComicMigrationCopyCancelledException()
            }
            Files.move(tempPath, destinationFile.toPath())
            ComicMigrationCopyResult(ComicMigrationCopyStatus.COPIED)
        } catch (_: FileAlreadyExistsException) {
            ComicMigrationCopyResult(ComicMigrationCopyStatus.ALREADY_EXISTS)
        } catch (_: ComicMigrationCopyCancelledException) {
            ComicMigrationCopyResult(ComicMigrationCopyStatus.CANCELLED)
        } catch (error: Exception) {
            ComicMigrationCopyResult(
                ComicMigrationCopyStatus.FAILED,
                error.localizedMessage
            )
        } finally {
            temporaryPath?.let { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private fun isCopyCancelled(shouldCancel: () -> Boolean): Boolean {
        return Thread.currentThread().isInterrupted || shouldCancel()
    }

    private fun readChildren(directory: File): List<File> {
        return directory.listFiles()?.toList()
            ?: throw IOException("Unable to read directory")
    }

    private fun <T, R> mapWithWorkerPool(
        items: List<T>,
        workerExecutor: ExecutorService?,
        transform: (T) -> R
    ): List<R> {
        if (workerExecutor == null || items.size < 2) {
            return items.map(transform)
        }

        val workerCount = (workerExecutor as? ThreadPoolExecutor)
            ?.maximumPoolSize
            ?.coerceIn(1, MAX_WORKER_COUNT)
            ?: Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_WORKER_COUNT)
        val batchSize = workerCount * TASKS_PER_WORKER
        val results = ArrayList<R>(items.size)

        items.chunked(batchSize).forEach { batch ->
            ensurePreparationNotInterrupted()
            val tasks = batch.map { item -> Callable { transform(item) } }
            val futures = try {
                workerExecutor.invokeAll(tasks)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }

            futures.forEach { future ->
                try {
                    results.add(future.get())
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        }
        return results
    }

    private fun ensurePreparationNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Comic migration preparation was interrupted")
        }
    }

    private fun scanDestinationDirectories(
        rootDirectory: File,
        excludedDirectory: File?,
        workerExecutor: ExecutorService? = null
    ): List<File> {
        val pending = ArrayDeque<File>()
        val visitedPaths = mutableSetOf<String>()
        val directories = mutableListOf<File>()
        pending.add(rootDirectory)

        while (pending.isNotEmpty()) {
            ensurePreparationNotInterrupted()
            val currentLevel = mutableListOf<File>()
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                if (directory.isDirectory && visitedPaths.add(directory.stablePath())) {
                    currentLevel.add(directory)
                }
            }

            val childrenByDirectory = mapWithWorkerPool(
                currentLevel,
                workerExecutor
            ) { directory ->
                ensurePreparationNotInterrupted()
                readChildren(directory).filter { it.isDirectory }.sortedWith(filePathComparator)
            }
            childrenByDirectory.forEach { children ->
                children.forEach childLoop@ { child ->
                    ensurePreparationNotInterrupted()
                    if (!child.isSameOrDescendantOf(rootDirectory) ||
                        excludedDirectory != null && child.isSameOrDescendantOf(excludedDirectory)
                    ) {
                        return@childLoop
                    }
                    directories.add(child)
                    pending.add(child)
                }
            }
        }

        return directories.distinctBy { it.stablePath() }
    }

    companion object {

        fun isSafeDestinationDirectory(sourceDirectory: File, destination: File): Boolean {
            if (!sourceDirectory.isDirectory || !destination.isDirectory) {
                return false
            }
            return !destination.isSameOrDescendantOf(sourceDirectory)
        }

        private val filePathComparator = compareBy<File>(
            { it.name.lowercase(Locale.ROOT) },
            { it.absolutePath.lowercase(Locale.ROOT) }
        )

        private val kanaRomanization = mapOf(
            'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
            'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
            'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
            'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
            'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
            'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
            'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
            'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
            'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
            'ワ' to "wa", 'ヲ' to "wo", 'ン' to "n",
            'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
            'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
            'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
            'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
            'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
            'ヴ' to "vu",
            'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
            'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo", 'ヮ' to "wa"
        )

        private val kanaDigraphs = mapOf(
            "イェ" to "ye",
            "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
            "キェ" to "kye", "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
            "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
            "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
            "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ジェ" to "je",
            "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che",
            "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
            "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
            "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
            "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
            "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
            "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
            "ツァ" to "tsa", "ツィ" to "tsi", "ツェ" to "tse", "ツォ" to "tso",
            "ティ" to "ti", "トゥ" to "tu", "ディ" to "di", "ドゥ" to "du",
            "デュ" to "dyu", "フュ" to "fyu",
            "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
            "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo",
            "ヴュ" to "vyu"
        )

        // 统一常见的赫本式、训令式拼法；这些规则只扩充候选，不会自动决定目标。
        private val romanizationAliases = listOf(
            "ou" to "oo", "ei" to "ee",
            "sya" to "sha", "syu" to "shu", "syo" to "sho",
            "tya" to "cha", "tyu" to "chu", "tyo" to "cho",
            "cya" to "cha", "cyu" to "chu", "cyo" to "cho",
            "zya" to "ja", "zyu" to "ju", "zyo" to "jo",
            "jya" to "ja", "jyu" to "ju", "jyo" to "jo",
            "si" to "shi", "ti" to "chi", "tu" to "tsu",
            "hu" to "fu", "zi" to "ji"
        )

        private val romajiSyllables = (
            kanaRomanization.values + kanaDigraphs.values +
                romanizationAliases.flatMap { (variant, canonical) ->
                    listOf(variant, canonical)
                }
            ).toSet()

        private fun findMatchReason(
            startMarker: String,
            directoryName: String
        ): ComicMigrationMatchReason? {
            val literalMarker = normalizeName(startMarker)
            val literalDirectory = normalizeName(directoryName)

            if (literalMarker.isNotEmpty() && literalMarker == literalDirectory) {
                return ComicMigrationMatchReason.EXACT_NAME
            }

            if (literalMarker.isNotEmpty() && literalDirectory.contains(literalMarker)) {
                return ComicMigrationMatchReason.CONTAINS_MARKER
            }

            val components = startMarker.split(PARENTHESES)
                .map(String::trim)
                .filter(String::isNotEmpty)
            components.forEach { component ->
                val literalComponent = normalizeName(component)
                if (literalComponent.isNotEmpty() && literalDirectory.contains(literalComponent)) {
                    return ComicMigrationMatchReason.COMPONENT_ALIAS
                }
            }

            val markerParts = (listOf(startMarker) + components).distinct()
            val markerEquivalents = markerParts.flatMap(::equivalenceForms)
            val directoryEquivalents = equivalenceForms(directoryName)
            if (markerEquivalents.any { marker ->
                    directoryEquivalents.any { directory -> directory.contains(marker) }
                }
            ) {
                return ComicMigrationMatchReason.KANA_ROMAJI
            }

            return null
        }

        private fun equivalenceForms(value: String): List<String> {
            val forms = linkedSetOf<String>()
            val normalizedWholeName = normalizeName(value)
            if (isCompleteRomaji(normalizedWholeName)) {
                forms.add(canonicalizeRomanization(normalizedWholeName))
            }
            KANA_RUN.findAll(value).forEach { match ->
                romanizeKana(match.value)
                    ?.let(::normalizeName)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { forms.add(canonicalizeRomanization(it)) }
            }
            LATIN_RUN.findAll(value).forEach { match ->
                val normalized = normalizeName(match.value)
                if (isCompleteRomaji(normalized)) {
                    forms.add(canonicalizeRomanization(normalized))
                }
            }
            return forms.toList()
        }

        private fun romanizeKana(value: String): String? {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
            val codePoints = normalized.codePoints().toArray()
            if (codePoints.none { it.isJapaneseKana() }) {
                return null
            }

            val result = StringBuilder()
            var index = 0
            while (index < codePoints.size) {
                val katakana = codePoints[index].toKatakana()
                when (katakana) {
                    SMALL_TSU -> {
                        val next = kanaUnit(codePoints, index + 1)
                        val initial = next?.first?.firstOrNull()
                        if (initial != null && initial !in "aeioun") {
                            result.append(if (next.first.startsWith("ch")) 't' else initial)
                        }
                        index++
                    }

                    SYLLABIC_N -> {
                        val next = kanaUnit(codePoints, index + 1)?.first.orEmpty()
                        val nextStartsWithVowel = next.firstOrNull()?.let { it in "aeiouy" } == true
                        result.append(if (nextStartsWithVowel) "n'" else "n")
                        index++
                    }

                    PROLONGED_SOUND_MARK -> {
                        result.lastVowel()?.let { result.append(it) }
                        index++
                    }

                    else -> {
                        val unit = kanaUnit(codePoints, index)
                        if (unit == null) {
                            result.appendCodePoint(codePoints[index])
                            index++
                        } else {
                            result.append(unit.first)
                            index += unit.second
                        }
                    }
                }
            }
            return result.toString()
        }

        private fun kanaUnit(codePoints: IntArray, index: Int): Pair<String, Int>? {
            if (index !in codePoints.indices) {
                return null
            }
            val first = codePoints[index].toKatakana()
            if (!first.isJapaneseKana()) {
                return null
            }

            if (index + 1 < codePoints.size) {
                val second = codePoints[index + 1].toKatakana()
                val digraph = kanaDigraphs["${first.toChar()}${second.toChar()}"]
                if (digraph != null) {
                    return digraph to 2
                }
            }
            return kanaRomanization[first.toChar()]?.let { it to 1 }
        }

        private fun canonicalizeRomanization(value: String): String {
            if (!isCompleteRomaji(value)) {
                return value
            }
            var result = value
            romanizationAliases.forEach { (variant, canonical) ->
                result = result.replace(variant, canonical)
            }
            return result
        }

        private fun isCompleteRomaji(value: String): Boolean {
            if (value.isEmpty() || value.any { it !in 'a'..'z' && it != APOSTROPHE.toChar() }) {
                return false
            }

            val canSegment = BooleanArray(value.length + 1)
            canSegment[0] = true
            for (index in value.indices) {
                if (!canSegment[index]) {
                    continue
                }
                if (value[index] == APOSTROPHE.toChar()) {
                    if (index > 0 && value[index - 1] == 'n' && index + 1 < value.length) {
                        canSegment[index + 1] = true
                    }
                    continue
                }
                if (index + 1 < value.length &&
                    value[index] in GEMINATABLE_CONSONANTS &&
                    (value[index] == value[index + 1] ||
                        value[index] == 't' && value.startsWith("ch", index + 1))
                ) {
                    canSegment[index + 1] = true
                }
                romajiSyllables.forEach { syllable ->
                    if (value.startsWith(syllable, index)) {
                        canSegment[index + syllable.length] = true
                    }
                }
            }
            return canSegment[value.length]
        }

        private fun normalizeName(value: String): String {
            val normalized = Normalizer.normalize(
                value.replace('\u2019', '\''),
                Normalizer.Form.NFKC
            )
                .lowercase(Locale.ROOT)
            return buildString {
                normalized.codePoints().forEach { codePoint ->
                    when {
                        codePoint in LONG_VOWEL_MACRONS -> append(LONG_VOWEL_MACRONS.getValue(codePoint))
                        Character.isLetterOrDigit(codePoint) || codePoint == APOSTROPHE ->
                            appendCodePoint(codePoint)
                    }
                }
            }
        }

        private fun Int.isJapaneseKana(): Boolean {
            val katakana = toKatakana()
            return katakana in 0x30A1..0x30F6 || katakana in 0x30F7..0x30FA
        }

        private fun Int.toKatakana(): Int {
            return if (this in 0x3041..0x3096) this + HIRAGANA_TO_KATAKANA_OFFSET else this
        }

        private fun StringBuilder.lastVowel(): Char? {
            for (index in lastIndex downTo 0) {
                if (this[index] in "aeiou") {
                    return this[index]
                }
            }
            return null
        }

        private fun File.isSameOrDescendantOf(parent: File): Boolean {
            return runCatching {
                canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())
            }.getOrDefault(absoluteFile.toPath().startsWith(parent.absoluteFile.toPath()))
        }

        private fun File.stablePath(): String {
            return runCatching { canonicalPath }.getOrDefault(absolutePath)
        }

        private val PARENTHESES = Regex("[()（）]")
        private val KANA_RUN = Regex("[\\u3041-\\u3096\\u30A1-\\u30FAー]+")
        private val LATIN_RUN = Regex("[A-Za-z\\u00C0-\\u024F'’]+")
        private const val HIRAGANA_TO_KATAKANA_OFFSET = 0x60
        private const val SMALL_TSU = 0x30C3
        private const val SYLLABIC_N = 0x30F3
        private const val PROLONGED_SOUND_MARK = 0x30FC
        private const val COPY_BUFFER_SIZE = 128 * 1024
        private const val MAX_WORKER_COUNT = 8
        private const val TASKS_PER_WORKER = 2
        private const val APOSTROPHE = 0x27
        private const val GEMINATABLE_CONSONANTS = "bcdfghjklmpqrstvwxyz"

        private val LONG_VOWEL_MACRONS = mapOf(
            'ā'.code to "aa", 'â'.code to "aa",
            'ī'.code to "ii", 'î'.code to "ii",
            'ū'.code to "uu", 'û'.code to "uu",
            'ē'.code to "ee", 'ê'.code to "ee",
            'ō'.code to "oo", 'ô'.code to "oo"
        )

        private class ComicMigrationCopyCancelledException : Exception()
    }
}

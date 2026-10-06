package com.example.comiclab.core.organize

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal object DirectorySimilarityScanner {

    fun createReport(
        rootDirectory: File,
        formatMessage: (DirectorySimilarityReportMessage, Array<out Any>) -> String,
        clock: () -> Date = { Date() },
        onProgress: (DirectorySimilarityProgress) -> Unit
    ): DirectorySimilarityReport {
        onProgress(DirectorySimilarityProgress(DirectorySimilarityProgress.Stage.SCANNING_DIRECTORIES))
        val directories = scanDirectories(rootDirectory)
        onProgress(
            DirectorySimilarityProgress(
                stage = DirectorySimilarityProgress.Stage.COMPARING_DIRECTORY_NAMES,
                completed = 0,
                total = directories.size
            )
        )

        val pairs = findSimilarDirectoryNamePairs(directories) { completed, total ->
            onProgress(
                DirectorySimilarityProgress(
                    stage = DirectorySimilarityProgress.Stage.COMPARING_DIRECTORY_NAMES,
                    completed = completed,
                    total = total
                )
            )
        }
        val outputFile = createReportFile(rootDirectory, clock())
        outputFile.writeText(
            buildReportText(rootDirectory, directories, pairs, formatMessage, clock),
            Charsets.UTF_8
        )
        return DirectorySimilarityReport(
            outputFile = outputFile,
            directoryCount = directories.size,
            pairCount = pairs.size
        )
    }

    private fun scanDirectories(rootDirectory: File): List<SimilarDirectoryInfo> {
        val result = mutableListOf<SimilarDirectoryInfo>()
        val pending = ArrayDeque<File>()
        val visited = mutableSetOf<String>()

        runCatching {
            rootDirectory.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                ?.forEach { pending.add(it) }
        }

        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            if (!directory.isDirectory || directory.name.startsWith(".")) {
                continue
            }

            val stablePath = runCatching { directory.canonicalPath }
                .getOrDefault(directory.absolutePath)
            if (!visited.add(stablePath)) {
                continue
            }

            result.add(
                SimilarDirectoryInfo(
                    name = directory.name,
                    path = directory.absolutePath
                )
            )

            val children = runCatching {
                directory.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                    .orEmpty()
            }.getOrDefault(emptyList())
            children.forEach { pending.add(it) }
        }

        return result.sortedBy { it.path.lowercase(Locale.ROOT) }
    }

    private fun findSimilarDirectoryNamePairs(
        directories: List<SimilarDirectoryInfo>,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<SimilarDirectoryNamePair> {
        val candidates = directories.mapNotNull { directory ->
            normalizeDirectoryName(directory.name)
                .takeIf { it.isNotBlank() }
                ?.let { normalizedName -> SimilarDirectoryNameCandidate(directory, normalizedName) }
        }

        if (candidates.size < 2) {
            onProgress(candidates.size, candidates.size)
            return emptyList()
        }

        val pairs = mutableListOf<SimilarDirectoryNamePair>()
        for (leftIndex in 0 until candidates.lastIndex) {
            val left = candidates[leftIndex]
            for (rightIndex in (leftIndex + 1)..candidates.lastIndex) {
                val right = candidates[rightIndex]
                val maxLength = maxOf(left.normalizedName.length, right.normalizedName.length)
                val minLength = minOf(left.normalizedName.length, right.normalizedName.length)
                if (maxLength <= 0 || minLength.toDouble() / maxLength.toDouble() < SIMILARITY_THRESHOLD) {
                    continue
                }

                val similarity = directoryNameSimilarity(left.normalizedName, right.normalizedName)
                if (similarity >= SIMILARITY_THRESHOLD) {
                    pairs.add(
                        SimilarDirectoryNamePair(
                            left = left.directory,
                            right = right.directory,
                            similarity = similarity
                        )
                    )
                }
            }

            if (leftIndex % PROGRESS_ROW_INTERVAL == 0 || leftIndex == candidates.lastIndex - 1) {
                onProgress(leftIndex + 1, candidates.size)
            }
        }
        onProgress(candidates.size, candidates.size)

        return pairs.sortedWith(
            compareByDescending<SimilarDirectoryNamePair> { it.similarity }
                .thenBy { it.left.name.lowercase(Locale.ROOT) }
                .thenBy { it.right.name.lowercase(Locale.ROOT) }
                .thenBy { it.left.path.lowercase(Locale.ROOT) }
                .thenBy { it.right.path.lowercase(Locale.ROOT) }
        )
    }

    private fun buildReportText(
        rootDirectory: File,
        directories: List<SimilarDirectoryInfo>,
        pairs: List<SimilarDirectoryNamePair>,
        formatMessage: (DirectorySimilarityReportMessage, Array<out Any>) -> String,
        clock: () -> Date
    ): String {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(clock())
        return buildString {
            appendLine(formatMessage(DirectorySimilarityReportMessage.TITLE, emptyArray()))
            appendLine(formatMessage(DirectorySimilarityReportMessage.GENERATED_AT, arrayOf(generatedAt)))
            appendLine(formatMessage(DirectorySimilarityReportMessage.ROOT_DIRECTORY, arrayOf(rootDirectory.absolutePath)))
            appendLine(
                formatMessage(
                    DirectorySimilarityReportMessage.SIMILARITY_RULE,
                    arrayOf(similarityPercent(SIMILARITY_THRESHOLD))
                )
            )
            appendLine(formatMessage(DirectorySimilarityReportMessage.DIRECTORY_COUNT, arrayOf(directories.size)))
            appendLine(formatMessage(DirectorySimilarityReportMessage.PAIR_COUNT, arrayOf(pairs.size)))
            appendLine()

            if (pairs.isEmpty()) {
                appendLine(formatMessage(DirectorySimilarityReportMessage.NO_PAIRS, emptyArray()))
                return@buildString
            }

            pairs.forEachIndexed { index, pair ->
                appendLine(
                    formatMessage(
                        DirectorySimilarityReportMessage.PAIR_SIMILARITY,
                        arrayOf(
                            String.format(Locale.ROOT, "%03d", index + 1),
                            similarityPercent(pair.similarity)
                        )
                    )
                )
                appendLine(formatMessage(DirectorySimilarityReportMessage.NAME_A, arrayOf(pair.left.name)))
                appendLine(formatMessage(DirectorySimilarityReportMessage.PATH_A, arrayOf(pair.left.path)))
                appendLine(formatMessage(DirectorySimilarityReportMessage.NAME_B, arrayOf(pair.right.name)))
                appendLine(formatMessage(DirectorySimilarityReportMessage.PATH_B, arrayOf(pair.right.path)))
                appendLine()
            }
        }
    }

    private fun createReportFile(rootDirectory: File, timestamp: Date): File {
        val formattedTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(timestamp)
        var outputFile = File(rootDirectory, "$REPORT_PREFIX$formattedTimestamp$REPORT_EXTENSION")
        var index = 1
        while (outputFile.exists()) {
            outputFile = File(
                rootDirectory,
                "$REPORT_PREFIX${formattedTimestamp}_" +
                    "${String.format(Locale.ROOT, "%03d", index)}$REPORT_EXTENSION"
            )
            index++
        }
        return outputFile
    }

    private fun normalizeDirectoryName(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .filterNot { char -> char.isWhitespace() || char in IGNORED_CHARACTERS }
    }

    private fun directoryNameSimilarity(left: String, right: String): Double {
        if (left == right) {
            return 1.0
        }
        val maxLength = maxOf(left.length, right.length)
        if (maxLength <= 0) {
            return 0.0
        }
        val distance = levenshteinDistance(left, right)
        return (1.0 - (distance.toDouble() / maxLength.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) {
            return 0
        }
        if (left.isEmpty()) {
            return right.length
        }
        if (right.isEmpty()) {
            return left.length
        }

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (leftIndex in 1..left.length) {
            current[0] = leftIndex
            for (rightIndex in 1..right.length) {
                val cost = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
                current[rightIndex] = minOf(
                    current[rightIndex - 1] + 1,
                    previous[rightIndex] + 1,
                    previous[rightIndex - 1] + cost
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    private fun similarityPercent(value: Double): String {
        return String.format(Locale.getDefault(), "%.2f%%", value * 100.0)
    }

    private data class SimilarDirectoryInfo(
        val name: String,
        val path: String
    )

    private data class SimilarDirectoryNameCandidate(
        val directory: SimilarDirectoryInfo,
        val normalizedName: String
    )

    private data class SimilarDirectoryNamePair(
        val left: SimilarDirectoryInfo,
        val right: SimilarDirectoryInfo,
        val similarity: Double
    )

    private const val SIMILARITY_THRESHOLD = 0.78
    private const val PROGRESS_ROW_INTERVAL = 25
    private const val REPORT_PREFIX = "ComicLab_similar_directory_names_"
    private const val REPORT_EXTENSION = ".txt"
    private val IGNORED_CHARACTERS = setOf(
        '_',
        '-',
        '.',
        '·',
        '[',
        ']',
        '(',
        ')',
        '（',
        '）',
        '【',
        '】'
    )
}

internal enum class DirectorySimilarityReportMessage {
    TITLE,
    GENERATED_AT,
    ROOT_DIRECTORY,
    SIMILARITY_RULE,
    DIRECTORY_COUNT,
    PAIR_COUNT,
    NO_PAIRS,
    PAIR_SIMILARITY,
    NAME_A,
    PATH_A,
    NAME_B,
    PATH_B
}

internal data class DirectorySimilarityProgress(
    val stage: Stage,
    val completed: Int = 0,
    val total: Int = 0
) {
    enum class Stage {
        SCANNING_DIRECTORIES,
        COMPARING_DIRECTORY_NAMES
    }
}

internal data class DirectorySimilarityReport(
    val outputFile: File,
    val directoryCount: Int,
    val pairCount: Int
)

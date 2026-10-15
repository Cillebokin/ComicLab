package com.example.comiclab.core.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal object GitHubReleaseApi {

    fun fetchLatestReleaseTag(): String {
        val connection = URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECTION_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", USER_AGENT)

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("GitHub Release API returned HTTP $responseCode")
            }
            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            parseLatestReleaseTag(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    fun parseLatestReleaseTag(responseBody: String): String {
        val tagName = JSONObject(responseBody).optString("tag_name").trim()
        require(tagName.isNotEmpty()) { "GitHub Release response has no tag_name" }
        return tagName
    }

    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/Cillebokin/ComicLab/releases/latest"
    private const val CONNECTION_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 10_000
    private const val USER_AGENT = "ComicLab-Android"
}

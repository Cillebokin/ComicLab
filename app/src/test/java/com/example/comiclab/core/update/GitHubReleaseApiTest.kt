package com.example.comiclab.core.update

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GitHubReleaseApiTest {

    @Test
    fun parsesLatestReleaseTagFromGitHubResponse() {
        assertEquals(
            "V1.0.9.1006_Base",
            GitHubReleaseApi.parseLatestReleaseTag(
                """{"tag_name":"V1.0.9.1006_Base","html_url":"https://github.com/Cillebokin/ComicLab/releases/tag/V1.0.9.1006_Base"}"""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsResponseWithoutReleaseTag() {
        GitHubReleaseApi.parseLatestReleaseTag("""{"name":"ComicLab release"}""")
    }
}

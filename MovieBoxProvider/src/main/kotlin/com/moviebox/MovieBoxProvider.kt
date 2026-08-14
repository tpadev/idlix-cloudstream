package com.moviebox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://moviebox-api-theta.vercel.app"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // private val fastApiUrl = "https://moviebox-fastapi.vercel.app"
    private val fastApiUrl = "https://fmoviesunblocked.net"
    override val mainPage = mainPageOf(
        "$mainUrl/api/homepage" to "Home",
        "$mainUrl/api/trending" to "Trending",
    )

    /* ---------- Homepage ---------- */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val json = fetchJson(request.data)
        val data = json["data"] as? Map<*, *>
            ?: return newHomePageResponse(request.name, emptyList())

        val items = if (request.data.contains("/trending")) {
            (data["subjectList"] as? List<*>)
                ?.mapNotNull { it.toSearchResponse() }
                ?: emptyList()
        } else {
            val operatingList = data["operatingList"] as? List<*>
                ?: return newHomePageResponse(request.name, emptyList())
            operatingList.flatMap { section ->
                val m = section as? Map<*, *> ?: return@flatMap emptyList()
                val subjects = m["subjects"] as? List<*>
                    ?: return@flatMap emptyList()
                subjects.mapNotNull { it.toSearchResponse() }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    /* ---------- Search (fastAPI) ---------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = fetchJson("$fastApiUrl/search?query=$encoded&limit=10")
        val results = json["results"] as? List<*> ?: return emptyList()
        return results.mapNotNull { it.toFastSearchResponse() }
    }

    /* ---------- Detail (old API) ---------- */

    override suspend fun load(url: String): LoadResponse {
        val subjectId = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
        val json = fetchJson("$mainUrl/api/info/$subjectId")
        val data = json["data"] as? Map<*, *>
            ?: throw ErrorLoadingException("No data in response")
        val subject = data["subject"] as? Map<*, *>
            ?: throw ErrorLoadingException("No subject in data")

        val subjectType = (subject["subjectType"] as? Int) ?: 1
        val title = (subject["title"] as? String) ?: "Unknown"
        val resolvedSubjectId = (subject["subjectId"] as? String) ?: subjectId
        val coverUrl = (subject["cover"] as? Map<*, *>)?.get("url") as? String
        val description = subject["description"] as? String
        val releaseDate = subject["releaseDate"] as? String
        val year = releaseDate?.substringBefore("-")?.toIntOrNull()
        val genre = subject["genre"] as? String
        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()
        val rating = subject["imdbRatingValue"] as? String
        val score = rating?.let { Score.from10(it) }
        val duration = (subject["duration"] as? Number)?.toLong()

        val stars = (data["stars"] as? List<*>)?.mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val avatar = m["avatarUrl"] as? String
            Actor(name, avatar)
        } ?: emptyList()

        val isSeries = subjectType == 2

        if (isSeries) {
            val resource = data["resource"] as? Map<*, *>
            val seasons = resource?.get("seasons") as? List<*>
            val episodes = mutableListOf<Episode>()

            seasons?.forEach { s ->
                val m = s as? Map<*, *> ?: return@forEach
                val se = (m["se"] as? Int) ?: return@forEach
                val maxEp = (m["maxEp"] as? Int) ?: return@forEach
                for (ep in 1..maxEp) {
                    val payload = MovieBoxLoadData(
                        subjectId = resolvedSubjectId,
                        season = se,
                        episode = ep,
                    ).toJson()
                    episodes.add(newEpisode(payload) {
                        this.season = se
                        this.episode = ep
                        this.name = "E$ep"
                    })
                }
            }

            return newTvSeriesLoadResponse(title, resolvedSubjectId, TvType.TvSeries, episodes) {
                this.posterUrl = coverUrl
                this.backgroundPosterUrl = coverUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration?.toInt()
                addActors(stars)
            }
        } else {
            val payload = MovieBoxLoadData(subjectId = resolvedSubjectId).toJson()
            return newMovieLoadResponse(title, resolvedSubjectId, TvType.Movie, payload) {
                this.posterUrl = coverUrl
                this.backgroundPosterUrl = coverUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration?.toInt()
                addActors(stars)
            }
        }
    }

    /* ---------- Links (fastAPI) ---------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<MovieBoxLoadData>(data) }
            .getOrNull() ?: return false

        val json = fetchJson("$fastApiUrl/get_download_links?subject_id=${parsed.subjectId}")
        val links = json["download_links"] as? List<*> ?: return false

        var emitted = false
        var subsLoaded = false

        links.forEach { dl ->
            val m = dl as? Map<*, *> ?: return@forEach
            val dlSeason = (m["season"] as? Int) ?: 0
            val dlEpisode = (m["episode"] as? Int) ?: 0

            // Filter by requested season/episode for TV, accept all for movie
            if (parsed.season != null) {
                if (dlSeason != parsed.season || dlEpisode != parsed.episode) return@forEach
            }

            val dlUrl = (m["url"] as? String) ?: return@forEach
            val resolution = (m["resolution"] as? Int)
            val size = (m["size"] as? String)
            val resourceId = (m["resource_id"] as? String)

            emitted = true

            callback(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append(resolution?.let { "${it}p" } ?: "Stream")
                        if (size != null) {
                            val mb = size.toLongOrNull()?.let { it / 1_000_000 }
                            if (mb != null) append(" ($mb MB)")
                        }
                    },
                    url = dlUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = resolution ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )

            // Load subtitles once via /get_subtitles
            if (!subsLoaded && resourceId != null) {
                subsLoaded = true
                val subJson = fetchJson("$fastApiUrl/get_subtitles?subject_id=${parsed.subjectId}&resource_id=$resourceId")
                val allSubs = subJson["all_subtitles"] as? List<*>
                allSubs?.forEach { sub ->
                    val sm = sub as? Map<*, *> ?: return@forEach
                    val subUrl = (sm["url"] as? String) ?: return@forEach
                    val label = (sm["language_name"] as? String)
                        ?: (sm["language_code"] as? String)
                        ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(label, subUrl))
                }
            }
        }

        return emitted
    }

    /* ---------- Helpers ---------- */

    /** Fetch JSON and parse into a dynamic map. */
    private suspend fun fetchJson(url: String): Map<String, Any?> {
        return runCatching {
            val text = app.get(url, timeout = 30L).text
            parseJson<Map<String, Any?>>(text)
        }.getOrNull() ?: emptyMap()
    }

    /* ---------- Mappers ---------- */

    @Suppress("UNCHECKED_CAST")
    private fun Any?.toSearchResponse(): SearchResponse? {
        val m = this as? Map<*, *> ?: return null
        val subjectId = (m["subjectId"] as? String) ?: return null
        val title = (m["title"] as? String) ?: return null
        val subjectType = (m["subjectType"] as? Int) ?: 1
        val cover = m["cover"] as? Map<*, *>
        val coverUrl = cover?.get("url") as? String
        val releaseDate = m["releaseDate"] as? String
        val year = releaseDate?.substringBefore("-")?.toIntOrNull()

        val apiUrl = "$mainUrl/api/info/$subjectId"
        val isSeries = subjectType == 2

        return if (isSeries) {
            newTvSeriesSearchResponse(title, apiUrl, TvType.TvSeries) {
                this.posterUrl = coverUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, apiUrl, TvType.Movie) {
                this.posterUrl = coverUrl
                this.year = year
            }
        }
    }

    /** Mapper for fastAPI /search results. */
    @Suppress("UNCHECKED_CAST")
    private fun Any?.toFastSearchResponse(): SearchResponse? {
        val m = this as? Map<*, *> ?: return null
        val subjectId = (m["subject_id"] as? String) ?: return null
        val title = (m["title"] as? String) ?: return null
        val type = (m["type"] as? String) ?: "movie"
        val poster = (m["poster"] as? String)
        val yearStr = (m["year"] as? String)
        val year = yearStr?.toIntOrNull()

        val apiUrl = "$mainUrl/api/info/$subjectId"
        val isSeries = type == "series"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, apiUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, apiUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }
}

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mrpoodles.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Backend research preparation only; later phases map verified evidence into feature results. */
@Serializable data class ResearchRestriction(val name: String, val kind: String, val aliases: String = "", val notes: String = "")
@Serializable data class ResearchConstraints(
    val restrictions: List<ResearchRestriction> = emptyList(), val diet: String = "", val equipment: List<String> = emptyList(),
    val experience: String = "", val injuries: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val durationMinutes: Int? = null
)
@Serializable data class ResearchRequest(
    val requestId: String, val profileRevision: Int, val task: String, val subject: String,
    val brand: String = "", val variant: String = "", val country: String = "",
    val constraints: ResearchConstraints = ResearchConstraints(), val allowExternalModel: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val url: String? = null
)
@Serializable data class ResearchClaim(val text: String, val basis: String, val evidence: List<EvidenceReference>)
@Serializable data class ResearchAnswer(val message: String, val claims: List<ResearchClaim>, val uncertainties: List<String>)
@Serializable data class ResearchVideo(val url: String, val title: String, val channel: String,
    val match: String, val verificationNote: String)
@Serializable data class ResearchResponse(
    val apiVersion: Int, val requestId: String, val profileRevision: Int, val snapshot: RetrievalSnapshot,
    val video: ResearchVideo? = null, val answer: ResearchAnswer, val provider: String,
    val limitations: List<String> = emptyList()
) {
    fun validate(request: ResearchRequest): ResearchResponse {
        require(apiVersion == 2 && requestId == request.requestId && profileRevision == request.profileRevision)
        require(snapshot.requestId == requestId && snapshot.id.isNotBlank())
        require(snapshot.sources.size in 1..3 && snapshot.sources.map { it.id }.distinct().size == snapshot.sources.size)
        snapshot.sources.forEach { source ->
            require(publicResearchUrl(source.url) && source.id.isNotBlank() && source.excerpt.length in 100..12000)
        }
        require(answer.message.length in 1..800 && answer.claims.size <= 8 && answer.uncertainties.size <= 10)
        answer.claims.forEach { claim ->
            require(claim.text.isNotBlank() && claim.text.length <= 600)
            when (claim.basis) {
                "source" -> {
                    val reference = claim.evidence.single()
                    val source = snapshot.sources.single { it.id == reference.sourceId }
                    require(reference.snapshotId == snapshot.id && reference.excerpt == claim.text && source.excerpt.contains(reference.excerpt))
                }
                "ai_adjustment" -> require(claim.evidence.isEmpty())
                else -> error("Unknown claim basis")
            }
        }
        video?.let {
            require(Regex("https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}").matches(it.url))
            require(it.match == "TECHNIQUE_REFERENCE" && it.title.isNotBlank() && it.channel.isNotBlank())
        }
        return this
    }
}

internal fun publicResearchUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
        !Regex("(?i)(?:^|[&?])[^=&]*(?:token|secret|password|api.?key|signature|credential)[^=&]*=").containsMatchIn(uri.rawQuery.orEmpty()) &&
        uri.host?.let { Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}").matches(it) &&
            !Regex("(?:^|\\.)(localhost|local|internal|lan|home|test|invalid|onion)$").containsMatchIn(it) } == true
}.getOrDefault(false)

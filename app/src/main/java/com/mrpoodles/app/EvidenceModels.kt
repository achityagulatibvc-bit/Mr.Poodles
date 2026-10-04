package com.mrpoodles.app

import kotlinx.serialization.Serializable
import java.util.UUID

/** Local schema only. Retrieval and source verification are implemented in later phases. */
@Serializable data class UserAssertion(val text: String, val messageId: String? = null)
@Serializable data class RetrievedSource(
    val id: String, val url: String, val title: String, val retrievedAt: String,
    val author: String? = null, val publisher: String? = null, val publishedAt: String? = null,
    val excerpt: String = "", val contentHash: String? = null,
    val kind: String = "legacy", val cached: Boolean = false, val completeness: String = "unverified"
)
@Serializable data class RetrievalSnapshot(
    val id: String, val requestId: String, val sources: List<RetrievedSource> = emptyList()
)
@Serializable data class EvidenceReference(val snapshotId: String, val sourceId: String, val excerpt: String)
@Serializable data class ResultOrigin(
    val requestId: String, val profileRevision: Int,
    val assertions: List<UserAssertion> = emptyList(), val retrieval: RetrievalSnapshot? = null
)
@Serializable data class NamedIngredient(
    val key: String, val name: String, val amount: Double? = null, val unit: String? = null,
    val preparation: String = "", val evidence: List<EvidenceReference> = emptyList(),
    val amountMax: Double? = null
)
@Serializable data class Substitution(
    val original: NamedIngredient, val replacement: NamedIngredient, val reason: String,
    val evidence: List<EvidenceReference> = emptyList()
)
@Serializable data class RecipeAdaptation(
    val explanation: String, val substitutions: List<Substitution> = emptyList(),
    val instructionChanges: List<String> = emptyList()
)
@Serializable data class Portion(
    val amount: Double = 1.0, val unit: String = "serving", val description: String = "",
    val assumed: Boolean = false, val grams: Double? = null
)
@Serializable enum class EstimateStatus { UNKNOWN, USER_REPORTED, ESTIMATED, PUBLISHED }
@Serializable data class NutritionEstimate(
    val kcal: Double? = null, val protein: Double? = null, val carbs: Double? = null,
    val fat: Double? = null, val status: EstimateStatus = EstimateStatus.UNKNOWN,
    val basis: Portion = Portion(), val evidence: List<EvidenceReference> = emptyList(),
    val explanation: String = "", val kcalLow: Double? = null, val kcalHigh: Double? = null
)
@Serializable data class SourcedRecipe(
    val origin: ResultOrigin, val ingredients: List<NamedIngredient>, val instructions: List<String>,
    val servings: Double, val activeMinutes: Int? = null, val cookingMinutes: Int? = null,
    val waitingMinutes: Int? = null, val adaptation: RecipeAdaptation? = null,
    val nutrition: NutritionEstimate = NutritionEstimate(),
    val originalSourceId: String? = null, val originalServings: Double? = null,
    val instructionEvidence: List<EvidenceReference> = emptyList(),
    val metadataEvidence: List<EvidenceReference> = emptyList(), val excludedIngredients: List<String> = emptyList(),
    val waitingMinutesMax: Int? = null, val originalServingDescription: String? = null,
    val servingBasisNote: String? = null
)
@Serializable enum class VideoMatch { TECHNIQUE_REFERENCE, FOLLOW_ALONG }
@Serializable data class VideoReference(
    val url: String, val title: String, val channel: String, val evidence: EvidenceReference,
    val match: VideoMatch = VideoMatch.TECHNIQUE_REFERENCE, val verificationNote: String = "Metadata only"
)
@Serializable data class WorkoutMovement(
    val name: String, val instructions: String, val sets: Int? = null, val reps: String? = null,
    val seconds: Int? = null, val restSeconds: Int? = null, val formCues: List<String> = emptyList(),
    val easierAlternative: String? = null, val evidence: List<EvidenceReference> = emptyList(),
    val aiAdjustment: String? = null, val publishedDose: String? = null
)
/** AI programming limits, never a transcription of the article's duration or repetition cadence. */
@Serializable data class WorkoutTimeBlock(
    val movementIndex: Int, val workSeconds: Int, val restSeconds: Int, val transitionSeconds: Int,
    val setCap: Int, val repCap: Int? = null, val doseSecondsCap: Int? = null
)
@Serializable data class WorkoutTimeStage(
    val phase: String, val seconds: Int, val blocks: List<WorkoutTimeBlock>, val recoverySeconds: Int
)
@Serializable data class WorkoutTimePlan(val totalSeconds: Int, val stages: List<WorkoutTimeStage>, val version: Int = 1)
@Serializable data class SourcedWorkout(
    val origin: ResultOrigin, val warmUp: List<WorkoutMovement>, val movements: List<WorkoutMovement>,
    val cooldown: List<WorkoutMovement>, val video: VideoReference? = null,
    val durationMinutes: Int? = null, val programmingNotes: List<String> = emptyList(),
    val noEquipment: Boolean = false, val quiet: Boolean = false, val noJumping: Boolean = false,
    val avoidPushUps: Boolean = false, val reducedDose: Boolean = false,
    val subject: String = "", val timePlan: WorkoutTimePlan? = null, val wallAvailable: Boolean = false
)
@Serializable data class WorkoutCompletion(val workoutId: String, val date: String, val id: String = UUID.randomUUID().toString())
@Serializable enum class AssessmentOutcome { AVOID, NO_LISTED_CONFLICT_FOUND, NEED_MORE_INFORMATION }
@Serializable data class FoodAssessment(
    val origin: ResultOrigin, val outcome: AssessmentOutcome = AssessmentOutcome.NEED_MORE_INFORMATION,
    val product: String, val brand: String? = null, val variant: String? = null, val country: String? = null,
    val labelVersion: String? = null, val findings: List<String> = emptyList(),
    val evidence: List<EvidenceReference> = emptyList(), val uncertainties: List<String> = emptyList()
)

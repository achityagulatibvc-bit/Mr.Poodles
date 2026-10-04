package com.mrpoodles.app

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class WorkoutChatReply(val message: String, val workout: Workout? = null, val subject: String = "")

/** Exact approved pages and reviewed text layouts only; this does not relax the generic parser. */
internal object NhsWorkoutArticles {
    const val WARMUP = "https://www.nhs.uk/live-well/exercise/how-to-warm-up-before-exercising/"
    const val STRENGTH = "https://www.nhs.uk/live-well/exercise/strength-exercises/"
    const val COOLDOWN = "https://www.nhs.uk/live-well/exercise/how-to-stretch-after-exercising/"
    val urls = listOf(WARMUP, STRENGTH, COOLDOWN)
    const val NOTE = "AI programming selection from three NHS articles, not an NHS-authored combined routine. Reserve at least 6 minutes for warm-up and 5 minutes for cooldown; the original guidance is retained. Without a requested duration, the displayed 15-minute schedule is an AI default. A wall and clear floor space are needed. These selected movements require no chair or weights. Quietness is not guaranteed; move comfortably and stop if the room is unsuitable."
    private val names = listOf("March on the spot", "Heel digs", "Knee lifts", "Shoulder rolls", "Knee bends", "Video", "More in",
        "Sit-to-stand", "Mini-squats", "Calf raises", "Sideways leg lift", "Leg extension", "Wall press-up", "Biceps curls",
        "Buttock stretch", "Hamstring stretch", "Inner thigh stretch", "Calf stretch", "Thigh stretch", "Support links")
    private val headings = Regex("(?m)^[\\t ]*(?:#{1,6}[\\t ]+)?(?:\\*\\*)?(${names.joinToString("|") { Regex.escape(it) }})(?=[: \\u00a0–—-]|$)[^\\r\\n]*")
    fun recognized(source: RetrievedSource) = source.kind == "workout" && source.url in urls
    fun guidance(source: RetrievedSource): Boolean {
        val text = FoodRules.normalize(source.excerpt)
        return recognized(source) && when (source.url) {
            WARMUP -> text.contains("this warm up routine should take at least 6 minutes")
            STRENGTH -> text.contains("these strength exercises are gentle and easy to follow") && text.contains("build up slowly")
            COOLDOWN -> text.contains("use this routine to cool down after a workout") && text.contains("these gentle stretches should take about 5 minutes")
            else -> false
        }
    }
    fun completeSet(sources: List<RetrievedSource>) = urls.all { url -> sources.singleOrNull { it.url == url }?.let(::guidance) == true }
    fun supportedSubject(subject: String): Boolean {
        val words = FoodRules.normalize(subject).split(' ')
        val allowed = setOf("i", "a", "an", "the", "to", "want", "need", "find", "new", "me", "please", "for", "with", "without", "no", "only",
            "minutes", "minute", "min", "mins", "at", "home", "in", "room", "small", "quiet", "hostel", "and", "jumping", "equipment", "beginner", "beginners",
            "workout", "workouts", "routine", "session", "exercise", "exercises", "bodyweight", "calisthenics", "use", "have", "wall", "available", "strength", "push", "ups", "press", "up")
        return words.all { it in allowed || it.toIntOrNull() != null || it.matches(Regex("\\d+(?:min|mins|minutes)")) }
    }
    fun supplementaryCandidate(source: RetrievedSource): Boolean = recognized(source) || runCatching {
        val uri = java.net.URI(source.url)
        source.kind == "workout" && uri.host == "www.acefitness.org" &&
            (uri.path.startsWith("/resources/") || uri.path.startsWith("/education-and-resources/")) &&
            Regex("(?i)workout|exercise|fitness").containsMatchIn(source.title)
    }.getOrDefault(false)

    fun blocks(source: RetrievedSource, snapshot: RetrievalSnapshot): List<PublishedWorkoutParser.Block> {
        if (!guidance(source) || source.excerpt.length !in 100..11999) return emptyList()
        val matches = headings.findAll(source.excerpt).toList()
        return matches.mapIndexedNotNull { index, match ->
            val name = match.groupValues[1]
            val phase = when (source.url) {
                WARMUP -> if (name in listOf("March on the spot", "Knee bends")) "warmup" else return@mapIndexedNotNull null
                STRENGTH -> if (name == "Wall press-up") "main" else return@mapIndexedNotNull null
                COOLDOWN -> if (name in listOf("Buttock stretch", "Hamstring stretch", "Inner thigh stretch")) "cooldown" else return@mapIndexedNotNull null
                else -> return@mapIndexedNotNull null
            }
            val end = matches.getOrNull(index + 1)?.range?.first ?: source.excerpt.length
            val raw = source.excerpt.substring(match.range.first, end).trim()
            val body = source.excerpt.substring(match.range.last + 1, end).trim()
                .replace(Regex("(?m)^[\\t ]*!\\[[^\\n]*\\]\\([^\\n]*\\)[\\t ]*\\r?$"), "").trim()
            if (body.length !in 65..2000 || !body.endsWith('.') || body.contains("...") || body.contains('…') ||
                Regex("(?i)ignore (?:previous|prior)|system prompt|assistant:|guaranteed|cure|push through pain").containsMatchIn(raw)) return@mapIndexedNotNull null
            val normalized = FoodRules.normalize(body)
            val required = when (name) {
                "March on the spot" -> listOf("start off marching on the spot and then march forwards and backwards", "pump your arms up and down in rhythm with your steps", "keeping the elbows bent and the fists soft")
                "Knee bends" -> listOf("stand with your feet shoulder width apart and your hands stretched out", "lower yourself no more than 10cm by bending your knees", "come up and repeat")
                "Wall press-up" -> listOf("stand at arm s length from the wall", "place your hands flat against the wall at chest level", "fingers pointing upwards", "with your back straight slowly bend your arms keeping your elbows by your side", "aim to close the gap between you and the wall as much as you can", "slowly return to the start")
                "Buttock stretch" -> listOf("lie on your back and bring your knees up to your chest", "cross your right leg over your left thigh", "grasp the back of your left thigh with both hands", "pull your left leg towards your chest", "repeat with the opposite leg")
                "Hamstring stretch" -> listOf("lie on your back and raise your right leg", "hold your right leg with both hands below your knee", "keeping your left leg bent with your foot on the floor pull your right leg towards you keeping it straight", "repeat with the opposite leg")
                else -> listOf("sit down with your back straight and your legs bent", "put the soles of your feet together", "holding on to your feet try to lower your knees towards the floor")
            }
            if (required.any { !normalized.contains(it) }) return@mapIndexedNotNull null
            val heading = match.value.trim().removePrefix("## ")
            val headingText = FoodRules.normalize(heading)
            var sets: Int? = null
            var reps: String? = null
            var seconds: Int? = null
            val dose: String
            when (name) {
                "March on the spot" -> {
                    val amount = Regex("keep going for (\\d+) minutes?").find(headingText)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
                    if (amount !in 1..5) return@mapIndexedNotNull null
                    seconds = amount * 60; dose = heading
                }
                "Knee bends" -> {
                    reps = Regex("knee bends (\\d+) repetitions").find(headingText)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                    dose = heading
                }
                "Wall press-up" -> {
                    val found = Regex("(?i)Attempt[ \\u00a0]+(\\d+)[ \\u00a0]+sets of[ \\u00a0]+(\\d+)[ \\u00a0]+to[ \\u00a0]+(\\d+)[ \\u00a0]+repetitions\\.").find(body) ?: return@mapIndexedNotNull null
                    sets = found.groupValues[1].toIntOrNull(); reps = "${found.groupValues[2]} to ${found.groupValues[3]}"; dose = found.value
                }
                else -> {
                    val range = Regex("hold for (\\d+) to (\\d+) seconds").find(headingText) ?: return@mapIndexedNotNull null
                    val low = range.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
                    val high = range.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
                    if (low !in 1..high || high > 300) return@mapIndexedNotNull null
                    seconds = high; dose = heading // Numeric value is the upper cap; the published range stays explicit.
                }
            }
            PublishedWorkoutParser.Block(phase, WorkoutMovement(name, body, sets, reps, seconds,
                evidence = listOf(EvidenceReference(snapshot.id, source.id, raw)), publishedDose = dose))
        }
    }
}

/** Only explicit, complete exercise blocks are parsed. The model's prose is never exercise evidence. */
internal object PublishedWorkoutParser {
    data class Block(val phase: String, val movement: WorkoutMovement)
    private val heading = Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]+([^\\n]+)|\\*\\*([^\\n*]+)\\*\\*)[ \\t]*$")
    private val action = Regex("(?i)\\b(?:stand|sit|lie|place|keep|bend|lift|lower|raise|step|reach|hold|return|move|walk|rotate|press|push|pull|extend|relax|breathe|lean|roll|straighten|squeeze|stretch)\\b")
    internal fun phase(name: String): String? = when (FoodRules.normalize(name)) {
        "warm up", "warmup", "warm up exercises" -> "warmup"
        "workout", "main workout", "exercises", "main exercises", "movements", "strength exercises" -> "main"
        "cool down", "cooldown", "cool down exercises", "cooldown exercises" -> "cooldown"
        else -> null
    }
    fun blocks(source: RetrievedSource, snapshot: RetrievalSnapshot): List<Block> {
        if (source.kind != "workout" || !publicResearchUrl(source.url) || source.excerpt.length !in 100..11999) return emptyList()
        if (NhsWorkoutArticles.recognized(source)) return NhsWorkoutArticles.blocks(source, snapshot)
        val headings = heading.findAll(source.excerpt).toList()
        var section: String? = null
        var sectionDepth = 0
        return headings.mapIndexedNotNull { index, match ->
            val depth = match.value.trimStart().takeWhile { it == '#' }.length
            val name = match.groupValues[1].ifBlank { match.groupValues[2] }.trim().trimEnd('#').trim()
                .replace(Regex("^\\d+[.)]\\s*"), "")
            phase(name)?.let { section = it; sectionDepth = depth; return@mapIndexedNotNull null }
            if (depth > 0 && sectionDepth > 0 && depth <= sectionDepth) section = null
            if (name.length !in 3..90) return@mapIndexedNotNull null
            val end = headings.getOrNull(index + 1)?.range?.first ?: source.excerpt.length
            val body = source.excerpt.substring(match.range.last + 1, end).trim()
            val block = source.excerpt.substring(match.range.first, end).trim()
            // A heading and a snippet are not instructions. Require a finished action sequence,
            // an explicit dose, and no truncation or embedded instructions to the assistant.
            if (body.length !in 65..2400 || !body.endsWith('.') || body.contains("...") || body.contains('…') ||
                action.findAll(body).count() < 3 || body.count { it in ".!?" } < 2 ||
                Regex("(?i)ignore (?:previous|prior)|system prompt|assistant:|guaranteed|cure|rehabilitat|pain.free|push through pain").containsMatchIn(block)) return@mapIndexedNotNull null
            val prescription = body.split(Regex("(?im)^[ \\t]*(?:form|form cue|tip|easier|easier alternative|modification):"), limit = 2).first()
                .replace(Regex("(?i)\\brest\\b[^.!?\\n]*(?:[.!?]|$)"), "")
            if (Regex("(?i)\\b\\d+\\s*[-–]\\s*\\d+\\s*sets?\\b").containsMatchIn(prescription)) return@mapIndexedNotNull null
            val secondsValues = Regex("(?i)\\b(?:for|hold(?: for)?|duration:)\\s+(\\d{1,3})\\s*(?:seconds?|secs?)\\b").findAll(prescription).map { it.groupValues[1] }.distinct().toList()
            val repValues = Regex("(?i)\\b(\\d{1,2}(?:\\s*[-–]\\s*\\d{1,2})?)\\s*(?:reps?|repetitions?)\\b").findAll(prescription).map { it.groupValues[1] }.distinct().toList()
            val setValues = Regex("(?i)\\b(\\d{1,2})\\s+sets?\\b").findAll(prescription).map { it.groupValues[1] }.distinct().toList()
            if (secondsValues.size > 1 || repValues.size > 1 || setValues.size > 1) return@mapIndexedNotNull null
            val seconds = secondsValues.singleOrNull()?.toIntOrNull()
            val reps = repValues.singleOrNull()
            if (seconds == null && reps == null) return@mapIndexedNotNull null
            val sets = setValues.singleOrNull()?.toIntOrNull()
            val rest = Regex("(?i)\\brest(?: for|:)?\\s+(\\d{1,3})\\s*(?:seconds?|secs?)\\b").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val form = Regex("(?im)^[ \\t]*(?:form|form cue|tip):[ \\t]*(.+)$").findAll(body).map { it.groupValues[1].trim() }.toList()
            val easier = Regex("(?im)^[ \\t]*(?:easier|easier alternative|modification):[ \\t]*(.+)$").find(body)?.groupValues?.get(1)?.trim()
            val phase = section ?: return@mapIndexedNotNull null
            Block(phase, WorkoutMovement(name, body, sets, reps, seconds, rest, form, easier,
                listOf(EvidenceReference(snapshot.id, source.id, block))))
        }
    }
}

internal object WorkoutEdits {
    const val SELECTION_NOTE = "AI programming selection: only complete, supported exercise blocks are included. This assembled session is not presented as the author's original full routine."
    const val DOSE_NOTE = "AI programming adjustment: use the reduced limits shown, at most 1 set, 5 repetitions or 20 seconds for main exercises. Warm-up and cooldown keep their separately checked limits. The original article instructions remain below; the reduced dose overrides their dose. Rest longer or stop whenever needed."
    const val REMOVE_NOTE = "AI programming adjustment: push-up movements were omitted, not replaced with an invented exercise."
    fun duration(text: String): Int? = Regex("(?i)(?:\\b|(?<=only))(\\d{1,3})\\s*(?:minutes?|mins?|min)\\b").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    fun edit(text: String) = Regex("(?i)^(?:please )?(?:use a wall|i have a wall|no wall|only(?=\\s|\\d)|make (?:it |this )?easier|no jumping|quiet|hostel|push.?ups? (?:are |is )?(?:difficult|hard)|without |no equipment|\\d+\\s*min)").containsMatchIn(text.trim())
    fun easier(text: String) = Regex("(?i)\\beasier\\b").containsMatchIn(text)
    fun pushups(text: String) = Regex("(?i)(?:push|press)[ -]?ups?").containsMatchIn(text)
    fun wall(text: String): Boolean? = when {
        Regex("(?i)\\b(?:no wall|without (?:a )?wall|wall (?:unavailable|not available))\\b").containsMatchIn(text) -> false
        Regex("(?i)\\b(?:use (?:a |the )?wall|have (?:a |the )?wall|wall available|wall (?:push|press)[ -]?ups?)\\b").containsMatchIn(text) -> true
        else -> null
    }
    fun wallAvailable(profile: Profile, assertions: List<UserAssertion>): Boolean = assertions.asReversed().firstNotNullOfOrNull { wall(it.text) }
        ?: wall(profile.environment().workoutSpace) ?: wall(profile.environment().workoutEquipment)
        ?: FoodRules.contains(profile.environment().workoutEquipment, "wall")
    fun noEquipment(text: String) = Regex("(?i)\\b(?:no equipment|without equipment|bodyweight|calisthenics)\\b").containsMatchIn(text)
    fun quiet(text: String) = Regex("(?i)\\b(?:quiet|hostel|small room)\\b").containsMatchIn(text)
    fun noJumping(text: String) = Regex("(?i)\\b(?:no jumping|without jumping|low impact|low-impact)\\b").containsMatchIn(text)
    fun supportedEdit(text: String): Boolean {
        val remaining = text.lowercase().replace(Regex("(?:\\b|(?<=only))\\d{1,3}\\s*(?:minutes?|mins?|min)\\b"), "")
            .replace(Regex("push[ -]?ups? (?:are |is )?(?:difficult|hard)|(?:no|without) push[ -]?ups?"), "")
            .replace(Regex("\\b(?:use a wall|i have a wall|no wall|without a wall|please|only|make|it|this|easier|no jumping|without jumping|no equipment|without equipment|quiet|hostel|small room|room|and)\\b|[\\s,.!?;]+"), "")
        return remaining.isEmpty()
    }
    fun relevant(source: RetrievedSource, subject: String): Boolean {
        val ignored = setOf("i", "am", "a", "an", "the", "want", "need", "find", "me", "my", "please", "for", "with", "without", "no", "only", "minutes", "minute", "min", "mins", "at", "home", "in", "room", "small", "quiet", "hostel", "and", "jumping", "equipment", "beginner", "beginners", "workout", "workouts", "routine", "session", "exercise", "exercises", "bodyweight", "calisthenics")
        val tokens = FoodRules.normalize(subject.replace(Regex("(?i)\\b\\d{1,3}\\s*(?:minutes?|mins?|min)\\b"), "")).split(' ').filter { it.isNotBlank() && it !in ignored && it.toIntOrNull() == null }
        return Regex("(?i)workout|exercises|calisthenics|fitness|movement").containsMatchIn(source.title) &&
            tokens.all { FoodRules.contains(source.title + " " + source.excerpt, it) }
    }
    fun dose(value: WorkoutMovement): WorkoutMovement = if (value.publishedDose != null && value.name == "March on the spot") value else value.copy(sets = value.sets?.coerceAtMost(1),
        reps = value.reps?.let { Regex("\\d+").find(it)?.value?.toIntOrNull()?.coerceAtMost(5)?.toString() },
        seconds = value.seconds?.coerceAtMost(20), aiAdjustment = DOSE_NOTE)
    fun budgetNote(minutes: Int) = "AI programming adjustment: a bounded $minutes-minute schedule, not an article-verified session length. Use the scheduled exercise caps instead of the full article sets/reps. Stop each exercise at its time or dose cap, whichever comes first. Keep the reserved cooldown; unused time is recovery, not extra repetitions. Finish early if needed."
}

/** A deterministic upper-bound schedule. No repetition-to-seconds conversion is used. */
internal object WorkoutProgramming {
    fun checked(value: SourcedWorkout): WorkoutTimePlan? {
        val minutes = value.durationMinutes ?: return null
        val snapshot = value.origin.retrieval ?: return null
        if (snapshot.requestId != value.origin.requestId) return null
        val published = snapshot.sources.flatMap { PublishedWorkoutParser.blocks(it, snapshot) }
        fun originals(phase: String, movements: List<WorkoutMovement>): List<WorkoutMovement>? {
            return movements.map { movement ->
                val original = published.singleOrNull { it.phase == phase && it.movement.evidence == movement.evidence }?.movement ?: return null
                if (movement != (if (value.reducedDose) WorkoutEdits.dose(original) else original)) return null
                original
            }
        }
        val expected = build(originals("warmup", value.warmUp) ?: return null, originals("main", value.movements) ?: return null,
            originals("cooldown", value.cooldown) ?: return null, minutes, value.reducedDose) ?: return null
        return value.timePlan?.takeIf { it == expected }
    }

    fun validDose(movement: WorkoutMovement): Boolean {
        val reps = movement.reps?.let { value ->
            if (!Regex("\\d{1,2}(?:\\s*(?:[-–]|to)\\s*\\d{1,2})?").matches(value)) return false
            Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
        }
        return (movement.seconds != null || reps != null) &&
            movement.sets?.let { it in 1..6 } != false && movement.seconds?.let { it in 1..300 } != false &&
            movement.restSeconds?.let { it in 0..300 } != false &&
            (reps == null || reps.all { it in 1..50 } && reps.first() <= reps.last())
    }

    fun build(warmUp: List<WorkoutMovement>, movements: List<WorkoutMovement>, cooldown: List<WorkoutMovement>,
        minutes: Int, easier: Boolean): WorkoutTimePlan? {
        if (minutes !in 5..60 || (warmUp + movements + cooldown).size !in 3..18 ||
            (warmUp + movements + cooldown).any { !validDose(it) }) return null
        val total = minutes * 60
        val bookend = (total / 5).coerceAtMost(180)
        val nhs = warmUp.any { it.publishedDose != null }
        val warmLimit = if (nhs) 360 else bookend
        val coolLimit = if (nhs) 300 else bookend
        if (total - warmLimit - coolLimit < 50) return null
        val stages = listOf(Triple("warmup", warmLimit, warmUp), Triple("main", total - warmLimit - coolLimit, movements),
            Triple("cooldown", coolLimit, cooldown)).map { (phase, limit, candidates) ->
            if (candidates.isEmpty()) return null
            var available = limit
            val blocks = candidates.mapIndexedNotNull { index, movement ->
                // Never shorten a published rest to squeeze in more activity. Unknown rest gets
                // an explicitly AI-programmed 30-second allowance, not a fabricated article fact.
                val rest = movement.restSeconds ?: 30
                val transition = 10
                val intendedWindow = if (nhs && phase == "warmup" && movement.name == "March on the spot") movement.seconds ?: 180
                    else if (easier) 20 else if (minutes <= 15) 45 else 60
                val window = minOf(intendedWindow, available - rest - transition)
                if (window < 10) return@mapIndexedNotNull null
                val work = minOf(window, movement.seconds ?: window)
                if (work < 1) return@mapIndexedNotNull null
                val repCap = movement.reps?.let { Regex("\\d+").find(it)!!.value.toInt() }
                    ?.coerceAtMost(if (easier || minutes <= 15) 5 else 8)
                available -= work + rest + transition
                WorkoutTimeBlock(index, work, rest, transition, setCap = 1, repCap = repCap,
                    doseSecondsCap = movement.seconds?.coerceAtMost(work))
            }
            if (blocks.isEmpty()) return null
            WorkoutTimeStage(phase, limit, blocks, recoverySeconds = available)
        }
        return WorkoutTimePlan(total, stages)
    }
}

object SourcedWorkoutRules {
    internal const val VIDEO_NOTE = "Returned metadata only; not watched. Technique reference, not an exact follow-along or verification of this session's timing, noise level or suitability."
    internal val impact = Regex("(?i)\\b(?:jump(?:ing|s)?|hop(?:ping|s)?|burpees?|sprints?|running|stomp(?:ing)?)\\b")
    internal val equipment = Regex("(?i)\\b(?:dumbbells?|barbells?|kettlebells?|resistance bands?|pull.up bar|bench|chairs?|step platform|treadmill|weights)\\b")
    private fun equipmentMissing(text: String, available: String): Boolean = equipment.findAll(text).any { required ->
        val term = FoodRules.normalize(required.value).removeSuffix("s")
        !FoodRules.contains(FoodRules.normalize(available).split(' ').joinToString(" ") { it.removeSuffix("s") }, term)
    }
    internal fun injury(text: String): Boolean = text.isNotBlank() && !Regex("(?i)^(?:none|no|no injuries|no injury|n/a)$").matches(text.trim())
    internal fun videoValid(video: VideoReference, origin: ResultOrigin, movements: List<WorkoutMovement>): Boolean {
        val snapshot = origin.retrieval ?: return false
        val source = snapshot.sources.singleOrNull { it.id == video.evidence.sourceId } ?: return false
        val metadata = "Title: ${video.title}\nChannel: ${video.channel}\nURL: ${video.url}"
        val title = FoodRules.normalize(video.title)
        val forbidden = Regex("\\b(?:deleted|private|unavailable|removed|advanced|weighted|rehab\\w*|music|playlist|reaction|cooking|diet|review)\\b")
        val articles = snapshot.sources.filter { item -> item.kind == "workout" && movements.any { movement ->
            movement.evidence.any { it.snapshotId == snapshot.id && it.sourceId == item.id && item.excerpt.contains(it.excerpt) }
        } }
        val broadMatch = Regex("\\bbeginners?\\b").containsMatchIn(title) &&
            Regex("\\b(?:workout|exercises?|calisthenics)\\b").containsMatchIn(title) &&
            Regex("\\b(?:no equipment|without equipment|bodyweight|body weight|calisthenics)\\b").containsMatchIn(title) &&
            (articles.any { article ->
                val content = FoodRules.normalize(article.title + " " + article.excerpt)
                Regex("\\bbeginners?\\b").containsMatchIn(content) &&
                    Regex("\\b(?:no equipment|without equipment|bodyweight|body weight)\\b").containsMatchIn(content) &&
                    (!title.contains("calisthenics") || content.contains("calisthenics")) &&
                    listOf("yoga", "pilates", "swimming", "cycling", "dance", "boxing").all { !FoodRules.contains(title, it) || FoodRules.contains(content, it) }
            } || NhsWorkoutArticles.completeSet(snapshot.sources) && articles.all(NhsWorkoutArticles::recognized) &&
                movements.any { it.name == "Wall press-up" } &&
                listOf("yoga", "pilates", "swimming", "cycling", "dance", "boxing").none { FoodRules.contains(title, it) })
        val exactMatch = movements.any { FoodRules.contains(video.title, it.name) }
        val compatible = !equipment.containsMatchIn(title.replace("no equipment", "")) &&
            !impact.containsMatchIn(title.replace("no jumping", "").replace("without jumping", "")) &&
            (!WorkoutEdits.pushups(title) || movements.any { WorkoutEdits.pushups(it.name) })
        return Regex("https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}").matches(video.url) &&
            video.title.isNotBlank() && video.title.length <= 300 && video.channel.isNotBlank() && video.channel.length <= 150 &&
            !forbidden.containsMatchIn(title) &&
            video.match == VideoMatch.TECHNIQUE_REFERENCE && video.verificationNote in listOf(VIDEO_NOTE, "Returned metadata only; not watched or verified as a follow-along.") &&
            video.evidence.snapshotId == snapshot.id && source.kind == "youtube_metadata" &&
            source.url == video.url && source.title == video.title && source.publisher == video.channel &&
            source.excerpt == metadata && video.evidence.excerpt == metadata &&
            (exactMatch || broadMatch && compatible)
    }
    fun validate(workout: Workout, profile: Profile): List<String> {
        val value = workout.sourced ?: return listOf("This is a legacy workout; review its original instructions separately.")
        val issues = mutableListOf<String>()
        val origin = value.origin
        val snapshot = origin.retrieval
        if (workout.origin != origin || workout.revision != origin.profileRevision || origin.requestId.isBlank() || snapshot == null ||
            snapshot.requestId != origin.requestId || snapshot.id.isBlank() || snapshot.sources.size !in 1..4 ||
            snapshot.sources.any { it.id.isBlank() || !publicResearchUrl(it.url) } ||
            snapshot.sources.map { it.id }.distinct().size != snapshot.sources.size) return listOf("Workout source identity is incomplete.")
        if (injury(profile.injuries)) issues += "Please clarify your injury and clinician's movement guidance before using a general session."
        if (value.warmUp.isEmpty() || value.movements.isEmpty() || value.cooldown.isEmpty()) issues += "Warm-up, movements and cooldown all need complete published instructions."
        val all = value.warmUp + value.movements + value.cooldown
        val curated = NhsWorkoutArticles.completeSet(snapshot.sources)
        if (curated && !NhsWorkoutArticles.supportedSubject(value.subject)) issues += "These NHS movements do not establish the requested workout topic."
        if (all.size !in 3..18) issues += "The session needs a manageable number of supported movements."
        val noEquipment = value.noEquipment || profile.environment().workoutEquipment.trim().equals("None", true)
        val quiet = value.quiet || WorkoutEdits.quiet(profile.environment().workoutSpace)
        val noJumping = value.noJumping || WorkoutEdits.noJumping(profile.environment().workoutSpace)
        if (value.durationMinutes != null && value.durationMinutes !in 5..60) issues += "Choose a time budget between 5 and 60 minutes."
        val expectedNotes = buildList {
            add(WorkoutEdits.SELECTION_NOTE)
            value.durationMinutes?.let { add(WorkoutEdits.budgetNote(it)) }
            if (value.reducedDose) add(WorkoutEdits.DOSE_NOTE)
            if (value.avoidPushUps) add(WorkoutEdits.REMOVE_NOTE)
            if (curated) add(NhsWorkoutArticles.NOTE)
        }
        if (value.programmingNotes != expectedNotes) issues += "Programming changes need their explicit AI labels."
        val published = snapshot.sources.flatMap { PublishedWorkoutParser.blocks(it, snapshot) }
        if (all.flatMap { it.evidence }.distinct().size != all.flatMap { it.evidence }.size) issues += "An exercise block was repeated without a supported programming change."
        listOf("warmup" to value.warmUp, "main" to value.movements, "cooldown" to value.cooldown).forEach { (phase, moves) ->
            moves.forEach { movement ->
                val original = published.singleOrNull { it.phase == phase && it.movement.evidence == movement.evidence }?.movement
                if (original == null || !WorkoutProgramming.validDose(original) || movement != (if (value.reducedDose) WorkoutEdits.dose(original) else original)) issues += "${movement.name}: instructions or dose do not match the cited article block."
                if (movement.sets?.let { it !in 1..6 } == true || movement.seconds?.let { it !in 1..300 } == true ||
                    movement.restSeconds?.let { it !in 0..300 } == true || movement.reps?.let { rep -> Regex("\\d+").findAll(rep).any { match -> match.value.toIntOrNull()?.let { it !in 1..50 } != false } } == true) issues += "${movement.name}: the published dose needs review."
                val text = movement.name + " " + movement.instructions
                if (FoodRules.contains(text, "wall") && (!value.wallAvailable || !WorkoutEdits.wallAvailable(profile, origin.assertions))) issues += "Please confirm that a suitable wall is available before using this movement."
                if (curated && Regex("(?i)no floor|no clear floor|cannot sit|can.t sit").containsMatchIn(profile.environment().workoutSpace)) issues += "The selected NHS cooldown needs clear floor space."
                if ((quiet || noJumping) && impact.containsMatchIn(text)) issues += "${movement.name}: this conflicts with quiet/no-jumping movement."
                if ((noEquipment && equipment.containsMatchIn(text)) || equipmentMissing(text, profile.environment().workoutEquipment)) issues += "${movement.name}: equipment is needed or uncertain."
                if (value.avoidPushUps && WorkoutEdits.pushups(text)) issues += "A difficult push-up movement is still present."
            }
        }
        val articleSources = all.flatMap { it.evidence }.mapNotNull { ref -> snapshot.sources.singleOrNull { it.id == ref.sourceId } }.distinctBy { it.id }
        if (articleSources.isEmpty() || articleSources.any { source ->
            if (curated && NhsWorkoutArticles.guidance(source)) false else
            !WorkoutEdits.relevant(source, value.subject) ||
            (noEquipment && !Regex("(?i)no equipment|without equipment|bodyweight|body-weight").containsMatchIn(source.title + " " + source.excerpt)) ||
                ((profile.experience.equals("Beginner", true) || Regex("(?i)\\bbeginners?\\b").containsMatchIn(value.subject)) && !Regex("(?i)beginner|beginners|getting started").containsMatchIn(source.title + " " + source.excerpt)) ||
                (quiet && !Regex("(?i)quiet|small (?:room|space)|low.impact|no jumping").containsMatchIn(source.title + " " + source.excerpt))
        }) issues += "Article suitability for your experience, equipment or room is not established."
        if (value.durationMinutes == null) {
            if (value.timePlan != null) issues += "A timed program needs a requested duration."
        } else {
            // Rebuild from article blocks, not from the submitted caps or modified dose fields.
            fun originals(moves: List<WorkoutMovement>) = moves.mapNotNull { movement ->
                published.singleOrNull { it.movement.evidence == movement.evidence }?.movement
            }
            val expected = WorkoutProgramming.build(originals(value.warmUp), originals(value.movements), originals(value.cooldown), value.durationMinutes, value.reducedDose)
            if (expected == null || value.timePlan != expected) issues += "Timed workload, rest, transitions or cooldown do not match the independently checked program."
        }
        value.video?.let { if (!videoValid(it, origin, all)) issues += "The video metadata does not match a movement or its source record." }
        return issues.distinct()
    }
}

class SourcedWorkoutService(private val model: CloudModel) {
    suspend fun reply(id: RequestIdentity, profile: Profile, conversation: FeatureConversation, text: String): WorkoutChatReply {
        currentCoroutineContext().ensureActive()
        val edit = WorkoutEdits.edit(text) && conversation.subject.isNotBlank()
        val subject = if (edit) conversation.subject else text.trim()
        fun question(message: String) = WorkoutChatReply(message, subject = subject)
        if (SourcedWorkoutRules.injury(profile.injuries) || Regex("(?i)\\b(?:injur\\w*|pain|hurts?|rehab\\w*|surgery|sprain\\w*)\\b").containsMatchIn(text)) return question(
            "Let's take this gently. A general workout isn't injury rehabilitation. What movement limits has your clinician given you?")
        if (edit && !WorkoutEdits.supportedEdit(text)) return question("I don't have a verified change for every part of that request yet. Would you like a shorter time budget, quieter movements, no push-ups or a reduced dose?")
        val previous = conversation.result?.workout?.takeIf { edit && it.sourced?.subject == subject }
        val prior = previous?.sourced
        val editHistory = if (edit) conversation.messages.filter { it.role in listOf("You", "user") }
            .takeLastWhile { WorkoutEdits.edit(it.text) }.map { it.text } else emptyList()
        val instructions = (if (edit) listOf(subject) else emptyList()) + editHistory + text
        val minutes = instructions.asReversed().firstNotNullOfOrNull(WorkoutEdits::duration) ?: prior?.durationMinutes
        if (minutes != null && minutes !in 5..60) return question("Let's keep the time budget manageable. Would 5–60 minutes work for you?")
        val noEquipment = instructions.any(WorkoutEdits::noEquipment) || prior?.noEquipment == true || profile.environment().workoutEquipment.trim().equals("None", true)
        val quiet = instructions.any(WorkoutEdits::quiet) || prior?.quiet == true || WorkoutEdits.quiet(profile.environment().workoutSpace)
        val noJumping = instructions.any(WorkoutEdits::noJumping) || WorkoutEdits.noJumping(profile.environment().workoutSpace) || prior?.noJumping == true || quiet
        val avoidPushUps = instructions.any { WorkoutEdits.pushups(it) && Regex("(?i)difficult|hard|without|no ").containsMatchIn(it) } || prior?.avoidPushUps == true
        val easier = instructions.any(WorkoutEdits::easier) || prior?.reducedDose == true
        val assertions = (if (edit) prior?.origin?.assertions.orEmpty() + editHistory.map { UserAssertion(it) } else emptyList()) + UserAssertion(text)
        val wallAvailable = WorkoutEdits.wallAvailable(profile, assertions)
        fun wallQuestion() = question("I can look for a gentle wall-supported session, without a chair or weights. Do you have a suitable wall and clear floor space? If so, say ‘Use a wall’.")
        fun session(sources: List<RetrievedSource>, assertions: List<UserAssertion>, video: ResearchVideo? = null): Workout? {
            val rebound = sources.filter { it.kind != "youtube_metadata" }.distinctBy { it.url }.mapIndexed { index, source -> source.copy(id = "article-$index") }
            val snapshot = RetrievalSnapshot(UUID.randomUUID().toString(), id.id, rebound)
            val origin = ResultOrigin(id.id, id.profileRevision, assertions, snapshot)
            val curated = NhsWorkoutArticles.completeSet(rebound)
            val duration = minutes ?: if (curated) 15 else null
            val notes = buildList {
                add(WorkoutEdits.SELECTION_NOTE)
                duration?.let { add(WorkoutEdits.budgetNote(it)) }
                if (easier) add(WorkoutEdits.DOSE_NOTE)
                if (avoidPushUps) add(WorkoutEdits.REMOVE_NOTE)
                if (curated) add(NhsWorkoutArticles.NOTE)
            }
            val blocks = rebound.flatMap { PublishedWorkoutParser.blocks(it, snapshot) }
                .filterNot { avoidPushUps && WorkoutEdits.pushups(it.movement.name + " " + it.movement.instructions) }
                // The bilateral stretches remain available as source references, but this minimal
                // timed path selects the symmetric seated stretch rather than inventing per-side timings.
                .filter { !curated || it.phase != "cooldown" || it.movement.name == "Inner thigh stretch" }
            fun originals(phase: String) = blocks.filter { it.phase == phase }.map { it.movement }
            fun moves(phase: String) = blocks.filter { it.phase == phase }.map { if (easier) WorkoutEdits.dose(it.movement) else it.movement }
            val timePlan = duration?.let { WorkoutProgramming.build(originals("warmup"), originals("main"), originals("cooldown"), it, easier) ?: return null }
            var result = Workout("Article-backed movement session", emptyList(), mode = profile.mode, revision = id.profileRevision, origin = origin,
                sourced = SourcedWorkout(origin, moves("warmup"), moves("main"), moves("cooldown"), durationMinutes = duration,
                    programmingNotes = notes, noEquipment = noEquipment, quiet = quiet, noJumping = noJumping,
                    avoidPushUps = avoidPushUps, reducedDose = easier, subject = subject, timePlan = timePlan, wallAvailable = wallAvailable))
            if (SourcedWorkoutRules.validate(result, profile).isNotEmpty()) return null
            if (video != null) result = attachVideo(result, video)
            return result
        }
        // A local edit re-parses the retained article, rebinds its snapshot, and never presents it
        // as a fresh lookup. Failed/forged prior results are not trusted as a shortcut.
        if (previous != null && prior != null && SourcedWorkoutRules.validate(previous, profile).isEmpty()) {
            val local = session(prior.origin.retrieval!!.sources, assertions,
                prior.video?.let { ResearchVideo(it.url, it.title, it.channel, "TECHNIQUE_REFERENCE", "Metadata only") })
            if (local != null) return WorkoutChatReply("Of course. I've adjusted the session using the same article evidence; the AI programming changes are labeled. Go at your own pace.", local, subject)
        }
        check(profile.sourceLookupConsent) { "Please allow source lookup before sending workout terms to search providers." }
        val query = buildString {
            append(subject.take(65))
            if (edit) append(" ").append(text.take(35))
            if (noEquipment) append(" no equipment")
            if (quiet) append(" quiet low impact")
            else if (noJumping) append(" no jumping")
            if (avoidPushUps) append(" without pushups")
            append(" warm up cooldown instructions")
        }.take(160)
        val request = ResearchRequest(id.id, id.profileRevision, "workout", query,
            constraints = ResearchConstraints(experience = profile.experience, injuries = profile.injuries,
                equipment = if (noEquipment) listOf("None") else listOf(profile.environment().workoutEquipment), durationMinutes = minutes),
            allowExternalModel = profile.externalModelConsent)
        // The backend composes CHAT_PERSONALITY for primary and fallback. Research claims are
        // validated, but cannot substitute for full published exercise instructions.
        suspend fun lookup(url: String? = null): ResearchResponse {
            val input = if (url == null) request else request.copy(url = url, subject = "NHS published warm-up, strength and cooldown instructions")
            val response = model.research(input)
            currentCoroutineContext().ensureActive()
            require(response.apiVersion == 2 && response.requestId == id.id && response.profileRevision == id.profileRevision && response.snapshot.requestId == id.id)
            if (response.snapshot.sources.isEmpty()) require(response.answer.claims.isEmpty())
            else response.copy(video = null).validate(input)
            return response
        }
        val explicitWall = WorkoutEdits.wall(text) == true && NhsWorkoutArticles.supportedSubject(subject)
        val response = if (explicitWall) null else lookup()
        val initial = response?.snapshot?.sources.orEmpty()
        var workout = initial.firstNotNullOfOrNull { session(listOf(it), assertions, response?.video) }
        val initialNhs = initial.filter(NhsWorkoutArticles::recognized)
        if (workout == null && NhsWorkoutArticles.completeSet(initialNhs)) workout = session(initialNhs, assertions, response?.video)
        if (workout == null && !wallAvailable && (explicitWall || initial.any(NhsWorkoutArticles::supplementaryCandidate) ||
                initial.any { source -> PublishedWorkoutParser.blocks(source, response!!.snapshot).any { FoodRules.contains(it.movement.instructions, "wall") } })) return wallQuestion()
        if (workout == null && NhsWorkoutArticles.supportedSubject(subject) &&
            (explicitWall || initial.any(NhsWorkoutArticles::supplementaryCandidate))) {
            if (!wallAvailable) return wallQuestion()
            if (avoidPushUps) return question("The reviewed NHS main movement is a wall press-up, which you asked to avoid. Could you choose another published session? Your previous workout stays here.")
            if (minutes != null && minutes < 12) return question("These NHS pages reserve at least 6 minutes for warm-up and about 5 for cooldown. Could you allow at least 12 minutes, or choose another published session?")
            val pages = initialNhs.toMutableList()
            var returnedVideo = response?.video
            // At most three additional URL lookups. Every call uses the normal research quota,
            // cancellation and citation checks; no raw page fetch or provider bypass in the app.
            for (url in NhsWorkoutArticles.urls) {
                val present = pages.singleOrNull { it.url == url }
                val presentSnapshot = RetrievalSnapshot("coverage", id.id, listOfNotNull(present))
                if (present != null && NhsWorkoutArticles.blocks(present, presentSnapshot).isNotEmpty()) continue
                val supplement = lookup(url)
                pages.removeAll { it.url == url }
                pages += supplement.snapshot.sources.filter { it.url == url && NhsWorkoutArticles.recognized(it) }
                if (returnedVideo == null) returnedVideo = supplement.video
            }
            workout = session(pages, assertions, returnedVideo)
        }
        val accepted = workout ?: return question("I couldn't verify a complete warm-up, workout and cooldown that fit your request. Could you try another published beginner session? Your previous workout stays here.")
        return WorkoutChatReply("Here's a gentle place to start. The linked articles support these instructions; any programming changes are labeled separately. ${if (accepted.sourced?.video == null) "No matching video metadata was available." else "The video is a technique reference only."}", accepted, subject)
    }

    private fun attachVideo(workout: Workout, metadata: ResearchVideo): Workout {
        val value = requireNotNull(workout.sourced)
        val old = requireNotNull(value.origin.retrieval)
        val text = "Title: ${metadata.title}\nChannel: ${metadata.channel}\nURL: ${metadata.url}"
        val source = RetrievedSource("video-${UUID.randomUUID()}", metadata.url, metadata.title,
            old.sources.firstOrNull()?.retrievedAt.orEmpty(), publisher = metadata.channel, excerpt = text, kind = "youtube_metadata")
        val origin = value.origin.copy(retrieval = old.copy(sources = old.sources + source))
        val video = VideoReference(metadata.url, metadata.title, metadata.channel, EvidenceReference(old.id, source.id, text),
            verificationNote = SourcedWorkoutRules.VIDEO_NOTE)
        if (metadata.match != "TECHNIQUE_REFERENCE" || !SourcedWorkoutRules.videoValid(video, origin, value.warmUp + value.movements + value.cooldown)) return workout
        return workout.copy(origin = origin, sourced = value.copy(origin = origin, video = video))
    }
}

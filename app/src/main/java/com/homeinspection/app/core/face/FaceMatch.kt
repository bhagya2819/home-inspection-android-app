package com.homeinspection.app.core.face

/** Stored by name on the server (attendance.face_result): never rename, only add. */
enum class FaceResult(val label: String) {
    MATCH("Face matched"),
    WEAK("Weak match"),
    NO_MATCH("Face did not match"),
    NOT_ENROLLED("Face not set up"),
    UNAVAILABLE("Face check unavailable"),
}

/**
 * Compares a check-in face with the engineer's approved enrolment shots.
 *
 * Scores are cosine similarities of SFace embeddings (see [FaceEngine.similarity]): about 1.0 for
 * the same photo, typically 0.5–0.8 for the same person in different light, and below ~0.3 for
 * different people. OpenCV's published SFace threshold is 0.363 (≥ = same person), tuned on the
 * LFW benchmark, where it gets 99.4 % of pairs right.
 */
object FaceMatcher {

    /** Best similarity against any enrolment shot (each shot is a different angle/expression). */
    fun bestScore(probe: FloatArray, enrolled: List<FloatArray>): Float =
        enrolled.maxOf { FaceEngine.similarity(probe, it) }

    /**
     * Turns the best score into the result the admin sees.
     *
     * MATCH: check in normally. WEAK: the engineer may retry or check in anyway; the admin sees an
     * amber badge. NO_MATCH: retry; after 3 tries the engineer may still check in, flagged red
     * (we remind, never block work).
     */
    fun classify(score: Float): FaceResult = when {
        score >= MATCH_AT -> FaceResult.MATCH
        score >= WEAK_AT -> FaceResult.WEAK
        else -> FaceResult.NO_MATCH
    }

    /**
     * Starting values (2026-10-21), to revisit after a week of real check-ins. MATCH sits a little
     * above OpenCV's 0.363 so a lookalike rarely passes cleanly; WEAK catches the same person in bad
     * light for the admin to eyeball instead of rejecting them.
     */
    private const val MATCH_AT = 0.45f
    private const val WEAK_AT = 0.30f

    /**
     * Enrolment sanity check: every shot must look like the same person as the others, so a
     * second person stepping in mid-enrolment (or a bad frame) is caught before the admin sees it.
     */
    fun consistent(shots: List<FloatArray>): Boolean {
        for (i in shots.indices) for (j in i + 1 until shots.size) {
            if (FaceEngine.similarity(shots[i], shots[j]) < ENROLL_MIN_PAIR) return false
        }
        return true
    }

    private const val ENROLL_MIN_PAIR = 0.45f
}

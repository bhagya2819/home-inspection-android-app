package com.griha.inspection.core.model

/** PRD §5.2 lifecycle. Stored by name, so never rename a constant — add new ones instead. */
enum class InspectionStatus(val label: String) {
    DRAFT("Draft"),
    IN_PROGRESS("In progress"),
    READY_FOR_REPORT("Ready for report"),
    FINALIZED("Finalized"),
    ARCHIVED("Archived"),
}

/** Labels follow the client prototype; constant names are the stored values. */
enum class Severity(val label: String) {
    INFO("Observation"),
    MINOR("Low"),
    MODERATE("Moderate"),
    MAJOR("High"),
    URGENT("Urgent"),
}

/**
 * What a finding row holds. ROOM_PHOTOS is a room's "common photos": overview shots from the corners
 * that identify the room, shown above its findings and never counted or checked as an issue.
 * Stored by name; a row synced from an app that predates this column is an ISSUE.
 */
enum class FindingKind { ISSUE, ROOM_PHOTOS }

enum class FindingStatus(val label: String) {
    OPEN("Open"),
    NOT_APPLICABLE("Not applicable"),
    RESOLVED("Resolved"),
}

/**
 * Per-photo backup state shown to the inspector (PRD §5.5).
 * A photo row only exists once its original is durably on disk, so every state here
 * already implies "saved on this device".
 */
enum class UploadState(val label: String) {
    LOCAL_ONLY("On this device"),
    QUEUED("Waiting to upload"),
    UPLOADING("Uploading"),
    RETRY_REQUIRED("Upload failed, will retry"),
    SERVER_VERIFIED("Backed up"),
    CLEANUP_ELIGIBLE("Backed up, can be cleared"),
}

enum class SyncEntity { INSPECTION, ROOM, FINDING, PHOTO, REPORT }

enum class SyncOperation { UPSERT, DELETE, UPLOAD_ORIGINAL }

// Configurable lists (PRD §5.3, §5.4). Admin-managed versions synced from the server come in M5;
// until then these are the defaults. Stored as plain text, so changing them never breaks old data.

val PropertyTypes = listOf("Apartment", "Independent house", "Villa", "Commercial", "Other")

val FindingCategories = listOf(
    "Structural",
    "Walls & finishes",
    "Water / plumbing",
    "Dampness / leakage",
    "Electrical",
    "Doors & windows",
    "Flooring & tiles",
    "Roof / terrace",
    "Safety",
    "Other",
)

/**
 * Rooms offered when starting an inspection. None is ticked by default: the inspector ticks them in
 * the order they will walk the property, and the rooms are created in that order.
 */
val DefaultRoomChoices = listOf(
    "Living room",
    "Kitchen",
    "Bedroom 1",
    "Bedroom 2",
    "Bathroom 1",
    "Bathroom 2",
    "Sit-out / balcony",
    "Roof / terrace",
    "Exterior",
    "Electrical",
    "Plumbing",
)

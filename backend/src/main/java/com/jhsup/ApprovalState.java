package com.jhsup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ApprovalState
 *
 * The freeze-dried HITL boundary persisted to:
 *   user-data/{userId}/courses/{courseId}/approvals/{approvalId}.json
 *
 * Events are typed as CalendarEventRequest — the same record the rest of
 * the application already serialises/deserialises for study-plan.json.
 * Jackson handles Java records natively (2.14+).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalState {

    // ── Identity ──────────────────────────────────────────────────────────────
    public String approvalId;
    public String userId;
    public String courseId;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    public Instant createdAt;
    public Instant expiresAt;   // default: createdAt + 7 days
    public Instant decidedAt;

    // ── Status ────────────────────────────────────────────────────────────────
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }
    public Status status = Status.PENDING;

    // ── Event payloads ────────────────────────────────────────────────────────

    /** Events loaded from the user's study-plan.json, before agent validation. */
    public List<AgenticService.CalendarEventRequest> originalEvents;

    /**
     * The conflict-resolved, celebration-injected events the agent produced.
     * This is exactly what the deterministic execution script POSTs to Google
     * Calendar — no further LLM calls after approval.
     */
    public List<AgenticService.CalendarEventRequest> validatedEvents;

    // ── Agent report ──────────────────────────────────────────────────────────
    public List<String> changeSummary;
    public String agentDebugLog;

    // ── Tool results ──────────────────────────────────────────────────────────
    public Map<String, Object> guideMappingResult;
    public Map<String, Object> temporalLogicResult;
    public Map<String, Object> burnoutResult;
    /** Per-event FreeBusy results keyed by event summary. */
    public Map<String, Map<String, Object>> conflictResults;

    // ── Execution receipt ─────────────────────────────────────────────────────
    /** Populated after approval: summary → Google Calendar event ID (or "ERROR: …"). */
    public Map<String, String> createdCalendarEventIds;

    // Jackson requires a no-arg constructor for records-as-beans
    public ApprovalState() {}

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ApprovalState pending(
            String approvalId,
            String userId,
            String courseId,
            List<AgenticService.CalendarEventRequest> originalEvents) {

        ApprovalState s  = new ApprovalState();
        s.approvalId     = approvalId;
        s.userId         = userId;
        s.courseId       = courseId;
        s.status         = Status.PENDING;
        s.createdAt      = Instant.now();
        s.expiresAt      = s.createdAt.plusSeconds(7L * 24 * 60 * 60);
        s.originalEvents = originalEvents;
        return s;
    }

    public boolean isActionable() {
        return status == Status.PENDING && Instant.now().isBefore(expiresAt);
    }
}

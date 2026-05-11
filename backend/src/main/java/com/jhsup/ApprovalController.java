package com.jhsup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ApprovalController — HITL three-phase workflow.
 *
 *   POST   /{courseId}/validate                   → Phase 1: agent SSE stream
 *   GET    /{courseId}/approvals/{id}             → Phase 2: review screen data
 *   GET    /{courseId}/approvals                  → list all for course
 *   POST   /{courseId}/approvals/{id}/approve     → Phase 3a: deterministic insert
 *   POST   /{courseId}/approvals/{id}/reject      → Phase 3b: no-op
 *
 * All event data is typed as AgenticService.CalendarEventRequest throughout.
 * The Google Calendar insert maps its fields directly.
 */
@RestController
@RequestMapping("/api/users/courses/{courseId}")
public class ApprovalController {

    private static final String GCAL_INSERT_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    private final ScheduleValidatorAgent agent;
    private final ApprovalStore          store;
    private final ObjectMapper           mapper;
    private final HttpClient             http     = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService        executor = Executors.newCachedThreadPool();

    public ApprovalController(ScheduleValidatorAgent agent, ApprovalStore store) {
        this.agent  = agent;
        this.store  = store;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request DTO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Body for POST /validate.
     * "events" is the List<CalendarEventRequest> from the frontend's studyEvents.
     * "availableGuides" is the list of guide filenames already generated.
     */
    record ValidateRequest(
            List<AgenticService.CalendarEventRequest> events,
            List<String> availableGuides
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 1 — Validate
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/validate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter validate(
            @AuthenticationPrincipal OAuth2User principal,
            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client,
            @PathVariable String courseId,
            @RequestBody ValidateRequest body) {

        SseEmitter emitter = new SseEmitter(300_000L);

        if (principal == null) {
            sendSse(emitter, "error", Map.of("message", "Unauthorized"));
            emitter.complete();
            return emitter;
        }

        String userId      = principal.getAttribute("sub");
        String accessToken = client.getAccessToken().getTokenValue();

        executor.submit(() -> {
            try {
                sendSse(emitter, "status", Map.of("step", "INIT",
                        "message", "Initializing validator agent…"));

                List<AgenticService.CalendarEventRequest> proposedEvents =
                        body.events() != null ? body.events() : List.of();

                List<String> availableGuides =
                        (body.availableGuides() != null && !body.availableGuides().isEmpty())
                                ? body.availableGuides()
                                : scanGuideNames(userId, courseId);

                String        approvalId = UUID.randomUUID().toString();
                ApprovalState state      = ApprovalState.pending(approvalId, userId, courseId, proposedEvents);
                store.save(state); // persist immediately so approvalId is valid even if agent crashes

                sendSse(emitter, "status", Map.of("step", "AGENT_START",
                        "message", "Agent running tool checks…",
                        "approvalId", approvalId));

                state = agent.run(state, availableGuides, accessToken);
                store.save(state);

                sendSse(emitter, "complete", Map.of(
                        "approvalId",    approvalId,
                        "status",        "PENDING",
                        "changeSummary", state.changeSummary != null ? state.changeSummary : List.of()
                ));
                emitter.complete();

            } catch (Exception e) {
                sendSse(emitter, "error",
                        Map.of("message", e.getMessage() != null ? e.getMessage() : "Agent failed"));
                emitter.complete();
            }
        });

        return emitter;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 2 — Review
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/approvals/{approvalId}")
    public ResponseEntity<ApprovalState> getApproval(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String approvalId) throws IOException {

        if (principal == null) return ResponseEntity.status(401).build();
        String userId = principal.getAttribute("sub");
        return store.load(userId, courseId, approvalId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/approvals")
    public ResponseEntity<List<ApprovalState>> listApprovals(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId) throws IOException {

        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(store.listForCourse(principal.getAttribute("sub"), courseId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 3a — Approve (deterministic — ZERO LLM calls)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/approvals/{approvalId}/approve")
    public ResponseEntity<ApprovalState> approve(
            @AuthenticationPrincipal OAuth2User principal,
            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client,
            @PathVariable String courseId,
            @PathVariable String approvalId) throws IOException, InterruptedException {

        if (principal == null) return ResponseEntity.status(401).build();
        String userId      = principal.getAttribute("sub");
        String accessToken = client.getAccessToken().getTokenValue();

        ApprovalState state = store.approve(userId, courseId, approvalId);

        Map<String, String> createdIds = new LinkedHashMap<>();
        for (AgenticService.CalendarEventRequest event : state.validatedEvents) {
            String summary = event.summary() != null ? event.summary() : "Unnamed";
            try {
                createdIds.put(summary, insertGoogleCalendarEvent(event, accessToken));
            } catch (Exception e) {
                createdIds.put(summary, "ERROR: " + e.getMessage());
            }
        }

        state.createdCalendarEventIds = createdIds;
        store.save(state);
        return ResponseEntity.ok(state);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHASE 3b — Reject
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/approvals/{approvalId}/reject")
    public ResponseEntity<ApprovalState> reject(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String approvalId) throws IOException {

        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(store.reject(principal.getAttribute("sub"), courseId, approvalId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Google Calendar insert — uses CalendarEventRequest fields directly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps a CalendarEventRequest to the Google Calendar Events.insert payload.
     *
     * Field mapping:
     *   event.summary()              → summary
     *   event.description()          → description
     *   event.location()             → location (if present)
     *   event.start().dateTime()     → start.dateTime
     *   event.start().timeZone()     → start.timeZone
     *   event.end().dateTime()       → end.dateTime
     *   event.end().timeZone()       → end.timeZone
     *   event.recurrence()           → recurrence (if non-empty)
     *   event.reminders()            → reminders
     *   event.extendedProperties()   → extendedProperties.private (as private namespace)
     *   event.extendedProperties().get("agent_type") → colorId
     */
    private String insertGoogleCalendarEvent(
            AgenticService.CalendarEventRequest event,
            String accessToken)
            throws IOException, InterruptedException {

        Map<String, Object> gcalEvent = new LinkedHashMap<>();

        gcalEvent.put("summary", event.summary() != null ? event.summary() : "Study Session");

        if (event.description() != null)
            gcalEvent.put("description", event.description());

        if (event.location() != null)
            gcalEvent.put("location", event.location());

        // start / end — use the CalendarEventRequest's nested Time records
        if (event.start() != null) {
            Map<String, String> start = new LinkedHashMap<>();
            start.put("dateTime", event.start().dateTime());
            start.put("timeZone",
                    event.start().timeZone() != null ? event.start().timeZone() : "UTC");
            gcalEvent.put("start", start);
        }

        if (event.end() != null) {
            Map<String, String> end = new LinkedHashMap<>();
            end.put("dateTime", event.end().dateTime());
            end.put("timeZone",
                    event.end().timeZone() != null ? event.end().timeZone() : "UTC");
            gcalEvent.put("end", end);
        }

        if (event.recurrence() != null && !event.recurrence().isEmpty())
            gcalEvent.put("recurrence", event.recurrence());

        // Reminders
        if (event.reminders() != null) {
            Map<String, Object> reminders = new LinkedHashMap<>();
            reminders.put("useDefault", event.reminders().useDefault());
            if (event.reminders().overrides() != null) {
                List<Map<String, Object>> overrides = new ArrayList<>();
                for (AgenticService.CalendarEventRequest.Override o : event.reminders().overrides()) {
                    overrides.add(Map.of("method", o.method(), "minutes", o.minutes()));
                }
                reminders.put("overrides", overrides);
            }
            gcalEvent.put("reminders", reminders);
        }

        // Extended properties — stored under the "private" namespace in GCal
        if (event.extendedProperties() != null && !event.extendedProperties().isEmpty()) {
            gcalEvent.put("extendedProperties",
                    Map.of("private", event.extendedProperties()));
        }

        // Color-code by agent_type so exams, sessions, and celebrations are distinct
        String agentType = ValidationTools.agentType(event);
        int colorId = switch (agentType) {
            case "exam"         -> 11; // Tomato
            case "celebration"  -> 2;  // Sage
            default             -> 9;  // Blueberry (study_session / other)
        };
        gcalEvent.put("colorId", String.valueOf(colorId));

        String jsonBody = mapper.writeValueAsString(gcalEvent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GCAL_INSERT_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Calendar insert failed (" + response.statusCode()
                    + "): " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = mapper.readValue(response.body(), Map.class);
        return (String) responseBody.get("id");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> scanGuideNames(String userId, String courseId) {
        File dir   = new File("user-data/" + userId + "/courses/" + courseId + "/guides");
        String[] files = dir.list((d, name) -> name.endsWith(".md"));
        if (files == null) return List.of();
        return Arrays.stream(files).map(name -> name.replace(".md", "")).toList();
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(mapper.writeValueAsString(data)));
        } catch (IOException ignored) {}
    }
}

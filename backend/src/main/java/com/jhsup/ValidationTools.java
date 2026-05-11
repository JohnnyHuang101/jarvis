package com.jhsup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ValidationTools
 *
 * The four deterministic tool functions the agent calls.
 *
 * CalendarEventRequest field mapping used throughout:
 *
 *   event.summary()                          → event title
 *   event.start().dateTime()                 → ISO-8601 start string
 *   event.end().dateTime()                   → ISO-8601 end string
 *   event.start().timeZone()                 → e.g. "America/Los_Angeles"
 *   event.extendedProperties().get("agent_type")   → "study_session" | "exam" | "celebration"
 *   event.extendedProperties().get("target_exam")  → exam summary this session prepares for
 *
 * Focus duration is derived from end − start (no separate focusTime field).
 */
@Component
public class ValidationTools {

    private static final int    BURNOUT_THRESHOLD_MINUTES = 360; // 6 hours
    private static final String GCAL_FREEBUSY_URL =
            "https://www.googleapis.com/calendar/v3/freeBusy";

    private final ObjectMapper mapper     = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — check_guide_mapping
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compares study-session event summaries against the guide files on disk.
     * Returns missing entries (session exists, no guide file) and orphaned ones
     * (guide file exists, no matching session).
     */
    public Map<String, Object> checkGuideMapping(
            List<String> eventSummaries,
            List<String> availableGuides) {

        Set<String> normGuides  = availableGuides.stream().map(this::sanitise).collect(Collectors.toSet());
        Set<String> normEvents  = eventSummaries.stream().map(this::sanitise).collect(Collectors.toSet());

        List<String> missing  = normEvents.stream().filter(e -> !normGuides.contains(e)).toList();
        List<String> orphaned = normGuides.stream().filter(g -> !normEvents.contains(g)).toList();

        String status = (missing.isEmpty() && orphaned.isEmpty()) ? "OK" : "MISMATCH";
        return Map.of(
                "status",   status,
                "missing",  missing,
                "orphaned", orphaned,
                "detail",   status.equals("OK")
                        ? "Every study session has a corresponding guide."
                        : "See missing/orphaned lists for required corrections."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — check_calendar_conflicts
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calls the Google Calendar FreeBusy API for [startTime, endTime].
     * The start/end strings come directly from CalendarEventRequest.start().dateTime()
     * and CalendarEventRequest.end().dateTime().
     */
    public Map<String, Object> checkCalendarConflicts(
            String startTime,
            String endTime,
            String accessToken) {

        try {
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "timeMin", startTime,
                    "timeMax", endTime,
                    "items",   List.of(Map.of("id", "primary"))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GCAL_FREEBUSY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("status", "ERROR",
                        "detail", "Google Calendar API returned " + response.statusCode(),
                        "busy", false);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Object> busySlots =
                    (List<Object>) ((Map<String, Object>)
                            ((Map<String, Object>) body.getOrDefault("calendars", Map.of()))
                                    .getOrDefault("primary", Map.of()))
                            .getOrDefault("busy", List.of());

            boolean isBusy = !busySlots.isEmpty();
            return Map.of(
                    "status",    "OK",
                    "busy",      isBusy,
                    "busySlots", busySlots,
                    "detail",    isBusy
                            ? busySlots.size() + " conflict(s) in [" + startTime + ", " + endTime + "]"
                            : "Time slot is free."
            );

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("status", "ERROR",
                    "detail", "FreeBusy call failed: " + e.getMessage(),
                    "busy", false);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — analyze_temporal_logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies chronological sanity using the actual CalendarEventRequest fields:
     *
     *   • event type   → extendedProperties.get("agent_type")
     *                    ("study_session" | "exam" | "celebration")
     *   • target exam  → extendedProperties.get("target_exam")
     *   • start time   → start().dateTime()
     *
     * Invariants checked:
     *   1. Every study_session must start before the exam it targets.
     *   2. No event may start in the past.
     */
    public Map<String, Object> analyzeTemporalLogic(
            List<AgenticService.CalendarEventRequest> events) {

        List<String> violations = new ArrayList<>();
        Instant      now        = Instant.now();

        // Index exam start times by their summary (lower-cased)
        Map<String, Instant> examTimes = new HashMap<>();
        for (AgenticService.CalendarEventRequest event : events) {
            if ("exam".equals(agentType(event)) && event.summary() != null) {
                Instant t = parseInstant(startDateTime(event));
                if (t != null) examTimes.put(event.summary().toLowerCase().trim(), t);
            }
        }

        for (AgenticService.CalendarEventRequest event : events) {
            String  type      = agentType(event);
            String  summary   = event.summary();
            Instant startTime = parseInstant(startDateTime(event));

            if (startTime == null) {
                violations.add("Event '" + summary + "' has an unparseable start.dateTime.");
                continue;
            }

            // Invariant 1 — study sessions must precede their target exam
            if ("study_session".equals(type)) {
                String targetExam = targetExam(event);
                if (targetExam != null) {
                    Instant examTime = examTimes.get(targetExam.toLowerCase().trim());
                    if (examTime != null && !startTime.isBefore(examTime)) {
                        violations.add(String.format(
                                "TEMPORAL VIOLATION: '%s' starts at %s which is NOT before " +
                                "its target exam '%s' at %s.",
                                summary, startTime, targetExam, examTime));
                    }
                }
            }

            // Invariant 2 — nothing in the past
            if (startTime.isBefore(now)) {
                violations.add(String.format(
                        "PAST EVENT: '%s' (%s) starts at %s — already in the past.",
                        summary, type, startTime));
            }
        }

        return Map.of(
                "status",     violations.isEmpty() ? "OK" : "VIOLATIONS_FOUND",
                "violations", violations,
                "examCount",  examTimes.size(),
                "detail",     violations.isEmpty()
                        ? "All " + events.size() + " events pass temporal logic checks."
                        : violations.size() + " violation(s) require correction."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — check_burnout_limits
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aggregates total study duration per calendar day.
     * Duration is derived from end.dateTime − start.dateTime (no separate
     * focusTime field exists on CalendarEventRequest).
     * Days exceeding 6 hours (360 min) are flagged as burnout risk.
     */
    public Map<String, Object> checkBurnoutLimits(
            List<AgenticService.CalendarEventRequest> events) {

        Map<String, Integer>      dailyMinutes    = new TreeMap<>();
        Map<String, List<String>> dailyEventNames = new TreeMap<>();

        for (AgenticService.CalendarEventRequest event : events) {
            String rawStart = startDateTime(event);
            String rawEnd   = endDateTime(event);
            if (rawStart == null) continue;

            Instant startInstant = parseInstant(rawStart);
            Instant endInstant   = rawEnd != null ? parseInstant(rawEnd) : null;
            if (startInstant == null) continue;

            // Duration in minutes from start → end; default 60 min if end missing
            int durationMinutes = endInstant != null
                    ? (int) ChronoUnit.MINUTES.between(startInstant, endInstant)
                    : 60;
            if (durationMinutes <= 0) durationMinutes = 60;

            String day     = startInstant.toString().substring(0, 10); // "yyyy-MM-dd"
            String summary = event.summary() != null ? event.summary() : "Unnamed";

            dailyMinutes.merge(day, durationMinutes, Integer::sum);
            dailyEventNames.computeIfAbsent(day, k -> new ArrayList<>()).add(summary);
        }

        List<Map<String, Object>> overloadedDays = new ArrayList<>();
        List<Map<String, Object>> allDays        = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : dailyMinutes.entrySet()) {
            String  day     = entry.getKey();
            int     minutes = entry.getValue();
            boolean over    = minutes > BURNOUT_THRESHOLD_MINUTES;

            Map<String, Object> dayInfo = new HashMap<>();
            dayInfo.put("date",         day);
            dayInfo.put("totalMinutes", minutes);
            dayInfo.put("totalHours",   String.format("%.1fh", minutes / 60.0));
            dayInfo.put("overLimit",    over);
            dayInfo.put("events",       dailyEventNames.get(day));

            allDays.add(dayInfo);
            if (over) overloadedDays.add(dayInfo);
        }

        return Map.of(
                "status",         overloadedDays.isEmpty() ? "OK" : "BURNOUT_RISK",
                "thresholdHours", BURNOUT_THRESHOLD_MINUTES / 60,
                "overloadedDays", overloadedDays,
                "allDays",        allDays,
                "detail",         overloadedDays.isEmpty()
                        ? "No day exceeds the 6-hour study limit."
                        : overloadedDays.size() + " day(s) exceed the limit and must be rescheduled."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CalendarEventRequest field accessors (null-safe)
    // ─────────────────────────────────────────────────────────────────────────

    /** Null-safe read of start.dateTime */
    static String startDateTime(AgenticService.CalendarEventRequest e) {
        return e.start() != null ? e.start().dateTime() : null;
    }

    /** Null-safe read of end.dateTime */
    static String endDateTime(AgenticService.CalendarEventRequest e) {
        return e.end() != null ? e.end().dateTime() : null;
    }

    /** Reads extendedProperties.agent_type; falls back to eventType if absent. */
    static String agentType(AgenticService.CalendarEventRequest e) {
        if (e.extendedProperties() != null) {
            String t = e.extendedProperties().get("agent_type");
            if (t != null && !t.isBlank()) return t;
        }
        // Treat focusTime events as study sessions when metadata is missing
        return "focusTime".equals(e.eventType()) ? "study_session" : "other";
    }

    /** Reads extendedProperties.target_exam */
    private static String targetExam(AgenticService.CalendarEventRequest e) {
        return e.extendedProperties() != null
                ? e.extendedProperties().get("target_exam")
                : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String sanitise(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim().toLowerCase();
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toInstant(); }
        catch (Exception e) {
            try { return Instant.parse(raw); }
            catch (Exception ignored) { return null; }
        }
    }
}

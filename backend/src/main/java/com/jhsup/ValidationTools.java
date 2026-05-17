package com.jhsup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ValidationTools
 *
 * The four deterministic tool functions the agent calls.
 *
 * CalendarEventRequest field mapping used throughout:
 *
 * event.summary() → event title
 * event.start().dateTime() → ISO-8601 start string
 * event.end().dateTime() → ISO-8601 end string
 * event.start().timeZone() → e.g. "America/Los_Angeles"
 * event.extendedProperties().get("agent_type") → "study_session" | "exam" |
 * "celebration"
 * event.extendedProperties().get("target_exam") → exam summary this session
 * prepares for
 *
 * Focus duration is derived from end − start (no separate focusTime field).
 */
@Component
public class ValidationTools {

    private static final int BURNOUT_THRESHOLD_MINUTES = 360; // 6 hours
    private static final String GCAL_FREEBUSY_URL = "https://www.googleapis.com/calendar/v3/freeBusy";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
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
    public Map<String, Object> checkGuideMapping(List<AgenticService.CalendarEventRequest> currentEvents) {

        // 1. Find all exams currently in the backend state
        Set<String> normExams = currentEvents.stream()
                .filter(e -> "exam".equals(agentType(e)))
                .map(e -> sanitise(e.summary()))
                .collect(Collectors.toSet());

        // 2. Check if any study sessions point to a missing exam
        List<String> broken = currentEvents.stream()
                .filter(e -> "study_session".equals(agentType(e)))
                .filter(e -> !normExams.contains(sanitise(targetExam(e))))
                .map(e -> e.summary() + " → target_exam '" + targetExam(e) + "' not found")
                .toList();

        return Map.of(
                "status", broken.isEmpty() ? "OK" : "MISMATCH",
                "broken", broken,
                "detail", broken.isEmpty()
                        ? "All sessions link to a valid exam."
                        : broken.size() + " session(s) have a bad target_exam.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — check_calendar_conflicts
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calls the Google Calendar FreeBusy API for [startTime, endTime].
     * The start/end strings come directly from
     * CalendarEventRequest.start().dateTime()
     * and CalendarEventRequest.end().dateTime().
     */
    public Map<String, Object> checkCalendarConflicts(
            String startTime,
            String endTime,
            String accessToken) {

        // 1. Defend against LLM JSON formatting mistakes
        if (startTime == null || endTime == null) {
            return Map.of("status", "ERROR", "detail",
                    "Missing 'start_time' or 'end_time' inside the 'arguments' object. Please format your JSON correctly.");
        }

        try {
            Instant safeStart = parseInstant(startTime, "America/Los_Angeles");
            Instant safeEnd = parseInstant(endTime, "America/Los_Angeles");

            // 2. Defend against garbage date strings
            if (safeStart == null || safeEnd == null) {
                return Map.of("status", "ERROR", "detail", "Could not parse dates. Must be ISO-8601 format.");
            }

            String jsonBody = mapper.writeValueAsString(Map.of(
                    "timeMin", safeStart.toString(),
                    "timeMax", safeEnd.toString(),
                    "items", List.of(Map.of("id", "primary"))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GCAL_FREEBUSY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("status", "ERROR",
                        "detail", "Google Calendar API returned " + response.statusCode(),
                        "busy", false);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Object> busySlots = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) body
                    .getOrDefault("calendars", Map.of()))
                    .getOrDefault("primary", Map.of()))
                    .getOrDefault("busy", List.of());

            boolean isBusy = !busySlots.isEmpty();
            return Map.of(
                    "status", "OK",
                    "busy", isBusy,
                    "busySlots", busySlots,
                    "detail", isBusy
                            ? busySlots.size() + " conflict(s) in [" + startTime + ", " + endTime + "]"
                            : "Time slot is free.");

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
     * • event type → extendedProperties.get("agent_type")
     * ("study_session" | "exam" | "celebration")
     * • target exam → extendedProperties.get("target_exam")
     * • start time → start().dateTime()
     *
     * Invariants checked:
     * 1. Every study_session must start before the exam it targets.
     * 2. No event may start in the past.
     */
    public Map<String, Object> analyzeTemporalLogic(
            List<AgenticService.CalendarEventRequest> events) {

        List<String> violations = new ArrayList<>();
        Instant now = Instant.now();

        // Index exam start times by their summary (lower-cased)
        Map<String, Instant> examTimes = new HashMap<>();
        for (AgenticService.CalendarEventRequest event : events) {
            if ("exam".equals(agentType(event)) && event.summary() != null) {
                Instant t = parseInstant(startDateTime(event), event.start().timeZone());
                if (t != null)
                    examTimes.put(event.summary().toLowerCase().trim(), t);
            }
        }

        for (AgenticService.CalendarEventRequest event : events) {
            String type = agentType(event);
            String summary = event.summary();
            Instant startTime = parseInstant(startDateTime(event), event.start().timeZone());

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
                "status", violations.isEmpty() ? "OK" : "VIOLATIONS_FOUND",
                "violations", violations,
                "examCount", examTimes.size(),
                "detail", violations.isEmpty()
                        ? "All " + events.size() + " events pass temporal logic checks."
                        : violations.size() + " violation(s) require correction.");
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

        Map<String, Integer> dailyMinutes = new TreeMap<>();
        Map<String, List<String>> dailyEventNames = new TreeMap<>();

        for (AgenticService.CalendarEventRequest event : events) {
            String rawStart = startDateTime(event);
            String rawEnd = endDateTime(event);
            if (rawStart == null)
                continue;

            Instant startInstant = parseInstant(rawStart, event.start().timeZone());
            Instant endInstant = rawEnd != null ? parseInstant(rawEnd, event.start().timeZone()) : null;
            if (startInstant == null)
                continue;

            // Duration in minutes from start → end; default 60 min if end missing
            int durationMinutes = endInstant != null
                    ? (int) ChronoUnit.MINUTES.between(startInstant, endInstant)
                    : 60;
            if (durationMinutes <= 0)
                durationMinutes = 60;

            String day = startInstant.toString().substring(0, 10); // "yyyy-MM-dd"
            String summary = event.summary() != null ? event.summary() : "Unnamed";

            dailyMinutes.merge(day, durationMinutes, Integer::sum);
            dailyEventNames.computeIfAbsent(day, k -> new ArrayList<>()).add(summary);
        }

        List<Map<String, Object>> overloadedDays = new ArrayList<>();
        List<Map<String, Object>> allDays = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : dailyMinutes.entrySet()) {
            String day = entry.getKey();
            int minutes = entry.getValue();
            boolean over = minutes > BURNOUT_THRESHOLD_MINUTES;

            Map<String, Object> dayInfo = new HashMap<>();
            dayInfo.put("date", day);
            dayInfo.put("totalMinutes", minutes);
            dayInfo.put("totalHours", String.format("%.1fh", minutes / 60.0));
            dayInfo.put("overLimit", over);
            dayInfo.put("events", dailyEventNames.get(day));

            allDays.add(dayInfo);
            if (over)
                overloadedDays.add(dayInfo);
        }

        return Map.of(
                "status", overloadedDays.isEmpty() ? "OK" : "BURNOUT_RISK",
                "thresholdHours", BURNOUT_THRESHOLD_MINUTES / 60,
                "overloadedDays", overloadedDays,
                "allDays", allDays,
                "detail", overloadedDays.isEmpty()
                        ? "No day exceeds the 6-hour study limit."
                        : overloadedDays.size() + " day(s) exceed the limit and must be rescheduled.");
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
            if (t != null && !t.isBlank())
                return t;
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
        if (name == null)
            return "";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim().toLowerCase();
    }

    private Instant parseInstant(String raw, String tzId) {
        if (raw == null || raw.isBlank())
            return null;
        // Try with offset first
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        // Try as LocalDateTime + named zone
        try {
            ZoneId zone = (tzId != null && !tzId.isBlank())
                    ? ZoneId.of(tzId)
                    : ZoneId.of("America/Los_Angeles");
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    public void sortEventsChronologically(List<AgenticService.CalendarEventRequest> events) {
        events.sort((e1, e2) -> {
            try {
                Instant start1 = parseInstant(e1.start().dateTime(), e1.start().timeZone());
                Instant start2 = parseInstant(e2.start().dateTime(), e2.start().timeZone());
                if (start1 == null || start2 == null)
                    return 0;
                return start1.compareTo(start2);
            } catch (Exception ex) {
                return 0;
            }
        });
    }

    public Map<String, Object> findOpenTimeSlots(
            String searchStartIso,
            String searchEndIso,
            int minDurationMinutes,
            String accessToken) {

        try {
            // 1. Fetch busy data from Google
            Instant searchStart = parseInstant(searchStartIso, "America/Los_Angeles");
            Instant searchEnd = parseInstant(searchEndIso, "America/Los_Angeles");

            String jsonBody = mapper.writeValueAsString(Map.of(
                    "timeMin", searchStart.toString(),
                    "timeMax", searchEnd.toString(),
                    "items", List.of(Map.of("id", "primary"))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GCAL_FREEBUSY_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("status", "ERROR", "detail", "GCal API returned " + response.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(response.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> busySlots = (List<Map<String, String>>) ((Map<String, Object>) ((Map<String, Object>) body
                    .getOrDefault("calendars", Map.of()))
                    .getOrDefault("primary", Map.of()))
                    .getOrDefault("busy", List.of());

            // 3. Convert busy slots to Instant intervals and sort them chronologically
            record Interval(Instant start, Instant end) {
            }

            List<Interval> busyIntervals = busySlots.stream()
                    .map(slot -> new Interval(
                            ZonedDateTime.parse(slot.get("start"), DateTimeFormatter.ISO_DATE_TIME).toInstant(),
                            ZonedDateTime.parse(slot.get("end"), DateTimeFormatter.ISO_DATE_TIME).toInstant()))
                    // Ensure we only care about overlaps within our search window
                    .map(i -> new Interval(
                            i.start().isBefore(searchStart) ? searchStart : i.start(),
                            i.end().isAfter(searchEnd) ? searchEnd : i.end()))
                    .sorted((a, b) -> a.start().compareTo(b.start()))
                    .toList();

            // 4. Merge overlapping or touching busy intervals
            List<Interval> mergedBusy = new ArrayList<>();
            for (Interval current : busyIntervals) {
                if (mergedBusy.isEmpty()) {
                    mergedBusy.add(current);
                } else {
                    Interval lastMerged = mergedBusy.get(mergedBusy.size() - 1);
                    if (!current.start().isAfter(lastMerged.end())) {
                        // They overlap or touch; merge them by taking the max end time
                        Instant maxEnd = current.end().isAfter(lastMerged.end()) ? current.end() : lastMerged.end();
                        mergedBusy.set(mergedBusy.size() - 1, new Interval(lastMerged.start(), maxEnd));
                    } else {
                        mergedBusy.add(current);
                    }
                }
            }

            // 5. Invert busy intervals to find free gaps
            List<Map<String, Object>> freeSlots = new ArrayList<>();
            Instant currentPointer = searchStart;

            for (Interval busy : mergedBusy) {
                if (currentPointer.isBefore(busy.start())) {
                    long gapMinutes = ChronoUnit.MINUTES.between(currentPointer, busy.start());
                    if (gapMinutes >= minDurationMinutes) {
                        freeSlots.add(Map.of(
                                "start", currentPointer.toString(),
                                "end", busy.start().toString(),
                                "durationMinutes", gapMinutes));
                    }
                }
                currentPointer = busy.end().isAfter(currentPointer) ? busy.end() : currentPointer;
            }

            // Check the final gap after the last busy block until searchEnd
            if (currentPointer.isBefore(searchEnd)) {
                long gapMinutes = ChronoUnit.MINUTES.between(currentPointer, searchEnd);
                if (gapMinutes >= minDurationMinutes) {
                    freeSlots.add(Map.of(
                            "start", currentPointer.toString(),
                            "end", searchEnd.toString(),
                            "durationMinutes", gapMinutes));
                }
            }

            return Map.of(
                    "status", "OK",
                    "freeSlots", freeSlots,
                    "detail", freeSlots.isEmpty()
                            ? "No contiguous free time found matching criteria."
                            : "Found " + freeSlots.size() + " available time slots.");

        } catch (Exception e) {
            return Map.of("status", "ERROR", "detail", "Failed to calculate open slots: " + e.getMessage());
        }
    }

    public Map<String, Object> replaceEvent(
            List<AgenticService.CalendarEventRequest> currentEvents,
            String oldSummary,
            AgenticService.CalendarEventRequest newEvent) {

        boolean found = false;
        for (int i = 0; i < currentEvents.size(); i++) {
            if (currentEvents.get(i).summary().equalsIgnoreCase(oldSummary.trim())) {
                currentEvents.set(i, newEvent);
                found = true;
                break; // Replace the first match
            }
        }

        if (!found) {
            return Map.of("status", "ERROR", "detail", "Could not find event with summary: " + oldSummary);
        }
        return Map.of("status", "OK", "detail", "Successfully replaced '" + oldSummary + "'.");
    }

    /**
     * Adds a completely new event (like a 15-minute break).
     */
    public Map<String, Object> addEvent(
            List<AgenticService.CalendarEventRequest> currentEvents,
            AgenticService.CalendarEventRequest newEvent) {

        currentEvents.add(newEvent);
        return Map.of("status", "OK", "detail", "Successfully added new event: " + newEvent.summary());
    }

    /**
     * Deletes an event.
     */
    public Map<String, Object> deleteEvent(
            List<AgenticService.CalendarEventRequest> currentEvents,
            String summaryToDelete) {

        boolean removed = currentEvents.removeIf(e -> e.summary().equalsIgnoreCase(summaryToDelete.trim()));

        if (!removed) {
            return Map.of("status", "ERROR", "detail", "Could not find event to delete: " + summaryToDelete);
        }
        return Map.of("status", "OK", "detail", "Successfully deleted '" + summaryToDelete + "'.");
    }

    public Map<String, Object> getScheduleSummary(List<AgenticService.CalendarEventRequest> currentEvents) {
        List<String> simplifiedList = currentEvents.stream()
                .map(e -> String.format("[%s] '%s' : %s -> %s",
                        agentType(e).toUpperCase(),
                        e.summary(),
                        startDateTime(e),
                        endDateTime(e)))
                .toList();

        return Map.of("status", "OK", "current_schedule", simplifiedList);
    }

    public Map<String, Object> shiftAllEventDates(
            List<AgenticService.CalendarEventRequest> currentEvents,
            int daysToShift) {

        List<AgenticService.CalendarEventRequest> shiftedList = new ArrayList<>();

        for (var event : currentEvents) {
            if (event.start() == null || event.start().dateTime() == null)
                continue;

            // Parse current times
            Instant start = Instant.parse(parseInstant(event.start().dateTime(), event.start().timeZone()).toString());
            Instant end = event.end() != null && event.end().dateTime() != null
                    ? Instant.parse(parseInstant(event.end().dateTime(), event.start().timeZone()).toString())
                    : start.plus(1, ChronoUnit.HOURS);

            // Add the day offset
            Instant newStart = start.plus(daysToShift, ChronoUnit.DAYS);
            Instant newEnd = end.plus(daysToShift, ChronoUnit.DAYS);

            shiftedList.add(new AgenticService.CalendarEventRequest(
                    event.summary(),
                    event.description(),
                    event.location(),
                    new AgenticService.CalendarEventRequest.Time(newStart.toString(), event.start().timeZone()),
                    new AgenticService.CalendarEventRequest.Time(newEnd.toString(), event.end().timeZone()),
                    event.recurrence(),
                    event.reminders(),
                    event.eventType(),
                    event.extendedProperties()));
        }

        currentEvents.clear();
        currentEvents.addAll(shiftedList);

        return Map.of("status", "OK", "detail",
                "Successfully shifted all calendar events forward by " + daysToShift + " days.");
    }

    public Map<String, Object> rescheduleToTargetDate(
            List<AgenticService.CalendarEventRequest> currentEvents,
            String targetDateIso) {

        if (currentEvents.isEmpty()) {
            return Map.of("status", "ERROR", "detail", "No events to shift.");
        }

        try {
            // 1. Parse the target date the LLM wants
            Instant targetInstant = parseInstant(targetDateIso, "America/Los_Angeles");

            // 2. Find the earliest event currently in the schedule
            Instant earliestInstant = Instant.MAX;
            for (var event : currentEvents) {
                Instant start = parseInstant(event.start().dateTime(), event.start().timeZone());
                if (start != null && start.isBefore(earliestInstant)) {
                    earliestInstant = start;
                }
            }

            // 3. Calculate the exact days delta in Java!
            long daysToShift = ChronoUnit.DAYS.between(earliestInstant, targetInstant);

            // 4. Reuse your existing shift logic
            return shiftAllEventDates(currentEvents, (int) daysToShift);

        } catch (Exception e) {
            return Map.of("status", "ERROR", "detail", "Invalid target date format. Use ISO-8601.");
        }
    }

    public class BatchTools {

        public record BatchOperation(
                // Must be "ADD", "REPLACE", or "DELETE"
                String operationType,

                // The title of the old event to find (Required for REPLACE and DELETE)
                String targetSummary,

                // The new event details (Required for ADD and REPLACE)
                AgenticService.CalendarEventRequest newEvent) {
        }

        public record BatchRequest(
                List<BatchOperation> operations) {
        }
    }

    public Map<String, Object> batchMutateEvents(
            List<AgenticService.CalendarEventRequest> currentEvents,
            List<BatchTools.BatchOperation> operations) {

        if (operations == null || operations.isEmpty()) {
            return Map.of("status", "ERROR", "detail", "No operations provided.");
        }

        List<String> executionLogs = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < operations.size(); i++) {
            BatchTools.BatchOperation op = operations.get(i);

            try {
                switch (op.operationType().toUpperCase()) {
                    case "ADD" -> {
                        addEvent(currentEvents, op.newEvent());
                        executionLogs.add("Successfully added: " + op.newEvent().summary());
                        successCount++;
                    }
                    case "REPLACE" -> {
                        replaceEvent(currentEvents, op.targetSummary(), op.newEvent());
                        executionLogs.add("Successfully replaced: " + op.targetSummary());
                        successCount++;
                    }
                    case "DELETE" -> {
                        deleteEvent(currentEvents, op.targetSummary());
                        executionLogs.add("Successfully deleted: " + op.targetSummary());
                        successCount++;
                    }
                    default -> executionLogs
                            .add("Error at index " + i + ": Unknown operation type '" + op.operationType() + "'");
                }
            } catch (Exception e) {
                executionLogs.add("Error executing " + op.operationType() + " at index " + i + ": " + e.getMessage());
            }
        }

        return Map.of(
                "status", successCount == operations.size() ? "OK" : "PARTIAL_SUCCESS",
                "detail", "Processed " + operations.size() + " operations.",
                "executionLogs", executionLogs);
    }

}

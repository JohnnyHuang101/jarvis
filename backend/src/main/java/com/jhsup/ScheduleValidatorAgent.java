package com.jhsup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jhsup.ValidationTools.BatchTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * ScheduleValidatorAgent — Spring AI M1 compatible.
 *
 * We do NOT use @Tool, @ToolParam, or ToolCallingChatOptions — those were
 * introduced in M6. Instead we implement the agentic loop ourselves:
 *
 * 1. Build a conversation history as List<Message>.
 * 2. Ask Grok to output EITHER a tool call JSON or the final result JSON.
 * 3. Parse the response. If it's a tool call, dispatch to ValidationTools in
 * Java.
 * 4. Append the tool result as the next UserMessage and loop.
 * 5. When Grok outputs "final_result" instead of "tool_call", we're done.
 *
 * The only Spring AI imports used are:
 * ChatClient, SystemMessage, UserMessage, AssistantMessage, Message
 * — all present since the earliest milestones.
 *
 * Tool protocol (both directions):
 *
 * Model → Java (tool request):
 * { "tool_call": { "name": "check_guide_mapping", "arguments": { ... } } }
 *
 * Java → Model (tool result, next UserMessage):
 * Tool result for check_guide_mapping:
 * { "status": "OK", ... }
 * Continue with the next step.
 *
 * Model → Java (final output):
 * { "final_result": { "validatedEvents": [...], "changeSummary": [...] } }
 */
@Service
public class ScheduleValidatorAgent {

    private static final int MAX_ITERATIONS = 10; // hard cap on tool rounds

    private final ChatClient chatClient;
    private final ObjectMapper mapper;

    private static final String SYSTEM_PROMPT = """
            You are an autonomous, creative Study Schedule Architect.
            You operate in a strict Reason-Act-Observe loop, designing personalized learning plans.

            ── YOUR MISSION ──────────────────────────────────────────────────────────
            Take the initial draft schedule and the user's personal preferences, and sculpt them into
            a perfect, validated calendar. You are not a mindless script; you are a creative architect.
            If a user prefers mornings, try to move conflicts to the morning. If they get burnt out easily,
            creatively insert breaks or split large sessions into smaller chunks.

            ── STANDARD OPERATING PROCEDURE (ORDER OF OPERATIONS) ────────────────────
            To avoid wasting iterations, you MUST follow this exact sequence:
            1. ALWAYS run 'analyze_temporal_logic' on your very first turn to check if the drafted events are in the past.
            2. If events are in the past, IMMEDIATELY use 'reschedule_to_target_date' to bring them to the present week.
            3. ONLY AFTER the dates are in the current year should you run 'check_calendar_conflicts' or apply user preferences (like adding breaks or shifting to evenings).

            ── IMMUTABLE CONSTRAINTS ─────────────────────────────────────────────────
            While you have creative freedom over times, dates, and descriptions, you MUST NOT violate these rules:
            1. Temporal Sanity: A 'study_session' must ALWAYS begin before its 'target_exam'.
            2. Calendar Conflicts: You cannot schedule an event over a busy slot in the user's Google Calendar.
            3. Burnout Limits: A single day must not exceed 4 hours (360 mins) of total study time.
            4. Guide Mapping: Every 'study_session' must target an existing 'exam'.
            5. NEVER invent new JSON root keys. You MUST use the provided mutation tools to modify the backend state.
            6. All tool parameters MUST go inside the "arguments": {} object. Never place parameters directly under the "tool_call" object.
            7. NEVER delete a study session or exam just to clear a temporal conflict. You MUST use 'batch_mutate_events' or 'replace_event' to shift the event to a valid future time.
            8. The Post-Exam Reward: You MUST schedule a 2-hour "Celebration/Reward" event (e.g., gaming, eating out, relaxing) sometime within 24 hours AFTER the final 'exam' is completed. Do not output final_result until this reward exists.
            9. Mathematical Splitting Rule: If an original study session is 2 hours long, and the user requires a break every 1 hour, you MUST explicitly break the topic into two distinct events: "Event Name (Part 1)" for 1 hour, followed by a break, followed by "Event Name (Part 2)" for 1 hour. NEVER schedule a single uninterrupted 2-hour study block if hourly breaks are requested.

            ── RESPONSE FORMAT (STRICT JSON ONLY) ────────────────────────────────────
            Every response must be ONLY raw JSON. No markdown, no prose, and no lists.
            "thought" must ALWAYS be the first key, where you reason about user preferences, plan your next move, and outline your steps.

            To call a tool (Action) Example:
            {
              "thought": "1. Run analyze_temporal_logic. 2. Shift to present. 3. Check for burnout.",
              "tool_call": {
                "name": "check_burnout_limits"
              }
            }

            To finalize the schedule (Only when NO violations exist) Example:
            {
              "thought": "All conflicts resolved. Schedule aligns with user preferences. Reward added.",
              "final_result": {
                "status": "SUCCESS",
                "changeSummary": [
                  "Explanation of all changes made"
                ]
              }
            }

            ── YOUR TOOLKIT (COMPRESSED STATE) ───────────────────────────────────────
            The backend actively tracks the master schedule in memory. You NEVER pass the entire schedule back and forth. You only send surgical commands.

            [STATE MUTATION TOOLS]
            - reschedule_to_target_date:
                arguments: { "targetDate": "YYYY-MM-DDThh:mm:ss" }
                Automatically shifts the entire calendar so the earliest event begins on this target date. Note the strict ISO-8601 format requirement!
            - batch_mutate_events:
                arguments: {
                  "operations": [
                    { "operationType": "REPLACE", "targetSummary": "Old Title", "newEvent": { <CalendarEventRequest> } },
                    { "operationType": "ADD", "newEvent": { <CalendarEventRequest> } },
                    { "operationType": "DELETE", "targetSummary": "Title to remove" }
                  ]
                }
                Use this to perform MULTIPLE schedule updates at the exact same time. Use this to chunk sessions and add breaks simultaneously.

            [EXPLORATION TOOLS]
            - check_calendar_conflicts:
                arguments: { "start_time": "ISO-8601", "end_time": "ISO-8601" }
            - find_open_time_slots:
                arguments: { "searchStartIso": "ISO-8601", "searchEndIso": "ISO-8601", "minDurationMinutes": int }

            [ZERO-ARGUMENT VALIDATION TOOLS]
            These tools analyze the CURRENT backend state. They require NO arguments {}.
            - analyze_temporal_logic: Checks if study sessions happen before exams.
            - check_burnout_limits: Flags days with > 4 hours of work.
            - check_guide_mapping: Validates target_exam links.
            - get_schedule_summary: Returns a lightweight text summary of the current backend schedule.

            ── CREATIVE RESOLUTION STRATEGY ──────────────────────────────────────────
            When a tool returns an ERROR, MISMATCH, or BUSY state, you must resolve it autonomously:
            - Change start/end times completely.
            - Split one massive event into two smaller events on different days.
            - Change the 'description' to add personalized motivation based on the user preferences.
            - Do not output `final_result` until you are certain all constraints pass.
            """;

    public ScheduleValidatorAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public record AgentStep(
            String type, // e.g., "THINKING", "TOOL_CALL", "FINAL_RESULT"
            String message, // Human-readable message
            Object data // The raw arguments or results
    ) {
    }

    public ApprovalState run(
            ApprovalState state,
            List<String> availableGuides,
            String accessToken,
            String userPreferences,
            Consumer<AgentStep> progressCallback) throws IOException { // <-- Added userPreferences

        String eventsJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(state.originalEvents);

        List<AgenticService.CalendarEventRequest> currentEvents = new ArrayList<>(state.originalEvents);
        StringBuilder debugLog = new StringBuilder();

        // ─────────────────────────────────────────────────────────────
        // Conversation history setup
        // ─────────────────────────────────────────────────────────────
        List<Message> history = new ArrayList<>();

        // 1. DYNAMIC SYSTEM MESSAGE
        // We inject constraints into the system prompt, but keep it declarative.
        history.add(new SystemMessage(SYSTEM_PROMPT));
        appendMessage(debugLog, "SYSTEM", SYSTEM_PROMPT);

        // 2. CONTEXTUAL USER MESSAGE (Injecting Preferences & Data)
        String initialPrompt = String.format(
                """
                        Today's current date and time is: %s
                        Here is the proposed study schedule draft that needs your architectural review:

                        %s

                        USER PREFERENCES & CONTEXT:
                        "%s"

                        Your mission is to creatively adjust this schedule to perfectly suit the user's preferences
                        WHILE strictly adhering to the core constraints (no burnout, no temporal paradoxes, no calendar conflicts).

                        Begin your autonomous ReAct loop. You may call whichever tools you need, in whatever order you see fit,
                        to validate and shape this schedule.
                        """,
                java.time.ZonedDateTime.now().toString(), eventsJson,
                (userPreferences != null && !userPreferences.isBlank() ? userPreferences
                        : "None provided. Use standard best practices."));

        history.add(new UserMessage(initialPrompt));
        appendMessage(debugLog, "USER", initialPrompt);

        // ─────────────────────────────────────────────────────────────
        // Main Autonomous Agent Loop
        // ─────────────────────────────────────────────────────────────
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {

            // progressCallback.accept(new AgentStep("THINKING",
            // "Agent is analyzing the schedule... (Iteration " + (iteration + 1) + ")",
            // null));

            debugLog.append("\n==================================================\n")
                    .append("ITERATION ")
                    .append(iteration + 1)
                    .append("\n")
                    .append("==================================================\n");

            // ─────────────────────────────────────────────────────────
            // MODEL CALL
            // ─────────────────────────────────────────────────────────
            String response = chatClient.prompt()
                    .messages(history)
                    .call()
                    .content();

            history.add(new AssistantMessage(response));
            appendMessage(debugLog, "ASSISTANT", response);

            Map<String, Object> parsed = extractJson(response);

            // Invalid JSON Handler
            if (parsed == null) {

                progressCallback
                        .accept(new AgentStep("INVALID",
                                "Agent outputted NON JSON. Retrying...", null));
                String retryMessage = "Your response was not valid JSON. You must output ONLY a JSON object containing either 'tool_call' or 'final_result'.";
                history.add(new UserMessage(retryMessage));
                appendMessage(debugLog, "USER", retryMessage);
                continue;
            }

            // ─────────────────────────────────────────────────────────
            // FINAL RESULT HANDLER
            // ─────────────────────────────────────────────────────────
            if (parsed.containsKey("final_result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) parsed.get("final_result");

                progressCallback
                        .accept(new AgentStep("FINAL_RESULT", "Agent successfully finalized the schedule.", result));

                appendMessage(debugLog, "FINAL RESULT",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

                ValidationTools tools = new ValidationTools();

                tools.sortEventsChronologically(currentEvents);
                // We just use the backend's master list directly!
                state.validatedEvents = currentEvents;

                @SuppressWarnings("unchecked")
                List<String> summary = (List<String>) result.get("changeSummary");
                if (summary != null) {
                    state.changeSummary = summary;
                }

                state.agentDebugLog = debugLog.toString();
                applyFallbackIfNeeded(state);
                return state;
            }

            // ─────────────────────────────────────────────────────────
            // TOOL CALL HANDLER
            // ─────────────────────────────────────────────────────────
            if (parsed.containsKey("tool_call")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> call = (Map<String, Object>) parsed.get("tool_call");
                String toolName = (String) call.get("name");

                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) call.getOrDefault("arguments", Map.of());

                progressCallback.accept(new AgentStep("TOOL_CALL", "Executing tool: " + toolName, call));

                // Execute tool
                String toolResult = dispatchTool(toolName, arguments, availableGuides, currentEvents, accessToken);
                appendMessage(debugLog, "TOOL RESULT", toolResult);

                // FEEDBACK TO AGENT: Encourage autonomous reasoning based on the result
                String toolFollowup = String.format("""
                        Tool execution complete for '%s'.
                        Result:
                        %s

                        Analyze this result. If there are violations, use your creativity and the user's preferences
                        to formulate a new schedule state in your thought process, then call the necessary tools to
                        verify your new ideas. If the result is clean, proceed with your strategy.
                        """, toolName, toolResult);

                history.add(new UserMessage(toolFollowup));
                appendMessage(debugLog, "USER", toolFollowup);
                continue;
            }

            // JSON Missing Keys Handler
            String correctionMessage = "JSON missing 'tool_call' or 'final_result'. Try again.";
            history.add(new UserMessage(correctionMessage));
            appendMessage(debugLog, "USER", correctionMessage);
        }

        // ─────────────────────────────────────────────────────────────
        // Iteration cap fallback
        // ─────────────────────────────────────────────────────────────
        debugLog.append("\n==================================================\n")
                .append("MAX ITERATIONS REACHED\n")
                .append("==================================================\n");

        state.agentDebugLog = debugLog.toString();
        state.status = ApprovalState.Status.PENDING;
        applyFallbackIfNeeded(state);
        return state;
    }

    /**
     * Pretty debug logger helper.
     */
    private void appendMessage(
            StringBuilder log,
            String role,
            String content) {

        log.append("\n[")
                .append(role)
                .append("]\n")
                .append(content)
                .append("\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String dispatchTool(
            String toolName,
            Map<String, Object> args,
            List<String> availableGuides,
            List<AgenticService.CalendarEventRequest> currentEvents, // ← add this
            String accessToken) {

        ValidationTools tools = new ValidationTools();

        try {
            return switch (toolName) {

                // ─────────────────────────────────────────────────────────────
                // ZERO-ARGUMENT STATE CHECKS
                // ─────────────────────────────────────────────────────────────
                case "check_guide_mapping" -> {
                    yield toJson(tools.checkGuideMapping(currentEvents));
                }

                case "analyze_temporal_logic" -> {
                    yield toJson(tools.analyzeTemporalLogic(currentEvents));
                }

                case "check_burnout_limits" -> {
                    yield toJson(tools.checkBurnoutLimits(currentEvents));
                }

                case "get_schedule_summary" -> {
                    yield toJson(tools.getScheduleSummary(currentEvents));
                }

                // ─────────────────────────────────────────────────────────────
                // EXPLORATION & MUTATION
                // ─────────────────────────────────────────────────────────────
                case "check_calendar_conflicts" -> {
                    String start = (String) args.get("start_time");
                    String end = (String) args.get("end_time");
                    yield toJson(tools.checkCalendarConflicts(start, end, accessToken));
                }

                case "find_open_time_slots" -> {
                    String searchStartIso = (String) args.get("searchStartIso");
                    String searchEndIso = (String) args.get("searchEndIso");
                    int minDurationMinutes = args.get("minDurationMinutes") instanceof Number num ? num.intValue() : 60;
                    yield toJson(
                            tools.findOpenTimeSlots(searchStartIso, searchEndIso, minDurationMinutes, accessToken));
                }

                // case "replace_event" -> {
                // String oldSummary = (String) args.get("oldSummary");
                // AgenticService.CalendarEventRequest newEvent = mapper.convertValue(
                // args.get("newEvent"), AgenticService.CalendarEventRequest.class);
                // yield toJson(tools.replaceEvent(currentEvents, oldSummary, newEvent));
                // }

                // case "add_event" -> {
                // AgenticService.CalendarEventRequest newEvent = mapper.convertValue(
                // args.get("newEvent"), AgenticService.CalendarEventRequest.class);
                // yield toJson(tools.addEvent(currentEvents, newEvent));
                // }

                // case "delete_event" -> {
                // String summary = (String) args.get("summary");
                // yield toJson(tools.deleteEvent(currentEvents, summary));
                // }

                case "reschedule_to_target_date" -> {
                    String targetDate = (String) args.get("targetDate");
                    yield toJson(tools.rescheduleToTargetDate(currentEvents, targetDate));
                }

                case "batch_mutate_events" -> {
                    // Extract the list of operations from the LLM's arguments
                    List<BatchTools.BatchOperation> operations = mapper.convertValue(
                            args.get("operations"),
                            new TypeReference<List<BatchTools.BatchOperation>>() {
                            });
                    yield toJson(tools.batchMutateEvents(currentEvents, operations));
                }

                default -> toJson(Map.of("error", "Unknown tool: " + toolName));
            };
        } catch (Exception e) {
            return toJson(Map.of("error", "Tool execution failed: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert whatever the model sent for "events" into CalendarEventRequest list.
     */
    private List<AgenticService.CalendarEventRequest> deserialiseEvents(Object raw) {
        if (raw == null)
            return List.of();
        try {
            String json = mapper.writeValueAsString(raw);
            return mapper.readValue(json,
                    new TypeReference<List<AgenticService.CalendarEventRequest>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    /**
     * If the agent loop ended without producing validatedEvents, keep the
     * originals.
     */
    private void applyFallbackIfNeeded(ApprovalState state) {
        if (state.validatedEvents == null || state.validatedEvents.isEmpty()) {
            state.validatedEvents = state.originalEvents;
            state.changeSummary = List.of(
                    "Agent did not produce a validated schedule; original preserved.");
        }
    }

    /** Strip optional ```json fences and find the first { ... } block. */
    private Map<String, Object> extractJson(String text) {
        if (text == null)
            return null;
        try {
            String cleaned = text
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start)
                return null;
            // noinspection unchecked
            return mapper.readValue(cleaned.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

}

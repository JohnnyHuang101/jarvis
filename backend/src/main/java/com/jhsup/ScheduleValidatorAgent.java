package com.jhsup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

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

    private static final int MAX_ITERATIONS = 14; // hard cap on tool rounds

    private final ChatClient chatClient;
    private final ObjectMapper mapper;

    public ScheduleValidatorAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ApprovalState run(
            ApprovalState state,
            List<String> availableGuides,
            String accessToken) throws IOException {

        String eventsJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(state.originalEvents);

        List<AgenticService.CalendarEventRequest> currentEvents = state.originalEvents;

        StringBuilder debugLog = new StringBuilder();

        // ─────────────────────────────────────────────────────────────
        // Conversation history
        // ─────────────────────────────────────────────────────────────
        List<Message> history = new ArrayList<>();

        // SYSTEM MESSAGE
        history.add(new SystemMessage(SYSTEM_PROMPT));
        appendMessage(debugLog, "SYSTEM", SYSTEM_PROMPT);

        // INITIAL USER MESSAGE
        String initialPrompt = "Here is the proposed study schedule to validate:\n\n"
                + eventsJson
                + "\n\nBegin with step 1: call check_guide_mapping.";

        history.add(new UserMessage(initialPrompt));
        appendMessage(debugLog, "USER", initialPrompt);

        // ─────────────────────────────────────────────────────────────
        // Main agent loop
        // ─────────────────────────────────────────────────────────────
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {

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

            // Store assistant response
            history.add(new AssistantMessage(response));

            // Log assistant response
            appendMessage(debugLog, "ASSISTANT", response);

            // ─────────────────────────────────────────────────────────
            // Parse assistant JSON
            // ─────────────────────────────────────────────────────────
            Map<String, Object> parsed = extractJson(response);

            // Invalid JSON
            if (parsed == null) {

                String retryMessage = "Your response was not valid JSON.\n" +
                        "Please output ONLY a JSON object containing either:\n" +
                        "- tool_call\n" +
                        "- final_result";

                history.add(new UserMessage(retryMessage));

                appendMessage(debugLog, "USER", retryMessage);

                continue;
            }

            // ─────────────────────────────────────────────────────────
            // FINAL RESULT
            // ─────────────────────────────────────────────────────────
            if (parsed.containsKey("final_result")) {

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) parsed.get("final_result");

                appendMessage(
                        debugLog,
                        "FINAL RESULT",
                        mapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(result));

                // Parse validated events
                Object rawEvents = result.get("validatedEvents");

                if (rawEvents != null) {

                    try {

                        String json = mapper.writeValueAsString(rawEvents);

                        state.validatedEvents = mapper.readValue(
                                json,
                                new TypeReference<List<AgenticService.CalendarEventRequest>>() {
                                });

                    } catch (Exception ignored) {
                        // fallback later
                    }
                }

                // Parse change summary
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
            // TOOL CALL
            // ─────────────────────────────────────────────────────────
            if (parsed.containsKey("tool_call")) {

                @SuppressWarnings("unchecked")
                Map<String, Object> call = (Map<String, Object>) parsed.get("tool_call");

                String toolName = (String) call.get("name");

                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) call.getOrDefault(
                        "arguments",
                        Map.of());

                // // Log tool call
                // appendMessage(
                // debugLog,
                // "TOOL CALL",
                // toolName + "\n\n"
                // + mapper.writerWithDefaultPrettyPrinter()
                // .writeValueAsString(arguments));

                // Execute tool
                String toolResult = dispatchTool(
                        toolName,
                        arguments,
                        availableGuides,
                        currentEvents,
                        accessToken);

                // Log tool result
                appendMessage(debugLog, "TOOL RESULT", toolResult);

                // Feed tool result back into conversation
                String toolFollowup = "Tool result for " + toolName + ":\n\n"
                        + toolResult
                        + "\n\nContinue with the next step.\n"
                        + "Remember:\n"
                        + "- Output ONLY valid JSON\n"
                        + "- No markdown\n"
                        + "- No prose\n"
                        + "- Must contain either 'tool_call' or 'final_result'";

                history.add(new UserMessage(toolFollowup));

                appendMessage(debugLog, "USER", toolFollowup);

                continue;
            }

            // ─────────────────────────────────────────────────────────
            // JSON Missing Required Keys
            // ─────────────────────────────────────────────────────────
            String correctionMessage = "Your JSON did not contain either:\n" +
                    "- tool_call\n" +
                    "- final_result\n\n" +
                    "Please try again.";

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

                case "check_guide_mapping" -> {
                    // Pull the structured sessions list the agent now passes
                    List<Map<String, String>> sessions = (List<Map<String, String>>) args.getOrDefault("sessions",
                            List.of());

                    // Extract exam summaries from the full event list so we don't rely on disk
                    // files
                    List<String> examSummaries = currentEvents.stream()
                            .filter(e -> "exam".equals(ValidationTools.agentType(e)))
                            .map(AgenticService.CalendarEventRequest::summary)
                            .filter(Objects::nonNull)
                            .toList();

                    yield toJson(tools.checkGuideMapping(sessions, examSummaries));
                }

                case "check_calendar_conflicts" -> {
                    String start = (String) args.get("start_time");
                    String end = (String) args.get("end_time");
                    yield toJson(tools.checkCalendarConflicts(start, end, accessToken));
                }

                case "analyze_temporal_logic" -> {
                    // The model sends the event list as raw JSON objects;
                    // deserialise them back into CalendarEventRequest records
                    List<AgenticService.CalendarEventRequest> events = deserialiseEvents(args.get("events"));
                    yield toJson(tools.analyzeTemporalLogic(events));
                }

                case "check_burnout_limits" -> {
                    List<AgenticService.CalendarEventRequest> events = deserialiseEvents(args.get("events"));
                    yield toJson(tools.checkBurnoutLimits(events));
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

    // ─────────────────────────────────────────────────────────────────────────
    // System prompt
    // ─────────────────────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You are an autonomous scheduling validator for a student study planner.
            You operate in a strict Reason-Act-Observe loop.

            ── RESPONSE FORMAT (STRICT) ──────────────────────────────────────────────
            Every single response must be ONLY a raw JSON object — no prose, no markdown
            fences, no explanations before or after. Nothing outside the JSON.

            CRITICAL: Before making a tool call or returning the final result, you MUST
            explain your logic using the "thought" key. This must be the first key in the JSON.

            Either a tool call:
            {
              "thought": "I am on Step 1. I need to extract the study_session summaries and call check_guide_mapping...",
              "tool_call": {
                "name": "<tool name>",
                "arguments": { ... }
              }
            }

            Or the final result (only after ALL four tools have run successfully):
            {
              "thought": "All 4 steps are complete and passing. I will now add the celebration event and output the final result.",
              "final_result": {
                "validatedEvents": [ ... ],
                "changeSummary":   [ "Shifted Event A by 2 hours due to conflict", "Added Celebration event" ]
              }
            }

            ── EVENT SCHEMA ──────────────────────────────────────────────────────────
            Every event is a CalendarEventRequest:
            {
              "summary":     "string",
              "description": "string",
              "location":    "string or null",
              "start":  { "dateTime": "ISO-8601", "timeZone": "America/Los_Angeles" },
              "end":    { "dateTime": "ISO-8601", "timeZone": "America/Los_Angeles" },
              "recurrence": [],
              "reminders": { "useDefault": true, "overrides": [] },
              "eventType": "focusTime" or "default",
              "extendedProperties": {
                "agent_type":  "study_session" | "exam" | "celebration",
                "target_exam": "exact summary of the exam this session prepares for"
              }
            }

            ── TOOL STEP ORDER (STRICT SEQUENCE) ─────────────────────────────────────
            You must execute these tools in exact order. Do not skip steps.

            Step 1 — check_guide_mapping
                arguments: {
                    "sessions": [{ "summary": "...", "target_exam": "..." }, ...],
                    "exam_summaries": ["Exam 1", "Exam 2", ...]
                }
                Pass only study_sessions. exam_summaries come from agent_type = "exam" events.

            Step 2 — analyze_temporal_logic
              arguments: { "events": [ <full CalendarEventRequest array> ] }
              Verifies every study_session starts before its target exam.

            Step 3 — check_calendar_conflicts  (call ONCE PER EVENT)
              arguments: { "start_time": "ISO-8601", "end_time": "ISO-8601", "summary": "..." }
              If result.busy = true, shift the event by ≤ 48 hours and call again to verify.

            Step 4 — check_burnout_limits
              arguments: { "events": [ <full CalendarEventRequest array> ] }
              Calculates duration per day from end − start. Flags days > 6 hours.


            ── ERROR / VIOLATION HANDLING ────────────────────────────────────────────────
            If any tool returns a non-OK status, you MUST correct the data before continuing.
            Do NOT proceed to the next step with unresolved violations.

            - check_guide_mapping → MISMATCH:
            Fix the broken session's target_exam field in the event list, then re-call
            check_guide_mapping with the corrected data. Only advance to Step 2 when status = OK.

            - analyze_temporal_logic → VIOLATIONS_FOUND:
            For each violation, shift the offending study_session's start/end earlier
            (keep duration unchanged) so it precedes the target exam. Re-call the tool.

            - check_calendar_conflicts → busy = true:
            Shift the conflicting event forward by 1 hour, re-call check_calendar_conflicts.
            Repeat up to 48 hours shift total.

            - check_calendar_conflicts → status = ERROR:
            Log the failure, assume the slot is free, and continue. Do NOT retry infinitely.

            - check_burnout_limits → BURNOUT_RISK:
            Spread overloaded events to adjacent days, re-call check_burnout_limits.

            Only call final_result when ALL four tools have returned OK on their last invocation.

            ── AFTER ALL TOOLS PASS ──────────────────────────────────────────────────
            1. Add ONE celebration event to the validated list:
               - extendedProperties.agent_type = "celebration"
               - eventType = "default"
               - start.dateTime within 24 hours after the last exam's end.dateTime
               - duration = 2 hours, fun description
            2. Output the final_result JSON with ALL events including the celebration.
            """;
}

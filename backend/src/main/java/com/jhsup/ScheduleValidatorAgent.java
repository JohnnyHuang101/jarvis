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
 *   1. Build a conversation history as List<Message>.
 *   2. Ask Grok to output EITHER a tool call JSON or the final result JSON.
 *   3. Parse the response. If it's a tool call, dispatch to ValidationTools in Java.
 *   4. Append the tool result as the next UserMessage and loop.
 *   5. When Grok outputs "final_result" instead of "tool_call", we're done.
 *
 * The only Spring AI imports used are:
 *   ChatClient, SystemMessage, UserMessage, AssistantMessage, Message
 * — all present since the earliest milestones.
 *
 * Tool protocol (both directions):
 *
 *   Model → Java  (tool request):
 *     { "tool_call": { "name": "check_guide_mapping", "arguments": { ... } } }
 *
 *   Java → Model  (tool result, next UserMessage):
 *     Tool result for check_guide_mapping:
 *     { "status": "OK", ... }
 *     Continue with the next step.
 *
 *   Model → Java  (final output):
 *     { "final_result": { "validatedEvents": [...], "changeSummary": [...] } }
 */
@Service
public class ScheduleValidatorAgent {

    private static final int MAX_ITERATIONS = 14; // hard cap on tool rounds

    private final ChatClient   chatClient;
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
            List<String>  availableGuides,
            String        accessToken) throws IOException {

        String eventsJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(state.originalEvents);

        // Seed the conversation
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(SYSTEM_PROMPT));
        history.add(new UserMessage(
                "Here is the proposed study schedule to validate:\n\n" + eventsJson +
                "\n\nBegin with step 1: call check_guide_mapping."));

        StringBuilder debugLog = new StringBuilder();

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {

            // Call Grok via the M1-compatible ChatClient API
            String response = chatClient.prompt()
                    .messages(history)
                    .call()
                    .content();

            debugLog.append("\n=== Iteration ").append(iteration + 1).append(" ===\n")
                    .append(response).append("\n");

            history.add(new AssistantMessage(response));

            // ── Try to parse the response ─────────────────────────────────────
            Map<String, Object> parsed = extractJson(response);

            if (parsed == null) {
                // Malformed — nudge the model back on track
                history.add(new UserMessage(
                        "Your response was not valid JSON. " +
                        "Please output either a tool_call JSON or a final_result JSON " +
                        "as described in the system prompt. Try again."));
                continue;
            }

            // ── Final result ──────────────────────────────────────────────────
            if (parsed.containsKey("final_result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result =
                        (Map<String, Object>) parsed.get("final_result");

                Object rawEvents = result.get("validatedEvents");
                if (rawEvents != null) {
                    try {
                        String json = mapper.writeValueAsString(rawEvents);
                        state.validatedEvents = mapper.readValue(json,
                                new TypeReference<List<AgenticService.CalendarEventRequest>>() {});
                    } catch (Exception ignored) { /* fall through to fallback */ }
                }

                @SuppressWarnings("unchecked")
                List<String> summary = (List<String>) result.get("changeSummary");
                if (summary != null) state.changeSummary = summary;

                state.agentDebugLog = debugLog.toString();
                applyFallbackIfNeeded(state);
                return state;
            }

            // ── Tool call ─────────────────────────────────────────────────────
            if (parsed.containsKey("tool_call")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> call =
                        (Map<String, Object>) parsed.get("tool_call");

                String toolName = (String) call.get("name");

                @SuppressWarnings("unchecked")
                Map<String, Object> arguments =
                        (Map<String, Object>) call.getOrDefault("arguments", Map.of());

                String toolResult = dispatchTool(
                        toolName, arguments, availableGuides, accessToken);

                debugLog.append("TOOL: ").append(toolName)
                        .append("\nRESULT: ").append(toolResult).append("\n");

                history.add(new UserMessage(
                        "Tool result for " + toolName + ":\n" + toolResult +
                        "\n\nContinue with the next step. " +
                        "Remember to output ONLY a JSON object with either " +
                        "\"tool_call\" or \"final_result\" — no prose, no markdown."));
                continue;
            }

            // Response had JSON but neither key — nudge
            history.add(new UserMessage(
                    "Your JSON did not contain a 'tool_call' or 'final_result' key. " +
                    "Please try again."));
        }

        // Iteration cap hit
        state.agentDebugLog = debugLog.toString();
        applyFallbackIfNeeded(state);
        return state;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String dispatchTool(
            String toolName,
            Map<String, Object> args,
            List<String> availableGuides,
            String accessToken) {

        ValidationTools tools = new ValidationTools();

        try {
            return switch (toolName) {

                case "check_guide_mapping" -> {
                    List<String> summaries =
                            (List<String>) args.getOrDefault("event_summaries", List.of());
                    yield toJson(tools.checkGuideMapping(summaries, availableGuides));
                }

                case "check_calendar_conflicts" -> {
                    String start = (String) args.get("start_time");
                    String end   = (String) args.get("end_time");
                    yield toJson(tools.checkCalendarConflicts(start, end, accessToken));
                }

                case "analyze_temporal_logic" -> {
                    // The model sends the event list as raw JSON objects;
                    // deserialise them back into CalendarEventRequest records
                    List<AgenticService.CalendarEventRequest> events =
                            deserialiseEvents(args.get("events"));
                    yield toJson(tools.analyzeTemporalLogic(events));
                }

                case "check_burnout_limits" -> {
                    List<AgenticService.CalendarEventRequest> events =
                            deserialiseEvents(args.get("events"));
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

    /** Convert whatever the model sent for "events" into CalendarEventRequest list. */
    private List<AgenticService.CalendarEventRequest> deserialiseEvents(Object raw) {
        if (raw == null) return List.of();
        try {
            String json = mapper.writeValueAsString(raw);
            return mapper.readValue(json,
                    new TypeReference<List<AgenticService.CalendarEventRequest>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    /** If the agent loop ended without producing validatedEvents, keep the originals. */
    private void applyFallbackIfNeeded(ApprovalState state) {
        if (state.validatedEvents == null || state.validatedEvents.isEmpty()) {
            state.validatedEvents = state.originalEvents;
            state.changeSummary   = List.of(
                    "Agent did not produce a validated schedule; original preserved.");
        }
    }

    /** Strip optional ```json fences and find the first { ... } block. */
    private Map<String, Object> extractJson(String text) {
        if (text == null) return null;
        try {
            String cleaned = text
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) return null;
            //noinspection unchecked
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

            ── RESPONSE FORMAT (STRICT) ──────────────────────────────────────────────
            Every single response must be ONLY a raw JSON object — no prose, no markdown
            fences, no explanations before or after. Nothing outside the JSON.

            Either a tool call:
            {
              "tool_call": {
                "name": "<tool name>",
                "arguments": { ... }
              }
            }

            Or the final result (only after ALL four tools have run):
            {
              "final_result": {
                "validatedEvents": [ ... ],
                "changeSummary":   [ "bullet 1", "bullet 2", ... ]
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

            ── TOOL STEP ORDER ───────────────────────────────────────────────────────
            Step 1 — check_guide_mapping
              arguments: { "event_summaries": ["summary1", "summary2", ...] }
              Pass only summaries where extendedProperties.agent_type = "study_session".

            Step 2 — analyze_temporal_logic
              arguments: { "events": [ <full CalendarEventRequest array> ] }
              Verifies every study_session starts before its target exam.

            Step 3 — check_calendar_conflicts  (call ONCE PER EVENT)
              arguments: { "start_time": "ISO-8601", "end_time": "ISO-8601", "summary": "..." }
              If result.busy = true, shift the event by ≤ 48 hours and call again.

            Step 4 — check_burnout_limits
              arguments: { "events": [ <full CalendarEventRequest array> ] }
              Calculates duration per day from end − start. Flags days > 6 hours.

            ── AFTER ALL TOOLS PASS ──────────────────────────────────────────────────
            1. Add ONE celebration event to the validated list:
               - extendedProperties.agent_type = "celebration"
               - eventType = "default"
               - start.dateTime within 24 hours after the last exam's end.dateTime
               - duration = 2 hours, fun description
            2. Output the final_result JSON with ALL events including the celebration.
            """;
}

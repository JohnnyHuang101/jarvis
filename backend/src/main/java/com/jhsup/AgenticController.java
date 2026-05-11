package com.jhsup;

// COURSE ->> EXAMS --> STUDYPLAN -->> CALENDAR SESSIONS + STUDY GUIDE
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Files;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/users/courses/{courseId}/study_guide")
public class AgenticController {

    private final AgenticService agenticService;
    private final EmbeddingModel embeddingModel; // ✅ Add this to reconstruct Vector DBs
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ Update constructor
    public AgenticController(AgenticService agenticService, EmbeddingModel embeddingModel) {
        this.agenticService = agenticService;
        this.embeddingModel = embeddingModel;
    }

    @PostMapping("/generate") // create the study PLAN
    public ResponseEntity<List<AgenticService.ExamSegment>> generateStudyPlan(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId) {

        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.getAttribute("sub");

        File userDir = new File("user-data/" + userId + "/courses/" + courseId);

        if (!userDir.exists()) {
            userDir.mkdirs();
        }

        File scheduleFile = new File(userDir, "study_plan.json");

        ObjectMapper mapper = new ObjectMapper();

        try {
            // ✅ If file already exists → read and return it
            if (scheduleFile.exists()) {
                List<AgenticService.ExamSegment> existingPlan = mapper.readValue(
                        scheduleFile,
                        new TypeReference<List<AgenticService.ExamSegment>>() {
                        });

                return ResponseEntity.ok(existingPlan);
            }

            // 2. Fetch syllabus
            String syllabusText = agenticService.fetchSyllabusText(userId, courseId);
            System.out.println("I got the sullabustext now");

            // 3. Generate plan
            List<AgenticService.ExamSegment> studyPlan = agenticService.buildFullStudyPlan(syllabusText);

            System.out.println("I got the study plan and am gonna save it");
            // 4. Save to file
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(scheduleFile, studyPlan);

            System.out.println("saved it now");

            return ResponseEntity.ok(studyPlan);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/guide/{guideName}/exists")
    public ResponseEntity<Map<String, Boolean>> checkGuideExists(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String guideName) {
        if (principal == null)
            return ResponseEntity.status(401).build();
        String userId = principal.getAttribute("sub");

        File guideFile = new File("user-data/" + userId + "/courses/" + courseId + "/guides/" + guideName + ".md");
        return ResponseEntity.ok(Map.of("exists", guideFile.exists()));
    }

    // ✅ NEW: Fetch the actual markdown of a cached guide
    @GetMapping("/guide/{guideName}")
    public ResponseEntity<String> getExistingGuide(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String guideName) throws IOException {
        if (principal == null)
            return ResponseEntity.status(401).build();
        String userId = principal.getAttribute("sub");

        File guideFile = new File("user-data/" + userId + "/courses/" + courseId + "/guides/" + guideName + ".md");
        if (!guideFile.exists())
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok(Files.readString(guideFile.toPath()));
    }

    // ✅ UPDATED: Generate individual guide content per calendar item and save it

    // request, then check cahce. if not cache
    // 1) load user vector db ie google drive
    // 2) seach the vector db for relevant files (embeds query but this doesnt take
    // grok tokens)
    // 3) rerank documents by constructing new prompt and getitng scores
    // 4) SLEEP cause the api is exhausted
    // 5) generate study guide and return it. then frontend sleeps and cools down
    // etc etc.

    @PostMapping(value = "/content", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStudyGuideContent(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @RequestBody AgenticService.CalendarEventRequest event) {

        SseEmitter emitter = new SseEmitter(360_000L); // 6-min timeout

        if (principal == null) {
            sendEvent(emitter, "error", Map.of("state", "ERROR", "message", "Unauthorized"));
            emitter.complete();
            return emitter;
        }

        String userId = principal.getAttribute("sub");

        sseExecutor.submit(() -> {
            try {
                File guidesDir = new File("user-data/" + userId + "/courses/" + courseId + "/guides");
                if (!guidesDir.exists())
                    guidesDir.mkdirs();

                String safeName = event.summary() != null
                        ? event.summary().replaceAll("[\\\\/:*?\"<>|]", "_")
                        : "Untitled";
                File guideFile = new File(guidesDir, safeName + ".md");

                // Already cached — return immediately
                if (guideFile.exists()) {
                    sendEvent(emitter, "complete", Map.of(
                            "state", "READY",
                            "guide", Files.readString(guideFile.toPath())));
                    emitter.complete();
                    return;
                }

                sendEvent(emitter, "status", Map.of("state", "PENDING", "message", "Starting…"));

                SimpleVectorStore userVectorStore = loadUserVectorStore(userId);

                // Status callback passed into the service
                AgenticService.StatusCallback callback = (state, message, extra) -> {
                    Map<String, Object> payload = new HashMap<>(Map.of(
                            "state", state, "message", message));
                    if (extra != null)
                        payload.putAll(extra);
                    sendEvent(emitter, "status", payload);
                };

                String markdownGuide = agenticService.generateStudyGuideContent(
                        event, userVectorStore, callback);

                Files.writeString(guideFile.toPath(), markdownGuide);

                sendEvent(emitter, "complete", Map.of("state", "READY", "guide", markdownGuide));
                emitter.complete();

            } catch (Exception e) {
                sendEvent(emitter, "error", Map.of("state", "ERROR", "message",
                        e.getMessage() != null ? e.getMessage() : "Unknown error"));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            // Client disconnected — let the thread finish naturally
        }
    }

    /**
     * Endpoint: Generate Calendar Events
     * POST /api/users/courses/{courseId}/study_guide/calendar
     */
    @PostMapping("/study-plan")
    public ResponseEntity<List<AgenticService.CalendarEventRequest>> generateStudyPlanForExam(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @RequestBody AgenticService.ExamSegment exam) {
        if (principal == null)
            return ResponseEntity.status(401).build();

        String userId = principal.getAttribute("sub");

        try {
            // 1. Fetch syllabus
            String syllabus = agenticService.fetchSyllabusText(userId, courseId);

            // 2. Generate plan
            List<AgenticService.CalendarEventRequest> plan = agenticService.buildCalendarEventRequests(
                    exam,
                    syllabus,
                    "" // optional preferences for now
            );

            // 3. Persist
            File userDir = new File("user-data/" + userId + "/courses/" + courseId);
            if (!userDir.exists())
                userDir.mkdirs();

            File planFile = new File(userDir, exam.examName() + "-study-plan.json");

            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(planFile, plan);

            return ResponseEntity.ok(plan);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Helper Method: Dynamically loads the SimpleVectorStore from the user's
     * directory
     */
    private SimpleVectorStore loadUserVectorStore(String userId) {
        SimpleVectorStore vectorStore = new SimpleVectorStore(this.embeddingModel);

        // Note: Make sure this path exactly matches where you save the vectors.json!
        File vectorFile = new File("user-data/" + userId + "/vectors.json");

        if (vectorFile.exists()) {
            vectorStore.load(vectorFile);
        } else {
            throw new RuntimeException("No vector database found for user: " + userId);
        }

        return vectorStore;
    }

    // helper to get the studyplan for a certain exam and return it on GET

    @GetMapping("/study-plan/{examName}")
    public ResponseEntity<List<AgenticService.CalendarEventRequest>> getStudyPlan(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String examName) {
        if (principal == null)
            return ResponseEntity.status(401).build();

        String userId = principal.getAttribute("sub");

        try {
            File file = new File("user-data/" + userId + "/courses/" + courseId,
                    examName + "-study-plan.json");

            if (!file.exists())
                return ResponseEntity.notFound().build();

            ObjectMapper mapper = new ObjectMapper();
            List<AgenticService.CalendarEventRequest> plan = mapper.readValue(file, new TypeReference<>() {
            });

            return ResponseEntity.ok(plan);

        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
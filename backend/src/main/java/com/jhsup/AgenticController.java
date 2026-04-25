package com.jhsup;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/users/courses/{courseId}/study_guide")
public class AgenticController {

    private final AgenticService agenticService;
    private final EmbeddingModel embeddingModel; // ✅ Add this to reconstruct Vector DBs

    // ✅ Update constructor
    public AgenticController(AgenticService agenticService, EmbeddingModel embeddingModel) {
        this.agenticService = agenticService;
        this.embeddingModel = embeddingModel;
    }

    @PostMapping("/generate")
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
                List<AgenticService.ExamSegment> existingPlan =
                        mapper.readValue(
                                scheduleFile,
                                new TypeReference<List<AgenticService.ExamSegment>>() {}
                        );

                return ResponseEntity.ok(existingPlan);
            }

            // 2. Fetch syllabus
            String syllabusText = agenticService.fetchSyllabusText(userId, courseId);

            // 3. Generate plan
            List<AgenticService.ExamSegment> studyPlan =
                    agenticService.buildFullStudyPlan(syllabusText);

            // 4. Save to file
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(scheduleFile, studyPlan);

            return ResponseEntity.ok(studyPlan);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }



    @PostMapping("/content")
    public ResponseEntity<String> generateStudyGuideContent(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @RequestBody AgenticService.ExamSegment exam) {

        if (principal == null) return ResponseEntity.status(401).build();
        String userId = principal.getAttribute("sub");

        try {
            // 1. Load the user's specific vector store
            SimpleVectorStore userVectorStore = loadUserVectorStore(userId);

            // 2. Generate the markdown content
            String markdownGuide = agenticService.generateStudyGuideContent(exam, userVectorStore);

            return ResponseEntity.ok(markdownGuide);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error generating content: " + e.getMessage());
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
            @RequestBody AgenticService.ExamSegment exam
    ) {
        if (principal == null) return ResponseEntity.status(401).build();

        String userId = principal.getAttribute("sub");

        try {
            // 1. Fetch syllabus
            String syllabus = agenticService.fetchSyllabusText(userId, courseId);

            // 2. Generate plan
            List<AgenticService.CalendarEventRequest> plan =
                    agenticService.buildCalendarEventRequests(
                            exam,
                            syllabus,
                            "" // optional preferences for now
                    );

            // 3. Persist
            File userDir = new File("user-data/" + userId + "/courses/" + courseId);
            if (!userDir.exists()) userDir.mkdirs();

            File planFile = new File(userDir, exam.examName() + "-study-plan.json");

            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(planFile, plan);

            return ResponseEntity.ok(plan);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Helper Method: Dynamically loads the SimpleVectorStore from the user's directory
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








    @GetMapping("/study-plan/{examName}")
    public ResponseEntity<List<AgenticService.CalendarEventRequest>> getStudyPlan(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String courseId,
            @PathVariable String examName
    ) {
        if (principal == null) return ResponseEntity.status(401).build();

        String userId = principal.getAttribute("sub");

        try {
            File file = new File("user-data/" + userId + "/courses/" + courseId,
                    examName + "-study-plan.json");

            if (!file.exists()) return ResponseEntity.notFound().build();

            ObjectMapper mapper = new ObjectMapper();
            List<AgenticService.CalendarEventRequest> plan =
                    mapper.readValue(file, new TypeReference<>() {});

            return ResponseEntity.ok(plan);

        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
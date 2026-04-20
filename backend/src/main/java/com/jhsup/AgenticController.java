package com.jhsup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import com.fasterxml.jackson.core.type.TypeReference;

@RestController
@RequestMapping("/api/users/courses/{courseId}/study_guide")
public class AgenticController {

    private final AgenticService agenticService;

    public AgenticController(AgenticService agenticService) {
        this.agenticService = agenticService;
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
}
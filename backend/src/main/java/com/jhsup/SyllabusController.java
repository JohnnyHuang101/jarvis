package com.jhsup;

// Spring Web & HTTP
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

// Spring AI
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.document.Document;

// Security & OAuth2
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

// JSON Handling (Jackson)
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Java Utilities
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/syllabus")
public class SyllabusController {

    private final SyllabusService syllabusService;
    private final ObjectMapper objectMapper; // Spring's hyper-efficient master JSON reader

    // Inject both the service and the object mapper
    public SyllabusController(SyllabusService syllabusService, ObjectMapper objectMapper) {
        this.syllabusService = syllabusService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadSyllabus(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal OAuth2User principal) {

        try {
            // 1. Use Tika to extract documents automatically using file.getResource()
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = documentReader.get();

            // 2. Combine content for the LLM safely
            String rawSyllabusText = documents.stream()
                .map(Document::getContent)
                .filter(Objects::nonNull) // Prevents null crashes if the PDF is purely a scanned image
                .collect(Collectors.joining("\n"));

            if (rawSyllabusText.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Could not extract any text from this file.");
            }
            
            
            // Make sure we use "email" consistently across upload and getCourses
            String userId = principal.getAttribute("sub"); 

            // 3. Send to your service
            syllabusService.processSyllabus(userId, rawSyllabusText);

            syllabusService.vectorizeCourse(userId,rawSyllabusText);
            
            return ResponseEntity.ok("Syllabus processed and master schedule created for " + userId);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process syllabus: " + e.getMessage());
        }
    }

    @GetMapping("/courses")
    public ResponseEntity<List<Map<String, String>>> getCourses(@AuthenticationPrincipal OAuth2User principal){
        
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.getAttribute("sub");
        File userDir = new File("user-data/" + userId + "/courses");
        System.out.println("Looking for courses in: " + userDir.getAbsolutePath());

        List<Map<String, String>> courses = new ArrayList<>();

        if (userDir.exists() && userDir.isDirectory()) {
            File[] files = userDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".json")) {
                        try {
                            // Parse the JSON file using the injected ObjectMapper
                            JsonNode rootNode = this.objectMapper.readTree(file);

                            // Extract the fields
                            String courseName = rootNode.path("courseName").asText("Unknown Course");
                            String courseCode = rootNode.path("courseCode").asText("Unknown Code");
                            String term = rootNode.path("term").asText("Unknown Term");
                            
                            System.out.println("Parsed course: " + courseCode + " - " + courseName + " (" + term + ") from file: " + file.getName());
                            // Build the map for Vue
                            Map<String, String> courseData = new HashMap<>();
                            courseData.put("courseName", courseName);
                            courseData.put("courseCode", courseCode);
                            courseData.put("term", term);
                            courseData.put("fileName", file.getName()); 

                            courses.add(courseData);
                            
                        } catch (Exception e) {
                            System.err.println("Failed to parse course file: " + file.getName());
                        }
                    }
                }
            }
        }
        
        // This is now safely outside the 'if' block so it always returns a response!
        return ResponseEntity.ok(courses);
    }
}
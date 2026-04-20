package com.jhsup;

// Spring Web & HTTP
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

// Spring AI
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
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
            else {
                System.out.println("Extracted syllabus text: " + rawSyllabusText);
            }
            // Make sure we use "email" consistently across upload and getCourses
            String userId = principal.getAttribute("sub"); 

            // 3. Send to your service
            String courseCode = syllabusService.processSyllabus(userId, rawSyllabusText);

            syllabusService.vectorizeCourse(userId,rawSyllabusText,courseCode);
            
            return ResponseEntity.ok("Syllabus processed and master schedule created for " + userId);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process syllabus: " + e.getMessage());
        }
    }

    @GetMapping("/courses")
    public ResponseEntity<List<Map<String, String>>> getCourses(@AuthenticationPrincipal OAuth2User principal) {
        
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.getAttribute("sub");
        File coursesRootDir = new File("user-data/" + userId + "/courses");
        System.out.println("Looking for course folders in: " + coursesRootDir.getAbsolutePath());

        List<Map<String, String>> courses = new ArrayList<>();

        if (coursesRootDir.exists() && coursesRootDir.isDirectory()) {
            
            // 1. List all subdirectories (each directory is a course)
            File[] courseFolders = coursesRootDir.listFiles(File::isDirectory);
            
            if (courseFolders != null) {
                for (File courseFolder : courseFolders) {
                    
                    // The folder name is the courseCode based on your saving logic
                    String folderName = courseFolder.getName(); 
                    
                    // 2. Target the specific JSON file inside the course folder
                    File jsonFile = new File(courseFolder, folderName + "-syllabus.json");

                    if (jsonFile.exists() && jsonFile.isFile()) {
                        try {
                            // Parse the JSON file using the injected ObjectMapper
                            JsonNode rootNode = this.objectMapper.readTree(jsonFile);

                            // Extract the fields
                            String courseName = rootNode.path("courseName").asText("Unknown Course");
                            String courseCode = rootNode.path("courseCode").asText("Unknown Code");
                            String term = rootNode.path("term").asText("Unknown Term");
                            
                            System.out.println("Parsed course: " + courseCode + " - " + courseName + " (" + term + ") from file: " + jsonFile.getName());
                            
                            // Build the map for Vue
                            Map<String, String> courseData = new HashMap<>();
                            courseData.put("courseName", courseName);
                            courseData.put("courseCode", courseCode);
                            courseData.put("term", term);
                            courseData.put("fileName", jsonFile.getName()); 

                            courses.add(courseData);
                            
                        } catch (Exception e) {
                            System.err.println("Failed to parse course file: " + jsonFile.getAbsolutePath());
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println("No JSON syllabus found in folder: " + folderName);
                    }
                }
            }
        }
        
        return ResponseEntity.ok(courses);
    }
}
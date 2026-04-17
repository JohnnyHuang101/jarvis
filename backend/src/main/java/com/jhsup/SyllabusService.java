package com.jhsup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.io.File;
import java.util.List;

record SyllabusExtraction(
    String courseName,
    String courseCode,          // e.g., "CSE 515T"
    String term,                // e.g., "Spring 2026"
    InstructorInfo instructor,
    List<ClassMeeting> schedule,
    List<GradingCategory> gradingScale,
    List<CourseTopic> syllabusTopics,
    List<Assignment> assignments,
    List<Exam> exams,
    List<RequiredMaterial> requiredMaterials
) {
    // Basic instructor details for reaching out or attending office hours
    public record InstructorInfo(
        String name, 
        String email, 
        String officeLocation, 
        String officeHours
    ) {}

    // When and where the class actually happens (useful for Calendar blocking)
    public record ClassMeeting(
        String daysOfWeek,      // e.g., "Monday, Wednesday"
        String startTime, 
        String endTime, 
        String location
    ) {}

    // CRITICAL for an agent: knowing what actually impacts the final grade
    public record GradingCategory(
        String categoryName,    // e.g., "Midterms", "Homework", "Participation"
        String weightPercentage // e.g., "30%", "15%"
    ) {}

    // General flow of the course
    public record CourseTopic(
        String weekOrDate,      // e.g., "Week 1", "Jan 15 - Jan 22"
        String topicDescription
    ) {}

    // Expanded assignment record
    public record Assignment(
        String name, 
        String dueDate, 
        String format           // e.g., "PDF upload", "In-class presentation"
    ) {}

    // Expanded exam record
    public record Exam(
        String title, 
        String date, 
        String time, 
        String topicsCovered
    ) {}

    // Textbooks or software needed
    public record RequiredMaterial(
        String itemName, 
        String authorOrLink
    ) {}
}


@Service
public class SyllabusService {

    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    // Spring Boot automatically injects the ObjectMapper
    public SyllabusService(ObjectMapper objectMapper, 
                           ChatClient.Builder chatClientBuilder,
                           EmbeddingModel embeddingModel) {
        
        this.objectMapper = objectMapper;
        this.chatClient = chatClientBuilder.build(); 
        this.embeddingModel = embeddingModel;
    }


    public SyllabusExtraction extractWithLlm(String rawText) {
        var converter = new BeanOutputConverter<>(SyllabusExtraction.class);
        String formatInstructions = converter.getFormat();
    
        // 1. TWEAKED PROMPT: Aggressively tell the LLM to output ONLY JSON
        String promptText = """
            You are an academic assistant. Read the following raw syllabus text and extract the course details, schedule, grading scale, assignments, and exams.
            If a specific piece of information is not explicitly stated, leave those fields null or as an empty list. Do not guess.
            
            IMPORTANT: Your entire response MUST be valid, raw JSON. Do not include markdown code blocks (like ```json), and do not include any introductory or conversational text.
            
            Raw Syllabus Text:
            {syllabusText}
            
            {formatInstructions}
            """;
    
        PromptTemplate template = new PromptTemplate(promptText);
        template.add("syllabusText", rawText);
        template.add("formatInstructions", formatInstructions);
    
        try {
            // 2. CHECK INPUT: Make sure you are actually sending text to the LLM
            System.out.println("====== STARTING LLM EXTRACTION ======");
            System.out.println("Input text length: " + (rawText != null ? rawText.length() : "NULL"));
    
            String jsonOutput = chatClient.prompt(template.create())
                    .call()
                    .content();
    
            // 3. PRINT THE RAW OUTPUT: This is the most important step. 
            // This will show you exactly what the LLM generated before Java tries to parse it.
            System.out.println("====== RAW LLM OUTPUT ======");
            System.out.println(jsonOutput);
            System.out.println("============================");
    
            if (jsonOutput == null || jsonOutput.trim().isEmpty()) {
                System.err.println("ERROR: The LLM returned an empty response.");
                return null;
            }
    
            // 4. ATTEMPT CONVERSION
            return converter.convert(jsonOutput);
    
        } catch (Exception e) {
            // 5. CATCH PARSING ERRORS: If Jackson fails to map the JSON to your record/class, it will print here.
            System.err.println("====== JSON CONVERSION FAILED ======");
            System.err.println("Reason: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public void processSyllabus(String userId, String rawText) throws Exception{

        SyllabusExtraction extractedData = extractWithLlm(rawText);

        String courseCode = extractedData.courseCode();

        File userDir = new File("user-data/" + userId + "/courses");

        if (!userDir.exists()) {
            userDir.mkdirs(); // Notice the 's' at the end of mkdirs!
        }

        File scheduleFile = new File(userDir, courseCode + "-syllabus.json");


        objectMapper.writerWithDefaultPrettyPrinter().writeValue(scheduleFile, extractedData);
        System.out.println("Saved master schedule to: " + scheduleFile.getAbsolutePath());

        
    }



    // public Document[] searchBm25(Document[] documents, String query){
        
    //     query = query.toLowerCase();

    //     for (Document doc : documents) {
    //         String content = doc.getContent().toLowerCase();
    //         content = content.replaceAll("[^a-z0-9 ]", " "); // Remove punctuation
    //         String[] words = content.split("\\s+");
            
    //     }
    // }   


    public void vectorizeCourse(String userId, String syllabus){

        String userRootPath = "user-data" + File.separator + userId;

        SimpleVectorStore userCourses = new SimpleVectorStore(embeddingModel);
        File userVectorFile = new File(userRootPath, "vectors_courses.json");

        if (userVectorFile.exists()) {
            userCourses.load(userVectorFile);
        }

        Document syllabusDoc = new Document(syllabus);

        TokenTextSplitter chunker = new TokenTextSplitter(300, 75, 100, 1000, true);
        
        List<Document> chunks = chunker.apply(List.of(syllabusDoc));

        userCourses.add(chunks);

        userCourses.save(userVectorFile);


        System.out.println("Finished creating user courses db");
    }
}

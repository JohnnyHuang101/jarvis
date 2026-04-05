package com.jhsup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

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

    // Spring Boot automatically injects the ObjectMapper
    public SyllabusService(ObjectMapper objectMapper, 
                           ChatClient.Builder chatClientBuilder) {
        
        this.objectMapper = objectMapper;
        this.chatClient = chatClientBuilder.build(); 
    }


    public SyllabusExtraction extractWithLlm(String rawText){
        var converter = new BeanOutputConverter<>(SyllabusExtraction.class);
        String formatInstructions = converter.getFormat();

        String promptText = """
            You are an academic assistant. Read the following raw syllabus text and extract the course details, schedule, grading scale, assignments, and exams.
            If a specific piece of information (like office hours or grading scale) is not explicitly stated, leave those fields null or as an empty list. Do not guess.
            
            Raw Syllabus Text:
            {syllabusText}
            
            {formatInstructions}
            """;

        PromptTemplate template = new PromptTemplate(promptText);
        template.add("syllabusText", rawText);
        template.add("formatInstructions", formatInstructions);

        String jsonOutput = chatClient.prompt(template.create())
                .call()
                .content();

        return converter.convert(jsonOutput);
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
}
                        
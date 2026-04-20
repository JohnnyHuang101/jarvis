package com.jhsup;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Service 
public class AgenticService {
    
    private final ChatClient chatClient;

    public AgenticService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Orchestrator: Chains the agentic steps together.
     * Extracts topics first, then passes them to the study plan generator.
     */
    public List<ExamSegment> buildFullStudyPlan(String syllabusContext) {
        // Step 1: Extract all topics
        List<String> extractedTopics = extractTopics(syllabusContext);
        
        // Step 2: Allocate topics to exam dates
        List<ExamSegment> content =  createStudyPlan(syllabusContext, extractedTopics);
        
        return content; 
    }

    /**
     * Agent Step 1: Extracts a clean, sequential list of topics from the raw text.
     */
    public List<String> extractTopics(String syllabusContext) {
        String systemPrompt = """
            You are an academic parsing agent. Your exact goal is to extract a comprehensive, sequential list of all units, chapters, or topics covered in the provided syllabus.
            
            Rules:
            1. Return ONLY the topics as a flat list.
            2. Preserve the chronological order of the topics as presented in the syllabus.
            3. Do not include dates, administrative information, or exam schedules in this list.
            4. Format each entry to be concise but descriptive (e.g., "Unit 1: Vector Databases" rather than just "Unit 1").
            """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(syllabusContext)
                .call()
                .entity(new ParameterizedTypeReference<List<String>>() {});
    }

    /**
     * Agent Step 2: Analyzes syllabus context and topics to generate a structured study plan.
     */
    public List<ExamSegment> createStudyPlan(String syllabusContext, List<String> allTopics) {
        String systemPrompt = """
            You are an academic planning agent. Your objective is to extract exam dates and assign study topics based on the provided syllabus context.
            
            Strict Rules for Execution:
            1. Extract all exam, midterm, and final dates from the context.
            2. If the context explicitly maps specific units or topics to specific exam dates, use that exact mapping.
            3. All provided topics MUST be completely allocated before the final exam.
            4. If the topic distribution is NOT explicitly specified in the context, you must divide the total list of topics evenly across the number of identified test segments.
            """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("Syllabus Context: {context}\nTopics to cover: {topics}")
                        .param("context", syllabusContext)
                        .param("topics", String.join(", ", allTopics)))
                .call()
                .entity(new ParameterizedTypeReference<List<ExamSegment>>() {});
    }



    public record ExamSegment(
            String examName,
            String date,
            List<String> unitsCovered,
            String metaInformation
    ) {}

    public String fetchSyllabusText(String userId, String courseCode) {
        try{
            Path file = Paths.get("user-data", userId, "courses", courseCode, "syllabus-raw.txt");

            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read raw syllabus for course: " + courseCode, e);
        }
    }
}
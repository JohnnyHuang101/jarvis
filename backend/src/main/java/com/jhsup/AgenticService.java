package com.jhsup;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import java.util.stream.Collectors;

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


    public record CalendarEventRequest(
        String summary,
        String description,
        String location,
        Time start,
        Time end,

        List<String> recurrence,

        Reminders reminders,

        String eventType, // "default" or "focusTime"

        Map<String, String> extendedProperties // your agent metadata
    ) {

        public record Time(
            String dateTime,   // "2026-04-22T18:00:00"
            String timeZone    // "America/Los_Angeles"
        ) {}

        public record Reminders(
            boolean useDefault,
            List<Override> overrides
        ) {}

        public record Override(
            String method, // "popup" or "email"
            int minutes
        ) {}
    }

    public String generateStudyGuideContent(ExamSegment exam, SimpleVectorStore vectorStore) {
        // 1. Construct a search query from the exam's context
        String searchQuery = String.format("Topics: %s. Context: %s", 
                String.join(", ", exam.unitsCovered()), 
                exam.metaInformation());

        // 2. Query the Vector Database for relevant course material
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.query(searchQuery).withTopK(10) // Adjust K based on your chunk size
        );

        // 3. Flatten the retrieved documents into a single context string
        String vectorDbContext = relevantDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. Pass everything to the LLM to generate the study guide
        String systemPrompt = """
            You are an expert academic tutor. Your task is to create a comprehensive study guide 
            for an upcoming exam based ONLY on the provided course material chunks.
            
            Format your response in clean Markdown. Include:
            - A brief overview of the exam topics.
            - Detailed summaries of the core concepts found in the context.
            - Key formulas, definitions, or dates if applicable.
            """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("Exam Name & Topics: {exam}\n\nCourse Material Context:\n{context}")
                        .param("exam", exam.examName() + " - " + String.join(", ", exam.unitsCovered()))
                        .param("context", vectorDbContext))
                .call()
                .content(); 
    }


    /**
     * Generates a multi-day study schedule for a specific exam.
     * Takes the specific exam segment, the full syllabus context, and outputs a List of events.
     */
    public List<CalendarEventRequest> buildCalendarEventRequests(ExamSegment exam, String fullSyllabusContext, String userPreferencesContext) {
        String systemPrompt = """
            You are an intelligent scheduling agent. Your task is to analyze an overall course syllabus 
            and create a multi-day study plan for ONE specific upcoming exam.
            
            You must logically divide the units required for this exam across multiple study sessions 
            leading up to the exam date.
            
            Strict Rules for mapping to the JSON schema:
            1. 'summary': Create a clear event title (e.g., "Exam Prep: [Specific Topic]").
            2. 'description': Detail the specific units to study during this particular session.
            3. 'start' and 'end': STRICTLY ISO-8601 format (e.g., "2026-04-22T18:00:00"). 
               - Ensure dates are scheduled logically BEFORE the exam date.
               - Assume the timezone is "America/Los_Angeles" unless otherwise stated.
            4. 'reminders': Set useDefault to true, or provide sensible overrides (e.g., popup 60 mins before).
            5. 'eventType': Set to "focusTime".
            6. 'extendedProperties': Include metadata like "target_exam" or "agent_type" as key-value pairs.

            Output a JSON Array containing all the scheduled study sessions.
            """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("Target Exam Segment: {segment}\nFull Course Syllabus: {syllabus}\nUser Scheduling Preferences: {preferences}")
                        .param("segment", exam.toString()) 
                        .param("syllabus", fullSyllabusContext)
                        .param("preferences", userPreferencesContext))
                .call()
                // Maps the LLM's JSON array output directly into a List of your Java Records
                .entity(new ParameterizedTypeReference<List<CalendarEventRequest>>() {}); 
    }
}
package com.jhsup;

import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

public class ChatComplete {

    private static final String API_KEY = System.getenv("OPENAI_KEY");
    private static final String QDRANT_URL = "http://localhost:6333";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String CHAT_MODEL = "gpt-4o-mini"; // Use "gpt-4o" for smarter, costlier results
    private static final String COLLECTION_NAME = "class_notes";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    // --- Step 1: Turn User Question into Vector ---
    public static List<Double> getEmbedding(String text) throws Exception {
        Map<String, Object> json = new HashMap<>();
        json.put("input", text);
        json.put("model", EMBEDDING_MODEL);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .post(RequestBody.create(mapper.writeValueAsString(json), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + API_KEY)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Embedding failed: " + response.body().string());
            Map<String, Object> res = mapper.readValue(response.body().string(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) res.get("data");
            return (List<Double>) data.get(0).get("embedding");
        }
    }

    // --- Step 2: Search Qdrant for Context ---
    public static String retrieveContext(String queryText) throws Exception {
        List<Double> queryVector = getEmbedding(queryText);

        // Construct Qdrant Search Body (Using Named Vector "embedding")
        Map<String, Object> searchBody = new HashMap<>();
        searchBody.put("vector", new HashMap<String, Object>() {{
            put("name", "embedding");
            put("vector", queryVector);
        }});
        searchBody.put("limit", 20); // Get top 5 most relevant chunks
        searchBody.put("with_payload", true);

        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + COLLECTION_NAME + "/points/search")
                .post(RequestBody.create(mapper.writeValueAsString(searchBody), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Search failed: " + response.body().string());
            
            Map<String, Object> resMap = mapper.readValue(response.body().string(), Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) resMap.get("result");

            StringBuilder contextBuilder = new StringBuilder();
            System.out.println("\n--- Retrieved Context Sources ---");
            
            for (Map<String, Object> result : results) {
                Map<String, Object> payload = (Map<String, Object>) result.get("payload");
                String text = (String) payload.get("text_content");//actual chunk of note used per file
                String filename = (String) payload.get("filename");
                double score = (double) result.get("score");
                
                System.out.printf("Score: %.4f | File: %s | Chunk: %s\n", score, filename,text);

                
                // Append to the big context string
                if (score>0.2){
                    contextBuilder.append("Source (").append(filename).append("):\n");
                    contextBuilder.append(text).append("\n\n");
                }
            }
            return contextBuilder.toString();
        }
    }

    // --- Step 3: Ask ChatGPT with Context ---
    public static String askGPT(String userQuery) throws Exception {
        // 1. Get the relevant notes
        String retrievedContext = retrieveContext(userQuery);

        if (retrievedContext.isEmpty()) {
            return "I couldn't find any relevant notes in your database.";
        }

        // 2. Construct the System Prompt (The "Rules")
        String systemPrompt = "You are an expert study assistant. You are tasked with creating a structured study guide with bullet points for the user's provided TOPIC." +
                "Answer the user's question using ONLY the provided CONTEXT below. " +
                "If the context doesn't contain the answer, say the question is off topic and that youre only used to create study guide notes ";

        // 3. Construct the User Prompt (Context + Query)
        String finalUserMessage = "CONTEXT:\n" + retrievedContext + "\n\nUSER TOPIC:\n" + userQuery;

        // 4. Build JSON for OpenAI Chat API
        Map<String, Object> messageSystem = new HashMap<>();
        messageSystem.put("role", "system");
        messageSystem.put("content", systemPrompt);

        Map<String, Object> messageUser = new HashMap<>();
        messageUser.put("role", "user");
        messageUser.put("content", finalUserMessage);

        Map<String, Object> json = new HashMap<>();
        json.put("model", CHAT_MODEL);
        json.put("messages", Arrays.asList(messageSystem, messageUser));

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(json), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + API_KEY)
                .build();

        System.out.println("\n--- Thinking... ---");
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Chat failed: " + response.body().string());
            
            Map<String, Object> res = mapper.readValue(response.body().string(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) res.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            
            return (String) message.get("content");
        }
    }

    // --- Main Entry Point ---
    public static void main(String[] args) throws Exception {
        // You can change this question to anything!
        String question = "Create a study guide on topics of mathematical reductions and NP completeness please";
        
        // If command line args are provided, use those instead
        if (args.length > 0) {
            question = String.join(" ", args);
        }

        System.out.println("Question: " + question);
        String answer = askGPT(question);
        
        System.out.println("\n=== ANSWER ===\n");
        System.out.println(answer);
    }
}
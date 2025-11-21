package com.jhsup.ProcessingCode;

import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;

public class TestSearch {

    private static final String API_KEY = System.getenv("OPENAI_KEY");
    private static final String QDRANT_URL = "http://localhost:6333";
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    // 1. Helper to get embedding for the query text (Same as before)
    public static List<Double> getEmbedding(String text) throws Exception {
        Map<String, Object> json = new HashMap<>();
        json.put("input", text);
        json.put("model", "text-embedding-3-small");

        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(json),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .post(body)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("OpenAI Error: " + response.body().string());
            Map<String, Object> res = mapper.readValue(response.body().string(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) res.get("data");
            return (List<Double>) data.get(0).get("embedding");
        }
    }

    // 2. The Search Logic
    public static void searchQdrant(String collectionName, String queryText) throws Exception {
        System.out.println("Generating embedding for query: \"" + queryText + "\"...");
        List<Double> queryVector = getEmbedding(queryText);

        // Construct the Search Request Body
        Map<String, Object> searchBody = new HashMap<>();
        // IMPORTANT: Use the named vector "embedding" to match your schema
        searchBody.put("vector", new HashMap<String, Object>() {{
            put("name", "embedding");
            put("vector", queryVector);
        }});
        searchBody.put("limit", 10); // Return top 3 results
        searchBody.put("with_payload", true); // Show the filename/chunk info

        String jsonBody = mapper.writeValueAsString(searchBody);

        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + collectionName + "/points/search")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        System.out.println("Searching Qdrant...");
        try (Response response = client.newCall(request).execute()) {
            String resBody = response.body().string();
            if (!response.isSuccessful()) {
                System.err.println("Search Failed: " + resBody);
                return;
            }

            // Pretty print the results
            System.out.println("\n--- Top Results ---");
            Map<String, Object> responseMap = mapper.readValue(resBody, Map.class);

            // The actual results list is nested under the "result" key.
            List<Map<String, Object>> results = (List<Map<String, Object>>) responseMap.get("result");            
            if (results.isEmpty()) {
                System.out.println("No results found.");
            }

            for (Map<String, Object> result : results) {
                double score = (double) result.get("score");
                Map<String, Object> payload = (Map<String, Object>) result.get("payload");
                String filename = (String) payload.get("filename");
                String chunk = (String) payload.get("text_content");
                
                System.out.printf("Score: %.4f | File: %s | Chunk: %s\n", score, filename,chunk);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Run the search
        searchQdrant("class_notes", "reduction proofs");
    }
}
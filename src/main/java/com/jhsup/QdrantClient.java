package com.jhsup;

import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;

public class QdrantClient {
    private static final String QDRANT_URL = "http://localhost:6333";
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    // --- Core API Methods ---

    /**
     * Checks if a collection exists by making a GET request.
     */
    public static boolean checkCollection(String collectionName) throws Exception {
        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + collectionName)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true; // HTTP 200 OK
            } else if (response.code() == 404) {
                return false; // HTTP 404 Not Found
            } else {
                String resBody = response.body() != null ? response.body().string() : "";
                throw new Exception("Qdrant collection check failed: HTTP " + response.code() + " - " + resBody);
            }
        }
    }

    /**
     * Creates a new collection with specified vector parameters.
     */
    public static void createCollection(
            String collectionName, int vectorSize, String distanceMetric
    ) throws Exception {

        Map<String, Object> vectorSpec = new HashMap<>();
        vectorSpec.put("size", vectorSize);
        vectorSpec.put("distance", distanceMetric);

        Map<String, Object> vectors = new HashMap<>();
        vectors.put("embedding", vectorSpec); // <-- vector field name

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("vectors", vectors); // <-- correct top-level key

        String jsonBody = mapper.writeValueAsString(requestBodyMap);
        System.out.println("Creating collection with body:\n" + jsonBody);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + collectionName)
                .put(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String resBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new Exception("Qdrant collection creation failed: HTTP " + response.code() + " - " + resBody);
            }
            System.out.println("Collection '" + collectionName + "' created successfully: " + resBody);
        }
    }


    /**
     * Inserts (Upserts) a single vector point into a Qdrant collection.
     * NOTE: This is the low-level API call and assumes the collection exists.
     */
    public static void insertVector(
            String collectionName, long id, List<Double> embedding,
            String filename, int chunkIndex
    ) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", filename);
        payload.put("chunk_index", chunkIndex);

        // --- FIX STARTS HERE ---
        // 1. Create a map to hold the named vector
        Map<String, Object> vectorsMap = new HashMap<>();
        
        // 2. Use the exact name found in your schema: "embedding"
        vectorsMap.put("embedding", embedding); 

        Map<String, Object> pointObj = new HashMap<>();


        pointObj.put("id", id);        
        pointObj.put("vectors", vectorsMap); 
        pointObj.put("payload", payload);

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("points", Arrays.asList(pointObj));

        String jsonBody = mapper.writeValueAsString(requestBodyMap);

        // Debug print (Optional)
        // System.out.println("Sending Body: " + jsonBody);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + collectionName + "/points?wait=true")
                .put(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String resBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                throw new Exception("Qdrant insertion failed: HTTP " + response.code() + " - " + resBody);
            }
            System.out.println("Qdrant Insertion Response:\n" + resBody);
        }
    }

    // --- Combined/User-Facing Methods ---

    /**
     * Public user-facing method that ensures the collection exists before inserting the vector.
     * @param collectionName The name of the collection.
     * @param vectorSize The dimensionality (required for auto-creation).
     * @param distanceMetric The distance function (required for auto-creation).
     * @param id The ID for the new point.
     * @param embedding The vector data.
     * @param filename Metadata.
     * @param chunkIndex Metadata.
     * @throws Exception if the collection cannot be created or the insertion fails.
     */
    public static void upsertVectorWithCollectionCheck(
            String collectionName, int vectorSize, String distanceMetric,
            long id, List<Double> embedding, String filename, int chunkIndex
    ) throws Exception {
        if (!checkCollection(collectionName)) {
            System.out.println("Collection '" + collectionName + "' not found. Attempting to create it...");
            createCollection(collectionName, vectorSize, distanceMetric);
        }
        // Now that the collection is guaranteed to exist, perform the insertion
        insertVector(collectionName, id, embedding, filename, chunkIndex);
    }

    /**
     * Performs a similarity search on a collection.
     * @param collectionName The collection to search.
     * @param queryEmbedding The vector to search with.
     * @param limit The maximum number of results to return.
     * @return The raw JSON response string from Qdrant.
     * @throws Exception if the search fails.
     */
    public static String search(
            String collectionName, List<Double> queryEmbedding, int limit
    ) throws Exception {
        
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("vector", queryEmbedding);
        requestBodyMap.put("limit", limit);
        requestBodyMap.put("with_payload", true); // Include metadata in results

        String jsonBody = mapper.writeValueAsString(requestBodyMap);
        
        System.out.println("Search Request Body:\n" + jsonBody);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(QDRANT_URL + "/collections/" + collectionName + "/points/search") 
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String resBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                throw new Exception("Qdrant search failed: HTTP " + response.code() + " - " + resBody);
            }
            return resBody;
        }
    }
}
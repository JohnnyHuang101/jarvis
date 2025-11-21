package com.jhsup.ProcessingCode;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import java.util.concurrent.TimeUnit;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;
import com.fasterxml.jackson.databind.*;

public class DocumentProcessor {

    // --- Configuration ---
    // NOTE: text-embedding-3-small default size is 1536.
    private static final int VECTOR_SIZE = 1536; 
    private static final String COLLECTION_NAME = "class_notes";
    private static final String DISTANCE_METRIC = "Cosine";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    
    // Globally unique ID generator starting from a high number to avoid clashes with existing points
    private static final AtomicLong ID_COUNTER = new AtomicLong(System.currentTimeMillis() * 100);
    
    // Fetches the API key from environment variables
    private static final String API_KEY = System.getenv("OPENAI_KEY");

    // ------------------- Text Extraction -------------------
    public static String extractTextPdf(String filePath) throws Exception {
        // Use try-with-resources to ensure PDDocument is closed
        try (PDDocument document = PDDocument.load(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    public static String extractTextDocx(String filePath) throws Exception {
        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    public static String extractTextPptx(String filePath) throws Exception {
        try (FileInputStream fis = new FileInputStream(filePath);
            XMLSlideShow ppt = new XMLSlideShow(fis)) {
            
            StringBuilder sb = new StringBuilder();
            
            for (XSLFSlide slide : ppt.getSlides()) {
                // 1. Append the slide title (if it exists)
                if (slide.getTitle() != null) {
                    sb.append(slide.getTitle()).append("\n");
                }

                // 2. Iterate through all shapes on the slide
                slide.getShapes().forEach(shape -> {
                    // Check if the shape is a text-holding shape
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        // Cast the generic shape to XSLFTextShape
                        org.apache.poi.xslf.usermodel.XSLFTextShape textShape = 
                            (org.apache.poi.xslf.usermodel.XSLFTextShape) shape;
                        
                        String shapeText = textShape.getText();
                        
                        if (shapeText != null && !shapeText.trim().isEmpty()) {
                            sb.append(shapeText).append("\n");
                        }
                    }
                });
            }
            return sb.toString();
        }
    }

    // ------------------- Text Splitting -------------------
    // (This method remains unchanged and is correct)
    public static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start += (chunkSize - overlap);
        }
        return chunks;
    }

    // ------------------- OpenAI Embeddings -------------------
    public static List<Double> getEmbedding(String text) throws Exception {
        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("OPENAI_KEY environment variable is not set.");
        }

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // Wait up to 60s to connect
            .readTimeout(30, TimeUnit.SECONDS)    // Wait up to 60s for data
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> json = new HashMap<>();
        json.put("input", text);
        json.put("model", EMBEDDING_MODEL);

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
            String resBody = response.body().string();
            
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI API call failed: HTTP " + response.code() + " - " + resBody);
            }
            
            Map<String, Object> res = mapper.readValue(resBody, Map.class);
            // Safely extract the embedding list
            List<Map<String, Object>> data = (List<Map<String, Object>>) res.get("data");
            if (data == null || data.isEmpty()) {
                 throw new IOException("OpenAI response did not contain embedding data.");
            }
            
            // Note: If you reduce the embedding size in the request (using "dimensions"), 
            // you must update VECTOR_SIZE at the top of this class.
            return (List<Double>) data.get(0).get("embedding");
        }
    }

    // ------------------- Main Pipeline -------------------
    public static void processFolder(String folderPath) throws Exception {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("Folder not found: " + folderPath);
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                processFolder(file.getAbsolutePath()); // recursive for subfolders
                continue;
            }

            String text = "";
            String name = file.getName().toLowerCase();
            try {
                if (name.endsWith(".pdf")) {
                    text = extractTextPdf(file.getAbsolutePath());
                } else if (name.endsWith(".docx")) {
                    text = extractTextDocx(file.getAbsolutePath());
                } else if (name.endsWith(".pptx")) {
                    text = extractTextPptx(file.getAbsolutePath());
                } else {
                    System.out.println("Skipping unsupported file: " + file.getName());
                    continue;
                }
            } catch (Exception e) {
                System.out.println("Error reading file: " + file.getName() + " -> " + e.getMessage());
                continue;
            }
            
            // Check if file is empty
            if (text.trim().isEmpty()) {
                System.out.println("Skipping empty file: " + file.getName());
                continue;
            }

            List<String> chunks = splitText(text, 500, 50); // chunk size 500, overlap 50

            for (int i = 0; i < chunks.size(); i++) {
                List<Double> embedding = getEmbedding(chunks.get(i));
                
                // --- FIX: Using the robust upsert method and unique ID ---
                long pointId = ID_COUNTER.incrementAndGet(); // Get a unique, atomic ID
                
                QdrantClient.upsertVectorWithCollectionCheck(
                    COLLECTION_NAME, 
                    VECTOR_SIZE, 
                    DISTANCE_METRIC, 
                    pointId, 
                    embedding, 
                    file.getName(), // Use the actual filename from the loop
                    i,
                    chunks.get(i)
                );
                // --------------------------------------------------------

                System.out.println("Processed chunk " + i + " (ID: " + pointId + ") from " + file.getName());
            }
        }
    }

    // ------------------- Main -------------------
    public static void main(String[] args) throws Exception {
        System.out.println("--- Starting Document Ingestion into Qdrant ---");
        System.out.println("Collection: " + COLLECTION_NAME + ", Embedding Model: " + EMBEDDING_MODEL);
        
        String downloadsFolder = "./downloads_";
        processFolder(downloadsFolder);
        
        System.out.println("\n--- All files processed! Total vectors upserted: " + (ID_COUNTER.get() - (System.currentTimeMillis() * 100)) + " ---");
    }
}
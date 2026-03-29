package com.jhsup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.ai.vectorstore.VectorStore;


import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;


import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.springframework.ai.vectorstore.SearchRequest;


//17106087
@Service
public class UserCreate {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final EmbeddingModel embeddingModel; 

    public UserCreate(OAuth2AuthorizedClientService authorizedClientService, EmbeddingModel embeddingModel) {
        this.authorizedClientService = authorizedClientService;
        this.embeddingModel = embeddingModel;
    }

    // ==========================================
    // THE 3 GETTERS (State Checkers)
    // ==========================================

    public boolean isFolderCreated(String userId) {
        return new File("user-data", userId).exists();
    }

    public boolean areFilesPulled(String userId) {
        return new File("user-data" + File.separator + userId, ".files_pulled").exists();
    }

    public boolean isVectorized(String userId) {
        return new File("user-data" + File.separator + userId, ".vectorized").exists();
    }

    // ==========================================
    // THE 3 SETUP FUNCTIONS (Writers)
    // ==========================================

    // 1. Make the Folder
    public String createFolders(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        File folder = new File("user-data", userId);

        if (!folder.exists()) {
            folder.mkdirs();
            new File(folder, "Documents").mkdir();
            new File(folder, "Images").mkdir();
            new File(folder, "Others").mkdir();
            System.out.println("Stage 1 Complete: Folders created for " + principal.getAttribute("email"));
        }
        return folder.getAbsolutePath();
    }

    // 2. Pull All Files
    // 2. Pull All Files
    private void pullFiles(OAuth2User principal, String userRootPath, String userId) throws Exception {
        System.out.println("Starting Stage 2: Pulling Google Drive Files for " + principal.getAttribute("email") + "...");
        
        // 1. Get the Google Access Token
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient("google", principal.getName());
        if (client == null || client.getAccessToken() == null) {
            throw new Exception("Could not retrieve Google Access Token for user.");
        }
        String accessToken = client.getAccessToken().getTokenValue();

        // 2. Initialize the Drive API Client
        Drive driveService = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                request -> request.getHeaders().setAuthorization("Bearer " + accessToken))
                .setApplicationName("Jarvis-Drive-Fetcher")
                .build();
        
        String pageToken = null;
        int fileCount = 0;

        // 3. Loop through the user's Drive, 100 files at a time
        do {
            FileList result = driveService.files().list()
                    .setPageSize(100)
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .setPageToken(pageToken)
                    .execute();

            // We use the fully qualified name here to avoid clashing with java.io.File
            List<com.google.api.services.drive.model.File> driveFiles = result.getFiles();
            
            if (driveFiles == null || driveFiles.isEmpty()) {
                break; // No more files to process
            }

            for (com.google.api.services.drive.model.File googleFile : driveFiles) {
                String mimeType = googleFile.getMimeType();

                if (!mimeType.contains("pdf") && !mimeType.contains("word") && !mimeType.contains("text")){
                    continue;
                }

                String folderName = "Documents"; // Default folder

                // Create the exact local path for this file
                File localDestination = new File(userRootPath + File.separator + folderName, googleFile.getName());
                
                // 5. Download the file bytes
                try (FileOutputStream outputStream = new FileOutputStream(localDestination)) {
                    driveService.files().get(googleFile.getId()).executeMediaAndDownloadTo(outputStream);
                    fileCount++;
                } catch (Exception e) {
                    // Native Google Docs/Sheets throw an error here because they require a special "export" call.
                    // We catch it so it skips the file without breaking the entire initialization.
                    System.err.println("Skipping file (likely native Google format): " + googleFile.getName());
                }
            }

            // Get the token for the next 100 files
            pageToken = result.getNextPageToken();

        } while (pageToken != null); 
        
        // 6. Write the completion marker to the hard drive
        new File(userRootPath, ".files_pulled").createNewFile();
        System.out.println("Stage 2 Complete: " + fileCount + " files successfully pulled and marker saved.");
    }


    // ==========================================
    // THE MASTER ASYNC PIPELINE
    // ==========================================
    
    @Async
    public void runInitializationPipeline(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        String userRootPath = "user-data" + File.separator + userId;

        try {
            // Check Stage 2
            if (!areFilesPulled(userId)) {
                pullFiles(principal, userRootPath, userId);
            } else {
                System.out.println("Skipping Stage 2: Files already pulled.");
            }

            // Check Stage 3
            if (!isVectorized(userId)) {
                vectorizeDocuments(userRootPath, userId);
            } else {
                System.out.println("Skipping Stage 3: Documents already vectorized.");
            }

            System.out.println("Jarvis Initialization 100% Complete for user: " + userId);

        } catch (Exception e) {
            System.err.println("Pipeline failed: " + e.getMessage());
            // Optional: You could create a ".error" file here if you want the frontend to show a red X
        }
    }



    // 3. Vectorize
    private void vectorizeDocuments(String userRootPath, String userId) throws Exception {
        System.out.println("Starting Stage 3: Vectorizing documents for user: " + userId);
        
        File docsFolder = new File(userRootPath + File.separator + "Documents");
        File[] files = docsFolder.listFiles();

        if (files == null || files.length == 0) {
            System.out.println("No documents found to vectorize.");
            new File(userRootPath, ".vectorized").createNewFile();
            return;
        }

        // 1. Create a brand new, isolated vector store JUST for this user
        SimpleVectorStore userVectorStore = new SimpleVectorStore(embeddingModel);
        File userVectorFile = new File(userRootPath, "vectors.json");

        // 2. If they already have a vector file, load it first so we don't overwrite it
        if (userVectorFile.exists()) {
            userVectorStore.load(userVectorFile);
        }

        TokenTextSplitter chunker = new TokenTextSplitter(800, 350, 100, 10000, true);

        for (File file : files) {
            System.out.println("Processing: " + file.getName());
            try {
                TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
                List<Document> rawDocuments = reader.get();

                // Even with physical isolation, tagging metadata is still a good safety practice
                for (Document doc : rawDocuments) {
                    doc.getMetadata().put("fileName", file.getName());
                }

                List<Document> chunkedDocuments = chunker.apply(rawDocuments);
                
                // Add to this specific user's temporary memory
                userVectorStore.add(chunkedDocuments); 
                
            } catch (Exception e) {
                System.err.println("Failed to vectorize file " + file.getName() + ": " + e.getMessage());
            }
        }

        // 3. Save the memory down to their physical folder
        userVectorStore.save(userVectorFile);
        System.out.println("User vector database saved to: " + userVectorFile.getAbsolutePath());

        // Write the completion marker
        new File(userRootPath, ".vectorized").createNewFile();
        System.out.println("Stage 3 Complete: Documents vectorized and marker saved.");
    }



    public String retrieveContext(String queryText, String userId) throws Exception {


        File userVectors = new File("user-data/" + userId + "vectors.json");

        // if(!userVectorFile.exists()){
        //     return "";
        // }

        try{
            SimpleVectorStore userStore = new SimpleVectorStore(embeddingModel);

            userStore.load(userVectors);

            SearchRequest request = SearchRequest.query(queryText)
                .withTopK(5)
                .withSimilarityThreshold(0.7);

            List<Document> similarDocs = userStore.similaritySearch(request);
            return similarDocs.stream()
                    .map(Document::getContent)
                    .collect(Collectors.joining("\n\n--- Document Chunk ---\n\n"));

        } catch (Exception e) {
            System.err.println("Search failed for user " + userId + ": " + e.getMessage());
            return "";
        }

    }


}
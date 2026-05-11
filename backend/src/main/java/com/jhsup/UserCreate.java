package com.jhsup;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;

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
        System.out
                .println("Starting Stage 2: Pulling Google Drive Files for " + principal.getAttribute("email") + "...");

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

                if (!mimeType.contains("pdf") && !mimeType.contains("word") && !mimeType.contains("text")) {
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
                    // Native Google Docs/Sheets throw an error here because they require a special
                    // "export" call.
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
            // Optional: You could create a ".error" file here if you want the frontend to
            // show a red X
        }
    }

    // 3. Vectorize
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

        SimpleVectorStore userVectorStore = new SimpleVectorStore(embeddingModel);
        File userVectorFile = new File(userRootPath, "vectors.json");

        if (userVectorFile.exists()) {
            userVectorStore.load(userVectorFile);
        }

        TokenTextSplitter chunker = new TokenTextSplitter(800, 350, 100, 10000, true);

        // --- PARALLEL PROCESSING SETUP ---
        // Spawn a thread pool. 10 workers is a good starting point.
        // If you are using Java 21+, you can use
        // Executors.newVirtualThreadPerTaskExecutor() instead!
        ExecutorService executor = Executors.newFixedThreadPool(3);

        System.out.println("Spawning workers to process " + files.length + " files in parallel...");

        // Map each file to an asynchronous task
        List<CompletableFuture<Void>> futures = Arrays.stream(files)
                .map(file -> CompletableFuture.runAsync(() -> {
                    System.out.println(
                            "Processing: " + file.getName() + " on thread: " + Thread.currentThread().getName());
                    try {
                        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
                        List<Document> rawDocuments = reader.get();

                        for (Document doc : rawDocuments) {
                            doc.getMetadata().put("fileName", file.getName());
                        }

                        List<Document> chunkedDocuments = chunker.apply(rawDocuments);

                        // SimpleVectorStore uses a ConcurrentHashMap internally, so concurrent adds are
                        // generally safe.
                        userVectorStore.add(chunkedDocuments);

                    } catch (Exception e) {
                        System.err.println("Failed to vectorize file " + file.getName() + ": " + e.getMessage());
                    }
                }, executor))
                .collect(Collectors.toList());

        // Wait for ALL worker threads to finish before moving on
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Shut down the thread pool
        executor.shutdown();
        // ---------------------------------

        // 3. Save the memory down to their physical folder
        userVectorStore.save(userVectorFile);
        System.out.println("User vector database saved to: " + userVectorFile.getAbsolutePath());

        // Write the completion marker
        new File(userRootPath, ".vectorized").createNewFile();
        System.out.println("Stage 3 Complete: Documents vectorized and marker saved.");
    }

    public String retrieveContext(String queryText, String userId) throws Exception {

        File userVectors = new File("user-data/" + userId + "/vectors.json");

        // if(!userVectorFile.exists()){
        // return "";
        // }

        try {
            SimpleVectorStore userStore = new SimpleVectorStore(embeddingModel);

            userStore.load(userVectors);

            SearchRequest request = SearchRequest.query(queryText)
                    .withTopK(5);
            // .withSimilarityThreshold(0.7);

            // 1. Change the call back to similaritySearch
            List<Document> similarDocs = userStore.similaritySearch(request);

            // 2. Access the score from Metadata
            similarDocs.forEach(doc -> {
                // In SimpleVectorStore, the key is usually "distance" or "similarityScore"
                // Let's print the whole map to see exactly what's inside
                System.out.println("Metadata keys: " + doc.getMetadata().keySet());

                Double score = (Double) doc.getMetadata().get("distance");
                System.out.println("Score: " + score + " | ID: " + doc.getId());
            });

            return similarDocs.stream()
                    .map(Document::getContent)
                    .collect(Collectors.joining("\n\n--- Document Chunk ---\n\n"));

        } catch (Exception e) {
            System.err.println("Search failed for user " + userId + ": " + e.getMessage());
            return "";
        }

    }

}

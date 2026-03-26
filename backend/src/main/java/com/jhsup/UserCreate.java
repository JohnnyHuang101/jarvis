

package com.jhsup;

import java.io.File; // Standard Java File
import java.io.FileOutputStream; // For downloading
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class UserCreate {

    private final OAuth2AuthorizedClientService authorizedClientService;
    
    // Tracks initialization stage by Google User ID (sub)
    // 1 = Folders, 2 = Downloading, 3 = Embedding, 4 = Ready, -1 = Error
    private final Map<String, Integer> userProgressMap = new ConcurrentHashMap<>();

    public UserCreate(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    // --- GETTER FOR THE FRONTEND TO POLL ---
    public int getUserProgress(String userId) {
        return userProgressMap.getOrDefault(userId, 0); // Default to 0 if not found
    }

    // --- STAGE 1: The Initial Sync Call ---
    public String getOrCreateUserFolder(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        File folder = new File("user-data", userId);

        if (!folder.exists()) {
            folder.mkdirs();
            new File(folder, "Documents").mkdir();
            new File(folder, "Images").mkdir();
            new File(folder, "Others").mkdir();
        }
        
        // Mark Stage 1 complete!
        userProgressMap.put(userId, 1);
        return folder.getAbsolutePath();
    }

    // --- STAGES 2 & 3: The Async Master Pipeline ---
    @Async
    public void runInitializationPipeline(OAuth2User principal, String userRootPath) {
        String userId = principal.getAttribute("sub");
        
        try {
            // Stage 1 (Folder created) is already done by the Controller
            userProgressMap.put(userId, 1);

            // Stage 2: Download
            populateFiles(principal, userRootPath);
            userProgressMap.put(userId, 2);

            // Stage 3: Vector Embeddings
            embedDocuments(userRootPath);
            userProgressMap.put(userId, 3);

            // Stage 4: Ready
            userProgressMap.put(userId, 4);

        } catch (Exception e) {
            userProgressMap.put(userId, -1);
        }
    }

    // Your existing populateFiles method (remove the @Async from it, 
    // since the runInitializationPipeline is now handling the async thread)
    private void populateFiles(OAuth2User principal, String userRootPath) throws Exception {
        // ... (Keep your exact driveService code here) ...
    }

    // --- THE VECTOR DB PLACEHOLDER ---
    private void embedDocuments(String userRootPath) {
        System.out.println("Starting vectorization for documents in: " + userRootPath + "/Documents");
        
        // 1. Read PDFs/Text files from the Documents folder
        // 2. Chunk the text
        // 3. Send chunks to LLM to get vector embeddings
        // 4. Save to Vector Database (Chroma, Pinecone, or pgvector)
        
        // Simulating heavy processing time for now:
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
    }


    public boolean doesFolderExist(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        return new File("user-data", userId).exists();
    }


}
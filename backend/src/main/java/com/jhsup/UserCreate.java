package com.jhsup;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import java.io.File;
import java.nio.file.Paths;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.util.List;

@Service
public class UserCreate {

    public String getOrCreateUserFolder(OAuth2User principal) {

        String userId = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        String folder_path = userId;

        File folder = new File("user-data",folder_path);

        if(!folder.exists()){
            boolean created = folder.mkdirs();

            if(created){    
                System.out.println("Created permanent folder for: " + email);
            
                new File(folder, "Documents").mkdir();
                new File(folder, "Images").mkdir();
                new File(folder, "Others").mkdir();
            }
        }

        return folder.getAbsolutePath();

    }

    public void populateFiles(OAuth2User principal, String userRootPath) throws Exception {
        // 1. Get the Access Token for this specific user
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient("google", principal.getName());
        String accessToken = client.getAccessToken().getTokenValue();

        // 2. Initialize the Google Drive Service
        Drive driveService = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                request -> request.getHeaders().setAuthorization("Bearer " + accessToken))
                .setApplicationName("Jarvis-Drive-Fetcher")
                .build();
        

        String pageToken = null;

        do {
            FileList result = driveService.files().list()
                    .setPageSize(100) // Get 100 at a time
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .setPageToken(pageToken) // Use the "bookmark" from the previous loop
                    .execute();

            List<File> driveFiles = result.getFiles();
            if (driveFiles == null || driveFiles.isEmpty()) {
                System.out.println("No files found.");
                return;
            }

            for (File file : driveFiles) {
                String mimeType = file.getMimeType();
                String folderName = "Others";

                if (mimeType.contains("image")) {
                    folderName = "Images";
                } else if (mimeType.contains("pdf") || mimeType.contains("word") || mimeType.contains("text")) {
                    folderName = "Documents";
                }

                System.out.println("Moving " + file.getName() + " [" + mimeType + "] to " + folderName);
                
                // Logic to actually download the file bytes would go here:
                driveService.files().get(file.getId()).executeMediaAndDownloadTo(outputStream);
            }

            // Get the "bookmark" for the next loop
            pageToken = result.getNextPageToken();

        } while (pageToken != null); // Keep going until there are no more pages
        
    }


    public boolean doesFolderExist(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        return new File("user-data", userId).exists();
    }
}

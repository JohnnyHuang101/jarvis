import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;

import java.io.*;
import java.util.Collections;

public class VDB_filler{

    public static Drive getDriveService() throws Exception {
        GoogleCredential credential = GoogleCredential
            .fromStream(new FileInputStream("./secrets/winter-flare-478606-d5-ee892e17c548.json"))
            .createScoped(Collections.singleton("https://www.googleapis.com/auth/drive.readonly"));

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Study Assistant")
                .build();
    }

    public static void StudyAssistant() throws Exception {
        Drive service = getDriveService();

        String query =
            "mimeType='application/pdf' or " +
            "mimeType='application/vnd.openxmlformats-officedocument.wordprocessingml.document' or " +
            "mimeType='application/vnd.openxmlformats-officedocument.presentationml.presentation' or " +
            "mimeType='application/vnd.google-apps.document' or " +
            "mimeType='application/vnd.google-apps.presentation'";

        FileList result = service.files().list()
                .setQ(query)
                .setFields("nextPageToken, files(id, name, mimeType)")
                .setPageSize(1000)
                .execute();

        if (result.getFiles().isEmpty()) {
            System.out.println("No files found. Check sharing and permissions.");
        } else {
            for (File file : result.getFiles()) {
                System.out.println(file.getName() + " | " + file.getMimeType() + " | " + file.getId());
                downloadFile(service, file);
            }
        }
    }

    public static java.io.File downloadFile(Drive service, File file) throws Exception {
        String fileId = file.getId();
        String mime = file.getMimeType();

        // Normalize subfolder based on MIME type
        String folderName = getFolderNameFromMime(mime);

        java.io.File folder = new java.io.File("downloads_/" + folderName);
        folder.mkdirs();

        // String safeName = file.getName().replaceAll("[\\\\/:*?\"<>|]", "_");

        // java.io.File output = new java.io.File(folder, safeName);

        String extension = getExtensionFromMime(mime);
        String safeName = file.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + extension;
        java.io.File output = new java.io.File(folder, safeName);


        OutputStream outputStream = new FileOutputStream(output);

        // Handle Google Docs → export to DOCX
        if (mime.equals("application/vnd.google-apps.document")) {
            service.files().export(fileId,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .executeMediaAndDownloadTo(outputStream);
        }
        // Google Slides → export to PPTX
        else if (mime.equals("application/vnd.google-apps.presentation")) {
            service.files().export(fileId,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                .executeMediaAndDownloadTo(outputStream);
        }
        // PDFs, DOCX, PPTX, etc → direct raw download
        else {
            service.files().get(fileId)
                .executeMediaAndDownloadTo(outputStream);
        }

        outputStream.close();
        return output;
    }

    /** Maps MIME type → folder name */
    private static String getFolderNameFromMime(String mime) {
        switch (mime) {
            case "application/pdf":
                return "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return "docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                return "pptx";
            case "application/vnd.google-apps.document":
                return "docx";
            case "application/vnd.google-apps.presentation":
                return "pptx";
            default:
                return "Error";
        }
    }

    private static String getExtensionFromMime(String mime) {
        switch (mime) {
            case "application/pdf":
                return ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return ".docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                return ".pptx";
            case "application/vnd.google-apps.document":
                return ".docx"; // exported
            case "application/vnd.google-apps.presentation":
                return ".pptx"; // exported
            default:
                return "";
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Program...");
        VDB_filler.StudyAssistant();
    }
}

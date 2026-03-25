import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import java.io.File;
import java.nio.file.Paths;


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


    public boolean doesFolderExist(OAuth2User principal) {
        String userId = principal.getAttribute("sub");
        return new File("user-data", userId).exists();
    }
}

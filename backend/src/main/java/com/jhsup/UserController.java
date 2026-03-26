package com.jhsup;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserCreate syncService;

    public UserController(UserCreate syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/load")
    public String loadUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return "Not authenticated";

        // 1. Ensure folder exists
        String path = syncService.getOrCreateUserFolder(principal);

        // 2. Start Drive Fetching (Asynchronous recommended)
        // fetchDriveData(principal, path); 

        return "Folder ready at: " + path;
    }

    @GetMapping("/check")
    public boolean checkUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return false;
        return syncService.doesFolderExist(principal);
    }
}
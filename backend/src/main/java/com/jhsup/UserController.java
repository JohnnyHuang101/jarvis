package com.jhsup;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;


import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.util.Map;


@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserCreate syncService;

    public UserController(UserCreate syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/load")
    public ResponseEntity<Map<String, String>> loadUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        boolean isReturningUser = syncService.doesFolderExist(principal);

        if (isReturningUser) {
            // Skip everything, they are already set up!
            return ResponseEntity.ok(Map.of("status", "READY"));
        } else {
            // New User! Create folder and start the background pipeline
            String path = syncService.getOrCreateUserFolder(principal);
            syncService.runInitializationPipeline(principal, path); 
            
            return ResponseEntity.ok(Map.of("status", "INITIALIZING"));
        }
    }

    @GetMapping("/check")
    public boolean checkUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) return false;
        return syncService.doesFolderExist(principal);
    }

    @GetMapping("/status")
    public ResponseEntity<Integer> checkStatus(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userId = principal.getAttribute("sub");
        int status = syncService.getUserProgress(userId); // Assuming userCreate is injected as syncService
        return ResponseEntity.ok(status);
    }


}
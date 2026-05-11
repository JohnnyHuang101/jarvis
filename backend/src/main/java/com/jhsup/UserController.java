package com.jhsup;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserCreate syncService;
    private final ChatModel chatModel;

    public record ChatRequest(String question) {
    }

    public UserController(UserCreate syncService, ChatModel chatModel) {
        this.syncService = syncService;
        this.chatModel = chatModel;
    }

    @GetMapping("/load")
    public ResponseEntity<Map<String, String>> loadUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String userId = principal.getAttribute("sub");

        // A user is only fully "READY" if the final step (Vectorization) is finished.
        if (syncService.isVectorized(userId)) {
            return ResponseEntity.ok(Map.of("status", "READY"));
        } else {
            // New user OR a user who got interrupted halfway through setup.
            // Start or resume the pipeline!

            syncService.createFolders(principal); // Guarantees Stage 1 is done
            syncService.runInitializationPipeline(principal); // Runs Stage 2 and 3 in background

            return ResponseEntity.ok(Map.of("status", "INITIALIZING"));
        }
    }

    @GetMapping("/check")
    public boolean checkUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null)
            return false;
        String userId = principal.getAttribute("sub");

        // This should also check for full completion now
        return syncService.isVectorized(userId);
    }

    @GetMapping("/status")
    public ResponseEntity<Integer> checkStatus(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userId = principal.getAttribute("sub");
        int currentStage = 0;

        // Cascade backwards to find the highest completed stage
        if (syncService.isVectorized(userId)) {
            currentStage = 3; // Everything is done
        } else if (syncService.areFilesPulled(userId)) {
            currentStage = 2; // Downloaded, currently embedding
        } else if (syncService.isFolderCreated(userId)) {
            currentStage = 1; // Folders made, currently downloading
        }

        return ResponseEntity.ok(currentStage);
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askGPT(@RequestBody ChatRequest request, @AuthenticationPrincipal OAuth2User principal)
            throws Exception {

        String userQuery = request.question();

        String userId = principal.getAttribute("sub");

        String retrievedContext = syncService.retrieveContext(userQuery, userId);

        // 2. Setup the System Message
        String systemText = """
                You are Jarvis, a personal assistant. Use the context below to answer:
                {context}
                """;

        // 3. Combine everything into a Prompt
        // This replaces your manual JSON Map building
        Message systemMessage = new SystemPromptTemplate(systemText)
                .createMessage(Map.of("context", retrievedContext));
        Message userMessage = new UserMessage(userQuery);

        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        return chatModel.stream(prompt)
                .map(response -> {
                    String content = response.getResult().getOutput().getContent();
                    return content != null ? content : "";
                });

    }
}
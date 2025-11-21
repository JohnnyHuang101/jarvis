package com.jhsup;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;

@RestController // Tells Spring this class handles Web API requests
@RequestMapping("/api")
public class ChatController {

    @PostMapping("/ask")
    public Map<String, String> askQuestion(@RequestBody Map<String, String> payload) {
        String userQuestion = payload.get("question");
        Map<String, String> response = new HashMap<>();

        try {
            System.out.println("Received question (Spring Boot): " + userQuestion);

            // Call your existing RAG logic
            // (Spring can auto-wire this too, but static call is fine for now)
            String aiAnswer = ChatComplete.askGPT(userQuestion);

            response.put("answer", aiAnswer);
            response.put("status", "success");

        } catch (Exception e) {
            e.printStackTrace();
            response.put("answer", "Error: " + e.getMessage());
            response.put("status", "error");
        }
        return response;
    }

}
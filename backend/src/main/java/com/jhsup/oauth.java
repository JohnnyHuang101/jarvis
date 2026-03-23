package com.jhsup;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuth {

    @GetMapping("/login")
    public String loginSuccess(@AuthenticationPrincipal OAuth2User principal) {
        return "Welcome, " + principal.getAttribute("name") + "! You are authenticated.";
    }

    @GetMapping("/route")
    public String listFiles(@RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client) {
        // The 'client' object now contains the Access Token needed for Google Drive!
        String accessToken = client.getAccessToken().getTokenValue();
        return "Your Access Token is: " + accessToken + ". We can now use this to hit Drive API.";
    }


    @GetMapping("/token")
    public String getToken(@AuthenticationPrincipal OAuth2User user,
                        OAuth2AuthorizedClientService clientService) {

        OAuth2AuthorizedClient client =
            clientService.loadAuthorizedClient("google", user.getName());

        String accessToken = client.getAccessToken().getTokenValue();

        return accessToken;
    }


}
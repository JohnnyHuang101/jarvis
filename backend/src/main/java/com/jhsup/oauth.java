package com.jhsup;


@RestController
public class Oauth{

    private String accessToken;

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal OAuth2User principal){

        this.accessToken = client.getAccessToken().getTokenValue();
        return "Welcome, " + principal.getAttribute("name") + "! You are authenticated.";
    }

    @GetMapping("/get_files")
    public String get_files()@RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client{

        return "Token is here";
    }
}
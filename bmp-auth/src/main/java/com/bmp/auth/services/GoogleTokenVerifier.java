package com.bmp.auth.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies a Google Sign-In ID token by calling Google's own tokeninfo endpoint — the
 * lightest correct way to validate a token the CLIENT already obtained via Google Sign-In
 * SDK (mobile/web), as opposed to bmp-auth driving a full OAuth2 authorization-code redirect
 * flow itself (spring-boot-starter-oauth2-client is built for that second case, which isn't
 * this one, so it's deliberately not pulled in). Google's tokeninfo endpoint does full
 * signature + expiry validation server-side; we only need to additionally check {@code aud}
 * matches OUR client id, so a token minted for a different app isn't accepted here.
 *
 * <p>{@code bmp.auth.google-client-id} must be set to the real OAuth client id once one
 * exists (Google Cloud Console — a one-time signup step only Darshan/the founders can do,
 * same category as Neon/Atlas account creation). Until then this fails closed with a clear
 * 501, not a silent bypass.
 */
@Service
public class GoogleTokenVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final RestClient restClient = RestClient.create();
    private final String expectedClientId;

    public GoogleTokenVerifier(@Value("${bmp.auth.google-client-id:}") String expectedClientId) {
        this.expectedClientId = expectedClientId;
    }

    public VerifiedGoogleToken verify(String idToken) {
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Google sign-in isn't configured yet — set BMP_GOOGLE_CLIENT_ID once a" +
                    " Google Cloud OAuth client exists (see docs/project-management).");
        }

        TokenInfo info;
        try {
            info = restClient.get()
                    .uri(TOKENINFO_URL + idToken)
                    .retrieve()
                    .body(TokenInfo.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired Google token");
        }

        if (info == null || info.sub == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        if (!expectedClientId.equals(info.aud)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google token was not issued for this app");
        }
        if (!"true".equals(info.email_verified)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google account email is not verified");
        }

        return new VerifiedGoogleToken(info.sub, info.email);
    }

    public record VerifiedGoogleToken(String subject, String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenInfo {
        public String sub;
        public String aud;
        public String email;
        public String email_verified;
    }
}

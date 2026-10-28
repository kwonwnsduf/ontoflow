package ai.ontoflow.platform.auth.controller;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookies {
    private final boolean secure;

    public AuthCookies(@Value("${ontoflow.auth.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    public String refreshName() {
        return secure ? "__Host-ontoflow_refresh" : "ontoflow_refresh";
    }

    public void write(HttpServletResponse response, String refreshToken, long remainingSeconds) {
        cookie(response, refreshToken, Duration.ofSeconds(remainingSeconds));
    }

    public void clear(HttpServletResponse response) {
        cookie(response, "", Duration.ZERO);
    }

    private void cookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie =
                ResponseCookie.from(refreshName(), value)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Strict")
                        .path("/")
                        .maxAge(maxAge)
                        .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

package ai.ontoflow.platform.auth.controller;

import ai.ontoflow.platform.auth.dto.LoginRequest;
import ai.ontoflow.platform.auth.dto.RegisterRequest;
import ai.ontoflow.platform.auth.dto.TokenResponse;
import ai.ontoflow.platform.auth.service.AuthService;
import ai.ontoflow.platform.auth.service.AuthStateStore;
import ai.ontoflow.platform.common.security.AuthPrincipal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthStateStore states;
    private final AuthCookies cookies;
    private final CookieCsrfTokenRepository csrf;

    public AuthController(
            AuthService auth,
            AuthStateStore states,
            AuthCookies cookies,
            CookieCsrfTokenRepository csrf) {
        this.auth = auth;
        this.states = states;
        this.cookies = cookies;
        this.csrf = csrf;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        states.beforeRegister(request.getRemoteAddr());
        TokenResponse result = auth.register(body);
        cookies.write(response, result.refreshToken(), result.refreshExpiresIn());
        csrf.saveToken(null, request, response);
        return result;
    }

    @PostMapping("/login")
    public TokenResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        states.beforeLogin(body.email(), request.getRemoteAddr());
        TokenResponse result = auth.login(body);
        cookies.write(response, result.refreshToken(), result.refreshExpiresIn());
        csrf.saveToken(null, request, response);
        return result;
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        states.beforeRefresh(request.getRemoteAddr());
        try {
            TokenResponse result = auth.refresh(refreshCookie(request));
            cookies.write(response, result.refreshToken(), result.refreshExpiresIn());
            return result;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 401) cookies.clear(response);
            throw exception;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal AuthPrincipal jwt) {
        String raw = optionalRefreshCookie(request);
        UUID cookieFamily = null;
        if (raw != null) {
            try {
                cookieFamily = auth.logout(raw);
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 401) throw exception;
            }
        }
        if (jwt != null) {
            UUID familyId = jwt.familyId();
            if (!familyId.equals(cookieFamily)) auth.logoutFamily(jwt.userId(), familyId);
        }
        cookies.clear(response);
        csrf.saveToken(null, request, response);
    }

    private String refreshCookie(HttpServletRequest request) {
        String token = optionalRefreshCookie(request);
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh cookie required");
        }
        return token;
    }

    private String optionalRefreshCookie(HttpServletRequest request) {
        Cookie[] requestCookies = request.getCookies();
        if (requestCookies == null) return null;
        return Arrays.stream(requestCookies)
                .filter(cookie -> cookies.refreshName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}

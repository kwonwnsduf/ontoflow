package ai.ontoflow.platform.common.security;

import ai.ontoflow.platform.auth.service.AuthStateStore;
import ai.ontoflow.platform.common.error.ApiErrors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokens;
    private final AuthStateStore states;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokens, AuthStateStore states, ObjectMapper mapper) {
        this.tokens = tokens;
        this.states = states;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String header = headers.getFirst();
            if (headers.size() != 1 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new JwtException("인증 헤더 형식이 올바르지 않습니다.");
            }
            AuthPrincipal principal = tokens.parseAccessToken(header.substring(7));
            states.requireActive(principal.familyId(), principal.userId());
            var authentication =
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            writeError(response, 401, "유효하지 않거나 만료된 접근 토큰입니다.");
            return;
        } catch (ResponseStatusException exception) {
            SecurityContextHolder.clearContext();
            writeError(response, exception.getStatusCode().value(), exception.getReason());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getWriter(), ApiErrors.body(status, message));
    }
}

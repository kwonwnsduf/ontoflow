package ai.ontoflow.platform.common.security;

import ai.ontoflow.platform.auth.service.AuthStateStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CookieCsrfTokenRepository csrfTokens(
            @Value("${ontoflow.auth.cookie-secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(secure ? "__Host-ontoflow_csrf" : "ontoflow_csrf");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(
                cookie -> cookie.httpOnly(true).secure(secure).sameSite("Strict"));
        return repository;
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            SecurityErrorHandler errorHandler,
            AuthStateStore states,
            JwtTokenProvider tokens,
            ObjectMapper mapper,
            CookieCsrfTokenRepository csrfTokens,
            @Value("${ontoflow.auth.web-origin:http://localhost:3000}") String origin,
            @Value("${ontoflow.auth.cookie-secure:true}") boolean secure)
            throws Exception {
        java.net.URI webOrigin = java.net.URI.create(origin);
        if (webOrigin.getHost() == null
                || webOrigin.getUserInfo() != null
                || webOrigin.getQuery() != null
                || webOrigin.getFragment() != null
                || (webOrigin.getPath() != null && !webOrigin.getPath().isEmpty())
                || !("https".equals(webOrigin.getScheme())
                        || (!secure && "http".equals(webOrigin.getScheme())))) {
            throw new IllegalArgumentException(
                    "WEB_ORIGIN must be an exact HTTPS origin; HTTP is allowed only with insecure"
                            + " local cookies");
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Trace-Id", "X-XSRF-TOKEN"));
        cors.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        http.csrf(
                csrf ->
                        csrf.csrfTokenRepository(csrfTokens)
                                .requireCsrfProtectionMatcher(this::requiresCsrf));
        http.cors(configuration -> configuration.configurationSource(source));
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(
                authorize -> {
                    authorize
                            .requestMatchers(
                                    "/actuator/health/**",
                                    "/api/v1",
                                    "/docs/**",
                                    "/swagger-ui/**",
                                    "/v3/api-docs/**")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll();
                    authorize
                            .requestMatchers(
                                    HttpMethod.POST,
                                    "/api/v1/auth/register",
                                    "/api/v1/auth/login",
                                    "/api/v1/auth/refresh",
                                    "/api/v1/auth/logout")
                            .permitAll();
                    authorize.anyRequest().authenticated();
                });
        http.addFilterBefore(
                new JwtAuthenticationFilter(tokens, states, mapper),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                        .class);
        http.exceptionHandling(
                exceptions -> {
                    exceptions.authenticationEntryPoint(errorHandler);
                    exceptions.accessDeniedHandler(errorHandler);
                });
        return http.build();
    }

    private boolean requiresCsrf(jakarta.servlet.http.HttpServletRequest request) {
        if (List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod())) {
            return false;
        }
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/auth/");
    }
}

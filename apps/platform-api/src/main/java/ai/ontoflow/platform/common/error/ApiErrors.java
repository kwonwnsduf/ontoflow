package ai.ontoflow.platform.common.error;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    public static Map<String, Object> body(int status, String message) {
        return Map.of(
                "status",
                status,
                "message",
                message,
                "traceId",
                String.valueOf(MDC.get("traceId")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        var response = ResponseEntity.status(e.getStatusCode());
        if (e instanceof ai.ontoflow.platform.auth.service.LoginRateLimitException limited) {
            response.header("Retry-After", Long.toString(limited.getRetryAfter()));
        }
        return response.body(
                body(
                        e.getStatusCode().value(),
                        e.getReason() == null ? "Request rejected" : e.getReason()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(body(400, "Invalid request"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409)
                .body(body(409, "Already exists or conflicts with current data"));
    }
}

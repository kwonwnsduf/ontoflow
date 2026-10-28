package ai.ontoflow.platform.user.service;

import ai.ontoflow.platform.user.domain.User;
import ai.ontoflow.platform.user.dto.UserResponse;
import ai.ontoflow.platform.user.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public User requireActive(UUID userId) {
        User user =
                users.findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "Invalid account"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid account");
        }
        return user;
    }

    public UserResponse me(UUID userId) {
        return response(requireActive(userId));
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static UserResponse response(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}

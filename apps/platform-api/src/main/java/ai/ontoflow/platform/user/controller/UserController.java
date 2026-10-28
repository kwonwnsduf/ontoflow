package ai.ontoflow.platform.user.controller;

import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;
import ai.ontoflow.platform.user.dto.UserResponse;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthPrincipal jwt) {
        return users.me(CurrentUser.id(jwt));
    }
}

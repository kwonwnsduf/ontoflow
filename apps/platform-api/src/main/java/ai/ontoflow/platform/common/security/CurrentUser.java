package ai.ontoflow.platform.common.security;


import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}

    public static UUID id(AuthPrincipal jwt) {
        return jwt.userId();
    }
}

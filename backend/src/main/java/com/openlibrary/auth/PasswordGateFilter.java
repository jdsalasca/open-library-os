package com.openlibrary.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * A self-hosted app ships with a known default password, so until the owner
 * changes it the account may only touch its own profile. Closing that hole is
 * worth fifteen lines.
 */
@Component
public class PasswordGateFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WHILE_PENDING = Set.of(
            "/auth/me", "/auth/password", "/auth/logout", "/auth/csrf");

    private final UserRepository users;

    public PasswordGateFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            String path = request.getRequestURI().replaceFirst("^/api", "");
            if (!ALLOWED_WHILE_PENDING.contains(path)) {
                boolean pending = users.findByEmailIgnoreCase(auth.getName())
                        .map(User::isMustChangePassword)
                        .orElse(false);
                if (pending) {
                    writeProblem(response);
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private void writeProblem(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\","
                + "\"code\":\"password_change_required\","
                + "\"detail\":\"Debes cambiar la contrasena inicial antes de continuar.\"}");
    }
}
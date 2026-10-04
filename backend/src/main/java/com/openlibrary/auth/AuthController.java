package com.openlibrary.auth;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final AuditService audit;
    private final PasswordEncoder passwords;

    /** Without form login there is no filter that saves the context for us. */
    private final SecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository users,
                          AuditService audit,
                          PasswordEncoder passwords) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.audit = audit;
        this.passwords = passwords;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName());
    }

    @PostMapping("/login")
    @Transactional
    public AuthDtos.UserProfile login(@Valid @RequestBody AuthDtos.LoginRequest body,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.email(), body.password()));
        } catch (BadCredentialsException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials",
                    "Correo o contrasena incorrectos.");
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);

        User user = users.findByEmailIgnoreCase(authentication.getName()).orElseThrow();
        user.recordLogin(Instant.now());
        users.save(user);

        audit.record(user.getId(), "auth.login", "user", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));

        return toProfile(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public AuthDtos.UserProfile me(@AuthenticationPrincipal UserDetails principal) {
        return toProfile(users.findByEmailIgnoreCase(principal.getUsername()).orElseThrow());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@AuthenticationPrincipal UserDetails principal,
                               @Valid @RequestBody AuthDtos.ChangePasswordRequest body) {
        User user = users.findByEmailIgnoreCase(principal.getUsername()).orElseThrow();

        if (!passwords.matches(body.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_credentials",
                    "La contrasena actual no es correcta.");
        }

        user.changePassword(passwords.encode(body.newPassword()));
        users.save(user);
        audit.record(user.getId(), "auth.password_changed", "user", user.getId(), Map.of());
    }

    static AuthDtos.UserProfile toProfile(User user) {
        return new AuthDtos.UserProfile(
                user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.isActive(), user.isMustChangePassword(),
                user.getRole().authorities().stream().sorted().toList());
    }
}
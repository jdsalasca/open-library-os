package com.openlibrary.auth;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authorisation for these endpoints is declared in {@link SecurityConfig}. */
@RestController
@RequestMapping("/users")
public class UserController {

    private final ReaderCsvImport readers;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public UserController(UserRepository users, PasswordEncoder passwords, AuditService audit,
            ReaderCsvImport readers) {
        this.readers = readers;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AuthDtos.UserProfile> list() {
        return users.findAll(Sort.by(Sort.Direction.ASC, "fullName")).stream()
                .map(AuthController::toProfile)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AuthDtos.UserProfile create(@Valid @RequestBody AuthDtos.CreateUserRequest body) {
        if (users.existsByEmailIgnoreCase(body.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken",
                    "Ya existe una cuenta con ese correo.",
                    List.of(new ApiException.FieldError("email", "Correo ya registrado")));
        }

        users.save(new User(
                body.email().trim(), passwords.encode(body.password()),
                body.fullName().trim(), body.role(), false));

        User user = users.findByEmailIgnoreCase(body.email()).orElseThrow();
        audit.record(user.getId(), "user.created", "user", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));

        return AuthController.toProfile(user);
    }

    /**
     * The roster a migrating library already has on a spreadsheet.
     *
     * <p>Body is the file as text so the browser needs no multipart parser. Every
     * account arrives as a LECTOR with a password its owner must choose: see
     * {@link ReaderCsvImport} for why the role and the password are not read from
     * the file.
     */
    @PostMapping("/import")
    public ReaderCsvImport.Report importReaders(
            @RequestHeader("Content-Type") String contentType,
            @RequestBody String csv) {
        if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("csv")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "csv_expected",
                    "El fichero tiene que ser un CSV.");
        }
        return readers.importCsv(csv, users.findByEmailIgnoreCase(currentEmail())
                .map(User::getId).orElse(null));
    }

    private String currentEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth == null ? null : auth.getName();
    }

    @PutMapping("/{id}")
    @Transactional
    public AuthDtos.UserProfile update(@PathVariable Long id, @Valid @RequestBody AuthDtos.UpdateUserRequest body) {
        User user = find(id);
        if (body.fullName() != null && !body.fullName().isBlank()) {
            user.rename(body.fullName().trim());
        }
        if (body.active() != null) {
            user.setActive(body.active());
        }
        users.save(user);
        audit.record(user.getId(), "user.updated", "user", user.getId(), Map.of());
        return AuthController.toProfile(user);
    }

    /** Only the owner role can hand out roles; everyone else can disable accounts. */
    @PatchMapping("/{id}/role")
    @Transactional
    public AuthDtos.UserProfile changeRole(@PathVariable Long id, @Valid @RequestBody AuthDtos.ChangeRoleRequest body) {
        User user = find(id);
        user.changeRole(body.role());
        users.save(user);
        audit.record(user.getId(), "user.role_changed", "user", user.getId(),
                Map.of("role", body.role().name()));
        return AuthController.toProfile(user);
    }

    private User find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found",
                        "No existe el usuario " + id + "."));
    }
}
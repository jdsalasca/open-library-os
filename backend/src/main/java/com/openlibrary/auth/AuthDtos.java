package com.openlibrary.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 200, message = "La contrasena debe tener al menos 10 caracteres")
            String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotNull Role role,
            @NotBlank @Size(min = 10, max = 200,
                    message = "La contrasena debe tener al menos 10 caracteres") String password,
            Boolean active) {
    }

    public record UpdateUserRequest(
            @Size(min = 2, max = 120) String fullName,
            Boolean active) {
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    public record UserProfile(
            Long id,
            String email,
            String fullName,
            Role role,
            boolean active,
            boolean mustChangePassword,
            List<String> authorities) {
    }
}
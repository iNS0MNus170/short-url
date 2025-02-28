package org.goit.urlshortener.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "Invalid email format")
        @NotBlank(message = "Email must not be blank") String email,
        @NotBlank(message = "Password must not be blank") String password) {
}

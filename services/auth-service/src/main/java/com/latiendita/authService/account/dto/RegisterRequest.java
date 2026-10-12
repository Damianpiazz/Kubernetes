package com.latiendita.authService.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(min = 3, max = 30) String username,
        @NotBlank @Size(min = 8, max = 100) String password
) {}
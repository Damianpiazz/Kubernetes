package com.latiendita.authService.account.dto;

public record AccountResponse(
        Long id,
        String email,
        String username
) {}
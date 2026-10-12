package com.latiendita.userService.dto;

public record UserResponse(
        Long id,
        String email,
        String username
) {}

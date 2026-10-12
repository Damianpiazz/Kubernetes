package com.latiendita.userService.dto.event;

import java.time.Instant;

public record UserCreatedEvent(Long id, String email, String username, Instant occurredAt) {}

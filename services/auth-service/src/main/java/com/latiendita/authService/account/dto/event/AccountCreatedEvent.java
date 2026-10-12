package com.latiendita.authService.account.dto.event;

import java.time.Instant;

public record AccountCreatedEvent(Long id, String email, String username, Instant occurredAt) {}
package com.latiendita.userService.dto.event;

import java.time.Instant;

public record UserDeletedEvent(Long id, Instant occurredAt) {}

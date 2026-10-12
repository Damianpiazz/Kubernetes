package com.latiendita.userService;

import com.latiendita.userService.dto.event.AccountCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventsConsumer {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "account-events")
    public void onAccountCreated(String payload) {
        try {
            AccountCreatedEvent event = objectMapper.readValue(payload, AccountCreatedEvent.class);

            if (userRepository.existsByEmail(event.email())) {
                log.info("Perfil ya existe para {}; se omite (idempotente)", event.email());
                return;
            }

            userRepository.save(UserEntity.builder()
                    .email(event.email())
                    .username(event.username())
                    .build());

            log.info("Perfil creado para {}", event.email());
        } catch (Exception ex) {
            log.error("Error procesando account-events: {}", ex.getMessage());
            throw ex;
        }
    }
}
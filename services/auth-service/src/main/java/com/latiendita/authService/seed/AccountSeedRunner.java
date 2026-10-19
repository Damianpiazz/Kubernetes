package com.latiendita.authService.seed;

import com.latiendita.authService.account.AccountRepository;
import com.latiendita.authService.account.AccountService;
import com.latiendita.authService.account.dto.RegisterRequest;
import com.latiendita.authService.exception.DuplicateAccountException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class AccountSeedRunner implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${app.seed.location:}")
    private String location;

    @Override
    public void run(ApplicationArguments args) {
        try {
            SeedAccount[] accounts = read();
            int created = 0;
            int skipped = 0;
            int failed = 0;
            for (SeedAccount seed : accounts) {
                if (accountRepository.existsByEmail(seed.email())
                        || accountRepository.existsByUsername(seed.username())) {
                    log.info("[seed] Cuenta {} ya existe; se omite", seed.email());
                    skipped++;
                    continue;
                }
                try {
                    accountService.register(new RegisterRequest(
                            seed.email(), seed.username(), seed.password()));
                    created++;
                } catch (DuplicateAccountException ex) {
                    skipped++;
                } catch (Exception ex) {
                    failed++;
                    log.error("[seed] Error al crear cuenta {}: {}",
                            seed.email(), ex.getMessage());
                }
            }
            log.info("[seed] accounts: {} creadas, {} omitidas, {} con error",
                    created, skipped, failed);
        } catch (IOException ex) {
            log.error("[seed] No se pudo leer el archivo de seeds: {}", ex.getMessage());
        }
    }

    private SeedAccount[] read() throws IOException {
        Resource resource = location.isBlank()
                ? resourceLoader.getResource("classpath:seed/accounts.json")
                : new FileSystemResource(Path.of(location, "accounts.json").toString());
        try (InputStream in = resource.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, SeedAccount[].class);
        }
    }
}

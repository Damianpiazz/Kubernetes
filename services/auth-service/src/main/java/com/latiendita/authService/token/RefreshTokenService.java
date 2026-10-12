package com.latiendita.authService.token;

import com.latiendita.authService.exception.InvalidRefreshTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    public record Issued(String raw, Long id) {}

    private final RefreshTokenRepository repository;

    @Value("${jwt.refresh-ttl-days}")
    private int refreshTtlDays;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public Issued issue(Long accountId) {
        return issue(accountId, null);
    }

    @Transactional
    public Issued issue(Long accountId, RefreshTokenEntity replaced) {
        String raw = generateRaw();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setTokenHash(hash(raw));
        entity.setAccountId(accountId);
        entity.setExpiresAt(Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS));
        entity.setRevoked(false);
        repository.save(entity);

        if (replaced != null) {
            replaced.setReplacedBy(entity.getId());
            repository.save(replaced);
        }
        return new Issued(raw, entity.getId());
    }

    @Transactional
    public RefreshTokenEntity rotate(String rawToken) {
        RefreshTokenEntity entity = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (entity.isRevoked()) {

            repository.findAllByAccountIdAndRevokedFalse(entity.getAccountId())
                    .forEach(t -> t.setRevoked(true));
            throw new InvalidRefreshTokenException();
        }

        if (entity.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        entity.setRevoked(true);
        repository.save(entity);
        return entity;
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(entity -> {
            entity.setRevoked(true);
            repository.save(entity);
        });
    }

    private String generateRaw() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
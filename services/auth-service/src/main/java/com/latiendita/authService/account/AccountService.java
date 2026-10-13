package com.latiendita.authService.account;

import com.latiendita.authService.account.dto.AccountMapper;
import com.latiendita.authService.account.dto.AccountResponse;
import com.latiendita.authService.account.dto.LoginRequest;
import com.latiendita.authService.account.dto.RegisterRequest;
import com.latiendita.authService.account.dto.TokenResponse;
import com.latiendita.authService.account.dto.event.AccountCreatedEvent;
import com.latiendita.authService.exception.DuplicateAccountException;
import com.latiendita.authService.exception.InvalidCredentialsException;
import com.latiendita.authService.exception.InvalidRefreshTokenException;
import com.latiendita.authService.outbox.OutboxWriter;
import com.latiendita.authService.token.JwtService;
import com.latiendita.authService.token.RefreshTokenEntity;
import com.latiendita.authService.token.RefreshTokenService;
import java.time.Instant;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountService {

    public static final String ACCOUNT_EVENTS_TOPIC = "account-events";

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outboxWriter;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final MeterRegistry meterRegistry;

    public AccountResponse register(RegisterRequest request) {
        if (accountRepository.existsByEmail(request.email())) {
            throw new DuplicateAccountException("email", request.email());
        }
        if (accountRepository.existsByUsername(request.username())) {
            throw new DuplicateAccountException("username", request.username());
        }

        AccountEntity saved = accountRepository.save(AccountEntity.builder()
                .email(request.email())
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .build());

        outboxWriter.append(ACCOUNT_EVENTS_TOPIC, saved.getId(), "ACCOUNT_CREATED",
                new AccountCreatedEvent(saved.getId(), saved.getEmail(),
                        saved.getUsername(), Instant.now()));

        meterRegistry.counter("latiendita.auth.registrations").increment();

        return AccountMapper.toResponse(saved);
    }

    public TokenResponse login(LoginRequest request) {
        AccountEntity account = accountRepository.findByEmail(request.email())
                .orElse(null);

        if (account == null || !passwordEncoder.matches(request.password(), account.getPassword())) {
            meterRegistry.counter("latiendita.auth.logins.failure").increment();
            throw new InvalidCredentialsException();
        }

        meterRegistry.counter("latiendita.auth.logins.success").increment();
        return issueTokens(account, null);
    }

    public TokenResponse refresh(String rawRefreshToken) {
        RefreshTokenEntity old = refreshTokenService.rotate(rawRefreshToken);
        AccountEntity account = accountRepository.findById(old.getAccountId())
                .orElseThrow(InvalidRefreshTokenException::new);
        return issueTokens(account, old);
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private TokenResponse issueTokens(AccountEntity account, RefreshTokenEntity replaced) {
        String accessToken = jwtService.generateAccessToken(account);
        RefreshTokenService.Issued refresh = refreshTokenService.issue(account.getId(), replaced);
        return new TokenResponse(accessToken, refresh.raw(), "Bearer",
                jwtService.getAccessTtlSeconds());
    }
}
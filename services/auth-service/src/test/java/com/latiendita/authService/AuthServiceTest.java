package com.latiendita.authService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.authService.account.AccountEntity;
import com.latiendita.authService.account.AccountRepository;
import com.latiendita.authService.account.AccountService;
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
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private AccountService accountService;

    @Test
    void shouldRegisterHashingPasswordAndAppendingEvent() {
        RegisterRequest request = new RegisterRequest("ana@mail.com", "ana", "secreta123");

        when(accountRepository.existsByEmail("ana@mail.com")).thenReturn(false);
        when(accountRepository.existsByUsername("ana")).thenReturn(false);
        when(passwordEncoder.encode("secreta123")).thenReturn("$2a$10$hash");
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(invocation -> {
            AccountEntity account = invocation.getArgument(0);
            account.setId(1L);
            return account;
        });

        var response = accountService.register(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("ana@mail.com");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(AccountService.ACCOUNT_EVENTS_TOPIC), eq(1L),
                eq("ACCOUNT_CREATED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(AccountCreatedEvent.class);
    }

    @Test
    void shouldRejectDuplicateEmailOnRegister() {
        when(accountRepository.existsByEmail("ana@mail.com")).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(
                new RegisterRequest("ana@mail.com", "ana", "secreta123")))
                .isInstanceOf(DuplicateAccountException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldLoginReturningTokens() {
        AccountEntity account = AccountEntity.builder()
                .id(1L).email("ana@mail.com").username("ana").password("$2a$10$hash").build();

        when(accountRepository.findByEmail("ana@mail.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("secreta123", "$2a$10$hash")).thenReturn(true);
        when(jwtService.generateAccessToken(account)).thenReturn("jwt-token");
        when(jwtService.getAccessTtlSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(1L, null))
                .thenReturn(new RefreshTokenService.Issued("refresh-token", 9L));

        TokenResponse response = accountService.login(
                new LoginRequest("ana@mail.com", "secreta123"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.expiresIn()).isEqualTo(900L);
    }

    @Test
    void shouldRejectWrongPasswordWithInvalidCredentials() {
        AccountEntity account = AccountEntity.builder()
                .id(1L).email("ana@mail.com").username("ana").password("$2a$10$hash").build();

        when(accountRepository.findByEmail("ana@mail.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("mala", "$2a$10$hash")).thenReturn(false);

        assertThatThrownBy(() -> accountService.login(new LoginRequest("ana@mail.com", "mala")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void shouldRejectUnknownAccountWithInvalidCredentials() {
        when(accountRepository.findByEmail("na@mail.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.login(new LoginRequest("na@mail.com", "x12345678")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void shouldThrowOnInvalidRefreshToken() {
        when(refreshTokenService.rotate("bad-token"))
                .thenThrow(new InvalidRefreshTokenException());

        assertThatThrownBy(() -> accountService.refresh("bad-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void shouldPassReplacedTokenWhenIssuingOnRefresh() {
        RefreshTokenEntity replaced = new RefreshTokenEntity();
        replaced.setId(5L);
        replaced.setAccountId(1L);

        AccountEntity account = AccountEntity.builder()
                .id(1L).email("ana@mail.com").username("ana").password("$2a$10$hash").build();

        when(refreshTokenService.rotate("old-token")).thenReturn(replaced);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(jwtService.generateAccessToken(account)).thenReturn("jwt-token");
        when(jwtService.getAccessTtlSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(1L, replaced))
                .thenReturn(new RefreshTokenService.Issued("new-token", 6L));

        TokenResponse response = accountService.refresh("old-token");

        assertThat(response.refreshToken()).isEqualTo("new-token");

        verify(refreshTokenService).issue(1L, replaced);
    }
}
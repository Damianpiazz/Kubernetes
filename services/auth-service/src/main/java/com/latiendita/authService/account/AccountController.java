package com.latiendita.authService.account;

import com.latiendita.authService.account.dto.AccountResponse;
import com.latiendita.authService.account.dto.LoginRequest;
import com.latiendita.authService.account.dto.RegisterRequest;
import com.latiendita.authService.account.dto.TokenResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/register")
    public ResponseEntity<AccountResponse> register(@Valid @RequestBody RegisterRequest request) {
        AccountResponse created = accountService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/users/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return accountService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody Map<String, String> body) {
        return accountService.refresh(body.get("refreshToken"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody Map<String, String> body) {
        accountService.logout(body.get("refreshToken"));
        return ResponseEntity.noContent().build();
    }
}
package com.latiendita.authService.token;

import com.latiendita.authService.account.AccountEntity;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final long accessTtlSeconds;
    private final String issuer;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-ttl-seconds}") long accessTtlSeconds,
                      @Value("${jwt.issuer}") String issuer) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("jwt.secret debe tener al menos 32 caracteres (HS256)");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(key);
        this.jwtEncoder = new NimbusJwtEncoder(jwkSource);
        this.jwtDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.accessTtlSeconds = accessTtlSeconds;
        this.issuer = issuer;
    }

    public String generateAccessToken(AccountEntity account) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(account.getId()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTtlSeconds))
                .claim("email", account.getEmail())
                .claim("username", account.getUsername())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    public JwtDecoder getDecoder() {
        return jwtDecoder;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }
}
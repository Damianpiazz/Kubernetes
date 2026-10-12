package com.latiendita.authService.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    Optional<AccountEntity> findByEmail(String email);
}
package com.latiendita.authService.account.dto;

import com.latiendita.authService.account.AccountEntity;

public final class AccountMapper {

    private AccountMapper() {}

    public static AccountResponse toResponse(AccountEntity entity) {
        return new AccountResponse(entity.getId(), entity.getEmail(), entity.getUsername());
    }
}
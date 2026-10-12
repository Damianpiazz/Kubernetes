package com.latiendita.userService.dto;

import com.latiendita.userService.UserEntity;

public final class UserMapper {

    private UserMapper() {}

    public static UserResponse toResponse(UserEntity entity) {
        return new UserResponse(entity.getId(), entity.getEmail(), entity.getUsername());
    }
}

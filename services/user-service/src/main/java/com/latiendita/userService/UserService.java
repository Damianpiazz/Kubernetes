package com.latiendita.userService;

import com.latiendita.userService.dto.UserMapper;
import com.latiendita.userService.dto.UserRequest;
import com.latiendita.userService.dto.UserResponse;
import com.latiendita.userService.dto.UserUpdateRequest;
import com.latiendita.userService.dto.event.UserCreatedEvent;
import com.latiendita.userService.dto.event.UserDeletedEvent;
import com.latiendita.userService.dto.event.UserUpdatedEvent;
import com.latiendita.userService.exception.DuplicateUserException;
import com.latiendita.userService.exception.UserNotFoundException;
import com.latiendita.userService.outbox.OutboxWriter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    public static final String USER_EVENTS_TOPIC = "user-events";

    private final UserRepository userRepository;
    private final OutboxWriter outboxWriter;

    public UserResponse create(UserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("email", request.email());
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException("username", request.username());
        }

        UserEntity saved = userRepository.save(UserEntity.builder()
                .email(request.email())
                .username(request.username())
                .build());

        outboxWriter.append(USER_EVENTS_TOPIC, saved.getId(), "USER_CREATED",
                new UserCreatedEvent(saved.getId(), saved.getEmail(),
                        saved.getUsername(), Instant.now()));

        return UserMapper.toResponse(saved);
    }

    public UserResponse update(Long id, UserUpdateRequest request) {
        UserEntity user = findEntity(id);

        boolean emailChanged = !user.getEmail().equals(request.email());
        if (emailChanged && userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("email", request.email());
        }

        boolean usernameChanged = !user.getUsername().equals(request.username());
        if (usernameChanged && userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException("username", request.username());
        }

        user.setEmail(request.email());
        user.setUsername(request.username());

        UserEntity saved = userRepository.save(user);

        outboxWriter.append(USER_EVENTS_TOPIC, saved.getId(), "USER_UPDATED",
                new UserUpdatedEvent(saved.getId(), saved.getEmail(),
                        saved.getUsername(), Instant.now()));

        return UserMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return UserMapper.toResponse(findEntity(id));
    }

    /**
     * Perfil de la persona autenticada.
     *
     * Se busca por el `X-User-Email` que propaga el gateway: el id de esta base
     * no es el de la cuenta (la entidad se crea por el evento `account-events`),
     * así que usar `X-User-Id` apuntaría a otro registro.
     */
    @Transactional(readOnly = true)
    public UserResponse findMe(String email) {
        return userRepository.findByEmail(email)
                .map(UserMapper::toResponse)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    public void delete(Long id) {
        UserEntity user = findEntity(id);
        userRepository.delete(user);

        outboxWriter.append(USER_EVENTS_TOPIC, id, "USER_DELETED",
                new UserDeletedEvent(id, Instant.now()));
    }

    private UserEntity findEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }
}
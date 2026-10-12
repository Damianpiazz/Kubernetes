package com.latiendita.userService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.userService.dto.UserRequest;
import com.latiendita.userService.dto.UserResponse;
import com.latiendita.userService.dto.UserUpdateRequest;
import com.latiendita.userService.dto.event.UserCreatedEvent;
import com.latiendita.userService.exception.DuplicateUserException;
import com.latiendita.userService.exception.UserNotFoundException;
import com.latiendita.userService.outbox.OutboxWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private UserService userService;

    @Test
    void shouldCreateUserAppendingEvent() {
        UserRequest request = new UserRequest("ana@mail.com", "ana");

        when(userRepository.existsByEmail("ana@mail.com")).thenReturn(false);
        when(userRepository.existsByUsername("ana")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        UserResponse response = userService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("ana@mail.com");
        assertThat(response.username()).isEqualTo("ana");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(UserService.USER_EVENTS_TOPIC), eq(1L),
                eq("USER_CREATED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(UserCreatedEvent.class);
    }

    @Test
    void shouldRejectDuplicateEmailOnCreate() {
        when(userRepository.existsByEmail("ana@mail.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(new UserRequest("ana@mail.com", "ana")))
                .isInstanceOf(DuplicateUserException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldRejectDuplicateUsernameOnCreate() {
        when(userRepository.existsByEmail("ana@mail.com")).thenReturn(false);
        when(userRepository.existsByUsername("ana")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(new UserRequest("ana@mail.com", "ana")))
                .isInstanceOf(DuplicateUserException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldThrowUserNotFoundOnFindById() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldUpdateUser() {
        UserEntity existing = UserEntity.builder()
                .id(1L)
                .email("ana@mail.com")
                .username("ana")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmail("nuevo@mail.com")).thenReturn(false);
        when(userRepository.existsByUsername("nuevo")).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));

        UserResponse response = userService.update(1L,
                new UserUpdateRequest("nuevo@mail.com", "nuevo"));

        assertThat(response.email()).isEqualTo("nuevo@mail.com");
        assertThat(response.username()).isEqualTo("nuevo");
    }
}
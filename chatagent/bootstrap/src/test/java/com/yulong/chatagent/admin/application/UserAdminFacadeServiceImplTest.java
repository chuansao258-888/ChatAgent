package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.user.application.AuthenticatedUserSnapshotCache;
import com.yulong.chatagent.user.application.PasswordService;
import com.yulong.chatagent.user.model.UserStatus;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.RefreshTokenStore;
import com.yulong.chatagent.user.port.UserRepository;
import com.yulong.chatagent.admin.model.request.CreateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserStatusRequest;
import com.yulong.chatagent.admin.model.response.CreateAdminUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordService passwordService;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache;

    private UserAdminFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new UserAdminFacadeServiceImpl(
                adminAccessService,
                userRepository,
                passwordService,
                refreshTokenStore,
                authenticatedUserSnapshotCache
        );
    }

    @Test
    void shouldCreateUserWithActiveStatusAndGeneratedPassword() {
        LoginUser admin = admin("admin-1");
        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(userRepository.findByUsername("new.user")).thenReturn(null);
        when(passwordService.hash(any())).thenReturn("hashed-password");
        doAnswer(invocation -> {
            UserDTO dto = invocation.getArgument(0);
            dto.setId("user-2");
            return true;
        }).when(userRepository).save(any(UserDTO.class));

        CreateAdminUserRequest request = new CreateAdminUserRequest();
        request.setUsername("new.user");
        request.setRole("admin");
        request.setAvatar("https://example.com/a.png");

        CreateAdminUserResponse response = facadeService.createUser(request);

        ArgumentCaptor<UserDTO> userCaptor = ArgumentCaptor.forClass(UserDTO.class);
        verify(userRepository).save(userCaptor.capture());
        UserDTO savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("new.user");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.ADMIN.persistedValue());
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE.name());
        assertThat(savedUser.getDeleted()).isFalse();
        assertThat(savedUser.getAvatar()).isEqualTo("https://example.com/a.png");
        assertThat(response.getUserId()).isEqualTo("user-2");
        assertThat(response.getInitialPassword()).isNotBlank();
    }

    @Test
    void shouldRejectDemotingSelfFromAdminRole() {
        LoginUser admin = admin("admin-1");
        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(userRepository.findById("admin-1")).thenReturn(activeAdmin("admin-1"));

        UpdateAdminUserRequest request = new UpdateAdminUserRequest();
        request.setRole("user");

        assertThatThrownBy(() -> facadeService.updateUser("admin-1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("own administrator role");

        verify(userRepository, never()).update(any(UserDTO.class));
    }

    @Test
    void shouldRejectDisablingLastActiveAdmin() {
        LoginUser admin = admin("admin-2");
        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(userRepository.findById("admin-1")).thenReturn(activeAdmin("admin-1"));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(activeAdmin("admin-1")));

        UpdateAdminUserStatusRequest request = new UpdateAdminUserStatusRequest();
        request.setStatus("DISABLED");

        assertThatThrownBy(() -> facadeService.updateUserStatus("admin-1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("last active administrator");

        verify(userRepository, never()).update(any(UserDTO.class));
    }

    @Test
    void shouldSoftDeleteUserAndRevokeSessions() {
        LoginUser admin = admin("admin-1");
        UserDTO member = activeUser("user-3");
        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(userRepository.findById("user-3")).thenReturn(member);
        when(userRepository.update(any(UserDTO.class))).thenReturn(true);

        facadeService.deleteUser("user-3");

        ArgumentCaptor<UserDTO> userCaptor = ArgumentCaptor.forClass(UserDTO.class);
        verify(userRepository).update(userCaptor.capture());
        UserDTO updatedUser = userCaptor.getValue();
        assertThat(updatedUser.getDeleted()).isTrue();
        assertThat(updatedUser.getStatus()).isEqualTo(UserStatus.DISABLED.name());
        verify(authenticatedUserSnapshotCache).invalidate("user-3");
        verify(refreshTokenStore).deleteByUserId("user-3");
    }

    @Test
    void shouldRejectDeletingOwnAdministratorAccount() {
        LoginUser admin = admin("admin-1");
        when(adminAccessService.requireAdmin()).thenReturn(admin);
        when(userRepository.findById("admin-1")).thenReturn(activeAdmin("admin-1"));

        assertThatThrownBy(() -> facadeService.deleteUser("admin-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("own administrator account");

        verify(userRepository, never()).update(any(UserDTO.class));
    }

    private static LoginUser admin(String userId) {
        return LoginUser.builder()
                .userId(userId)
                .username("Admin")
                .role("admin")
                .build();
    }

    private static UserDTO activeAdmin(String userId) {
        return UserDTO.builder()
                .id(userId)
                .username("admin")
                .role("admin")
                .status(UserStatus.ACTIVE.name())
                .deleted(Boolean.FALSE)
                .build();
    }

    private static UserDTO activeUser(String userId) {
        return UserDTO.builder()
                .id(userId)
                .username("member")
                .role("user")
                .status(UserStatus.ACTIVE.name())
                .deleted(Boolean.FALSE)
                .build();
    }
}

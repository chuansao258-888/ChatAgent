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
import com.yulong.chatagent.admin.model.response.GetAdminUsersResponse;
import com.yulong.chatagent.admin.model.response.ResetAdminUserPasswordResponse;
import com.yulong.chatagent.admin.model.vo.AdminUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Administrator-facing user management flows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminFacadeServiceImpl implements UserAdminFacadeService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";

    private final AdminAccessService adminAccessService;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public GetAdminUsersResponse getUsers(int page, int size, String keyword, String status) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        int normalizedPage = Math.max(DEFAULT_PAGE, page);
        int normalizedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? DEFAULT_SIZE : size));
        int offset = (normalizedPage - 1) * normalizedSize;
        String normalizedKeyword = trimToNull(keyword);
        String normalizedStatus = normalizeStatus(status);

        List<AdminUserVO> users = new ArrayList<>();
        for (UserDTO user : userRepository.findPage(normalizedKeyword, normalizedStatus, normalizedSize, offset)) {
            users.add(toVO(user));
        }

        long total = userRepository.count(normalizedKeyword, normalizedStatus);
        log.info("Admin listed users: adminUserId={}, keyword={}, status={}, page={}, size={}, total={}",
                adminUser.getUserId(),
                normalizedKeyword,
                normalizedStatus,
                normalizedPage,
                normalizedSize,
                total);
        return GetAdminUsersResponse.builder()
                .users(users.toArray(new AdminUserVO[0]))
                .page(normalizedPage)
                .size(normalizedSize)
                .total(total)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateAdminUserResponse createUser(CreateAdminUserRequest request) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        if (request == null || !StringUtils.hasText(request.getUsername())) {
            throw new BizException("Username is required");
        }

        String username = request.getUsername().trim();
        if (userRepository.findByUsername(username) != null) {
            log.warn("Admin action rejected: adminUserId={}, action=createUser, targetUsername={}, reason=username already exists",
                    adminUser.getUserId(),
                    username);
            throw new BizException("Username already exists");
        }

        String persistedRole = normalizeRole(request.getRole());
        String generatedPassword = generatePassword();
        LocalDateTime now = LocalDateTime.now();
        UserDTO user = UserDTO.builder()
                .username(username)
                .passwordHash(passwordService.hash(generatedPassword))
                .role(persistedRole)
                .avatar(trimToNull(request.getAvatar()))
                .status(UserStatus.ACTIVE.name())
                .deleted(Boolean.FALSE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            if (!userRepository.save(user)) {
                throw new BizException("Failed to create user");
            }
        } catch (RuntimeException ex) {
            log.warn("Admin create user failed: adminUserId={}, username={}, error={}",
                    adminUser.getUserId(), username, ex.getMessage());
            throw new BizException("Username already exists");
        }

        log.info("Admin created user: adminUserId={}, targetUserId={}, username={}, role={}",
                adminUser.getUserId(),
                user.getId(),
                user.getUsername(),
                user.getRole());
        return CreateAdminUserResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .initialPassword(generatedPassword)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(String userId, UpdateAdminUserRequest request) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        UserDTO targetUser = requireManageableUser(adminUser, userId, "updateUser");
        if (request == null) {
            log.warn("Admin action rejected: adminUserId={}, action=updateUser, targetUserId={}, reason=missing payload",
                    adminUser.getUserId(),
                    userId);
            throw new BizException("Update payload is required");
        }

        String newRole = request.getRole() == null ? targetUser.getRole() : normalizeRole(request.getRole());
        String newAvatar = request.getAvatar() == null ? targetUser.getAvatar() : trimToNull(request.getAvatar());
        boolean roleChanged = !safeEquals(targetUser.getRole(), newRole);
        boolean avatarChanged = !safeEquals(targetUser.getAvatar(), newAvatar);
        boolean removingActiveAdmin = isActiveAdmin(targetUser) && !UserRole.ADMIN.matches(newRole);

        if (!roleChanged && !avatarChanged) {
            return;
        }

        if (removingActiveAdmin) {
            assertCanModifyOtherAdmin(adminUser, targetUser, "Cannot change your own administrator role");
            assertNotRemovingLastActiveAdmin(adminUser, targetUser.getId(), "updateUser");
        }

        targetUser.setRole(newRole);
        targetUser.setAvatar(newAvatar);
        targetUser.setUpdatedAt(LocalDateTime.now());
        if (!userRepository.update(targetUser)) {
            throw new BizException("Failed to update user");
        }
        invalidateUserSnapshot(targetUser.getId());

        if (roleChanged) {
            revokeUserSessionsAfterCommit(targetUser.getId(), "role changed");
        }
        log.info("Admin updated user: adminUserId={}, targetUserId={}, roleChanged={}, avatarChanged={}",
                adminUser.getUserId(), targetUser.getId(), roleChanged, avatarChanged);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(String userId, UpdateAdminUserStatusRequest request) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        UserDTO targetUser = requireManageableUser(adminUser, userId, "updateUserStatus");
        if (request == null || !StringUtils.hasText(request.getStatus())) {
            log.warn("Admin action rejected: adminUserId={}, action=updateUserStatus, targetUserId={}, reason=missing status",
                    adminUser.getUserId(),
                    userId);
            throw new BizException("Status is required");
        }

        String newStatus = UserStatus.normalize(request.getStatus());
        if (safeEquals(targetUser.getStatus(), newStatus)) {
            return;
        }

        if (UserStatus.DISABLED.matches(newStatus) && isActiveAdmin(targetUser)) {
            assertCanModifyOtherAdmin(adminUser, targetUser, "Cannot disable your own administrator account");
            assertNotRemovingLastActiveAdmin(adminUser, targetUser.getId(), "updateUserStatus");
        }

        targetUser.setStatus(newStatus);
        targetUser.setUpdatedAt(LocalDateTime.now());
        if (!userRepository.update(targetUser)) {
            throw new BizException("Failed to update user status");
        }
        invalidateUserSnapshot(targetUser.getId());

        revokeUserSessionsAfterCommit(targetUser.getId(), "status changed");
        log.info("Admin updated user status: adminUserId={}, targetUserId={}, status={}",
                adminUser.getUserId(), targetUser.getId(), newStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResetAdminUserPasswordResponse resetPassword(String userId) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        UserDTO targetUser = requireManageableUser(adminUser, userId, "resetPassword");
        String generatedPassword = generatePassword();

        targetUser.setPasswordHash(passwordService.hash(generatedPassword));
        targetUser.setUpdatedAt(LocalDateTime.now());
        if (!userRepository.update(targetUser)) {
            throw new BizException("Failed to reset password");
        }
        invalidateUserSnapshot(targetUser.getId());

        revokeUserSessionsAfterCommit(targetUser.getId(), "password reset");
        log.info("Admin reset user password: adminUserId={}, targetUserId={}",
                adminUser.getUserId(), targetUser.getId());
        return ResetAdminUserPasswordResponse.builder()
                .userId(targetUser.getId())
                .newPassword(generatedPassword)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(String userId) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        UserDTO targetUser = requireManageableUser(adminUser, userId, "deleteUser");

        if (isActiveAdmin(targetUser)) {
            assertCanModifyOtherAdmin(adminUser, targetUser, "Cannot delete your own administrator account");
            assertNotRemovingLastActiveAdmin(adminUser, targetUser.getId(), "deleteUser");
        } else if (adminUser.getUserId() != null && adminUser.getUserId().equals(targetUser.getId())) {
            log.warn("Admin action rejected: adminUserId={}, action=deleteUser, targetUserId={}, reason=cannot delete own account",
                    adminUser.getUserId(),
                    targetUser.getId());
            throw new BizException("Cannot delete your own account");
        }

        targetUser.setDeleted(Boolean.TRUE);
        targetUser.setStatus(UserStatus.DISABLED.name());
        targetUser.setUpdatedAt(LocalDateTime.now());
        if (!userRepository.update(targetUser)) {
            throw new BizException("Failed to delete user");
        }
        invalidateUserSnapshot(targetUser.getId());

        revokeUserSessionsAfterCommit(targetUser.getId(), "user deleted");
        log.info("Admin deleted user: adminUserId={}, targetUserId={}, username={}",
                adminUser.getUserId(),
                targetUser.getId(),
                targetUser.getUsername());
    }

    private UserDTO requireManageableUser(LoginUser adminUser, String userId, String action) {
        UserDTO user = userRepository.findById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            log.warn("Admin action rejected: adminUserId={}, action={}, targetUserId={}, reason=user not found",
                    adminUser.getUserId(),
                    action,
                    userId);
            throw new BizException("User not found");
        }
        return user;
    }

    private void assertCanModifyOtherAdmin(LoginUser adminUser, UserDTO targetUser, String message) {
        if (adminUser.getUserId() != null && adminUser.getUserId().equals(targetUser.getId())) {
            log.warn("Admin action rejected: adminUserId={}, action=modifyAdminGuard, targetUserId={}, reason={}",
                    adminUser.getUserId(),
                    targetUser.getId(),
                    message);
            throw new BizException(message);
        }
    }

    private void assertNotRemovingLastActiveAdmin(LoginUser adminUser, String targetUserId, String action) {
        List<UserDTO> activeAdmins = userRepository.findActiveAdminsForUpdate();
        if (activeAdmins.size() == 1 && targetUserId.equals(activeAdmins.get(0).getId())) {
            log.warn("Admin action rejected: adminUserId={}, action={}, targetUserId={}, reason=last active administrator guard",
                    adminUser.getUserId(),
                    action,
                    targetUserId);
            throw new BizException("Cannot remove the last active administrator");
        }
    }

    private void invalidateUserSnapshot(String userId) {
        authenticatedUserSnapshotCache.invalidate(userId);
    }

    private void revokeUserSessionsAfterCommit(String userId, String reason) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revokeUserSessionsQuietly(userId, reason);
                }
            });
            return;
        }
        revokeUserSessionsQuietly(userId, reason);
    }

    private void revokeUserSessionsQuietly(String userId, String reason) {
        try {
            refreshTokenStore.deleteByUserId(userId);
        } catch (RuntimeException ex) {
            log.warn("Failed to revoke user sessions after {}: userId={}, error={}",
                    reason, userId, ex.getMessage());
        }
    }

    private AdminUserVO toVO(UserDTO user) {
        return AdminUserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private String normalizeRole(String rawRole) {
        if (!StringUtils.hasText(rawRole)) {
            return UserRole.USER.persistedValue();
        }
        try {
            return UserRole.normalizePersistedValue(rawRole);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ex.getMessage());
        }
    }

    private String normalizeStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return null;
        }
        try {
            return UserStatus.normalize(rawStatus);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ex.getMessage());
        }
    }

    private boolean isActiveAdmin(UserDTO user) {
        return user != null
                && !Boolean.TRUE.equals(user.getDeleted())
                && UserRole.ADMIN.matches(user.getRole())
                && !UserStatus.DISABLED.matches(user.getStatus());
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String generatePassword() {
        StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < 16; i += 1) {
            int index = secureRandom.nextInt(PASSWORD_ALPHABET.length());
            builder.append(PASSWORD_ALPHABET.charAt(index));
        }
        return builder.toString();
    }
}

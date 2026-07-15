package com.bmp.user.internal.repository;

import com.bmp.user.internal.entity.UserRoles;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRolesRepository extends JpaRepository<UserRoles, UUID> {
    List<UserRoles> findByUserId(UUID userId);
}

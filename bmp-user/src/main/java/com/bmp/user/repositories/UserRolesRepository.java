package com.bmp.user.repositories;

import com.bmp.user.entities.UserRoles;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRolesRepository extends JpaRepository<UserRoles, UUID> {
    List<UserRoles> findByUserId(UUID userId);
}

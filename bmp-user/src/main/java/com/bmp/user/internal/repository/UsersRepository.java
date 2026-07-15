package com.bmp.user.internal.repository;

import com.bmp.user.internal.entity.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsersRepository extends JpaRepository<Users, UUID> {
    Optional<Users> findByPhone(String phone);
    boolean existsByPhone(String phone);
}

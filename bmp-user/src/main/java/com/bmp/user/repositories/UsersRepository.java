package com.bmp.user.repositories;

import com.bmp.user.entities.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsersRepository extends JpaRepository<Users, UUID> {
    Optional<Users> findByPhone(String phone);
    boolean existsByPhone(String phone);
}

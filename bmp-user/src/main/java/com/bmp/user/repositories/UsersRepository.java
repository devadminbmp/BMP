package com.bmp.user.repositories;

import com.bmp.user.entities.Users;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsersRepository extends JpaRepository<Users, UUID> {
    Optional<Users> findByPhone(String phone);

    /**
     * Look somebody up by email, case-insensitively. Session 65.
     *
     * <p>Added so a salon can invite a stylist it knows by email rather than only by phone —
     * an owner who has the person's card has one or the other, not reliably both.
     *
     * <p>IGNORE CASE deliberately: emails are stored lowercase on write, but a caller typing
     * "Priya@Gmail.com" is the normal case and an exact match would report "no such stylist" for
     * somebody who is plainly there.
     */
    Optional<Users> findByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
}

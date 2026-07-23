package com.bmp.auth.repositories;

import com.bmp.auth.entities.OAuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, UUID> {

    Optional<OAuthIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);
}

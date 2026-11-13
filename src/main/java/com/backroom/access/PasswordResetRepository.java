package com.backroom.access;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordReset r where r.tokenHash = :tokenHash")
    Optional<PasswordReset> lockByTokenHash(String tokenHash);

    List<PasswordReset> findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(UUID userId);
}

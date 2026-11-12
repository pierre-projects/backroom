package com.backroom.access;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);
    boolean existsByEmail(String email);
}

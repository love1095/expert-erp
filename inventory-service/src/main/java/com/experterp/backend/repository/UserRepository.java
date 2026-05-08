package com.experterp.backend.repository;

import com.experterp.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    List<User> findByTenantId(Long tenantId);
    org.springframework.data.domain.Page<User> findByTenantId(Long tenantId, org.springframework.data.domain.Pageable pageable);
    long countByTenantId(Long tenantId);
}

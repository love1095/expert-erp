package com.experterp.backend.repository;

import com.experterp.backend.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    // The current organisation's keys (the tenant filter applies), newest first
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    long countByRevokedAtIsNull();
}

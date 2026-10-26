package com.printair.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShopOperatorRepository extends JpaRepository<ShopOperator, UUID> {
    Optional<ShopOperator> findByEmailIgnoreCase(String email);
}

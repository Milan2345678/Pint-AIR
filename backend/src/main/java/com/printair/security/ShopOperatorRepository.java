package com.printair.security;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ShopOperatorRepository extends JpaRepository<ShopOperator, UUID> {
    Optional<ShopOperator> findByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operator from ShopOperator operator where operator.id = :id")
    Optional<ShopOperator> findLockedById(@Param("id") UUID id);
}

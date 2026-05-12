package com.agentflow.demo.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, String> {

    Optional<AuthRefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);
}

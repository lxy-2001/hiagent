package com.agentflow.web.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, String> {

    Optional<SysUser> findByUsername(String username);
}

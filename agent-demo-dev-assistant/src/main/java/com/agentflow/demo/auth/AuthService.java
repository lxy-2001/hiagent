package com.agentflow.demo.auth;

import com.agentflow.demo.support.Hashing;
import com.agentflow.demo.support.Ids;
import com.agentflow.spring.autoconfigure.AgentFlowProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private final SysUserRepository userRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final AgentFlowProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(SysUserRepository userRepository, AuthRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService, StringRedisTemplate redisTemplate,
                       AgentFlowProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Transactional
    public AuthController.TokenResponse login(String username, String password) {
        SysUser user = userRepository.findByUsername(username)
                .filter(SysUser::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return issueTokens(user);
    }

    @Transactional
    public AuthController.TokenResponse refresh(String refreshToken) {
        String tokenHash = Hashing.sha256Hex(refreshToken);
        AuthRefreshToken stored = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token 无效"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            stored.revoke();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token 已过期");
        }
        stored.revoke();
        SysUser user = userRepository.findById(stored.getUserId())
                .filter(SysUser::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不可用"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(Jwt jwt, String refreshToken) {
        if (jwt != null && jwt.getId() != null && jwt.getExpiresAt() != null) {
            Duration ttl = Duration.between(Instant.now(), jwt.getExpiresAt());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redisTemplate.opsForValue().set("auth:blacklist:" + jwt.getId(), "1", ttl.toSeconds(), TimeUnit.SECONDS);
            }
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepository.findByTokenHashAndRevokedFalse(Hashing.sha256Hex(refreshToken))
                    .ifPresent(AuthRefreshToken::revoke);
        }
    }

    private AuthController.TokenResponse issueTokens(SysUser user) {
        List<String> roles = List.of("USER");
        JwtService.IssuedToken accessToken = jwtService.issueAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = randomToken();
        Instant expiresAt = Instant.now().plus(properties.security().getRefreshTokenTtl());
        AuthRefreshToken stored = new AuthRefreshToken(Ids.newId(), user.getId(), Hashing.sha256Hex(refreshToken),
                expiresAt, Instant.now());
        refreshTokenRepository.save(stored);
        redisTemplate.opsForValue().set("auth:refresh:" + stored.getUserId() + ":" + stored.hashCode(), "1",
                properties.security().getRefreshTokenTtl().toSeconds(), TimeUnit.SECONDS);
        return new AuthController.TokenResponse(accessToken.value(), refreshToken, accessToken.expiresInSeconds());
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

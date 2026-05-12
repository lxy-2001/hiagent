package com.agentflow.demo.auth;

import com.agentflow.demo.support.Ids;
import com.agentflow.spring.autoconfigure.AgentFlowProperties;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final AgentFlowProperties properties;

    public JwtService(JwtEncoder jwtEncoder, AgentFlowProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedToken issueAccessToken(String userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().jwt().getAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(Ids.newId())
                .subject(userId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("username", username)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, properties.security().jwt().getAccessTokenTtl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}

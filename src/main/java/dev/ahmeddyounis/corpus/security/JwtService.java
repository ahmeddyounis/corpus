package dev.ahmeddyounis.corpus.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final CorpusSecurityProperties properties;

    public JwtService(JwtEncoder encoder, CorpusSecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public String issue(UUID userId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("corpus")
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(now)
                .expiresAt(now.plus(properties.jwt().ttl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return properties.jwt().ttl().toSeconds();
    }
}

package dev.ahmeddyounis.corpus.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record UserAccount(@Id UUID id, String username, String password, Instant createdAt) {

    public static UserAccount create(String username, String encodedPassword) {
        return new UserAccount(null, username, encodedPassword, Instant.now());
    }
}

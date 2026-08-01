package dev.ahmeddyounis.corpus.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Idempotently seeds the demo/demo account in keyless environments. */
@Component
@Profile({"local", "keyless", "test"})
@Order(10)
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;

    public DemoUserSeeder(UserRepository users, PasswordEncoder passwordEncoder, JdbcClient jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByUsername("demo").isPresent()) {
            return;
        }
        // Single atomic statement rather than check-then-act: two instances starting
        // together would otherwise race the UNIQUE username, and the resulting
        // DuplicateKeyException escaping an ApplicationRunner kills the process.
        int inserted = jdbc.sql("""
                        INSERT INTO users (username, password) VALUES (:username, :password)
                        ON CONFLICT (username) DO NOTHING
                        """)
                .param("username", "demo")
                .param("password", passwordEncoder.encode("demo"))
                .update();
        if (inserted > 0) {
            log.info("Seeded demo user (username=demo)");
        }
    }
}

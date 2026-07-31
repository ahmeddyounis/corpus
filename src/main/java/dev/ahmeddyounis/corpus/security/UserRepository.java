package dev.ahmeddyounis.corpus.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsername(String username);
}

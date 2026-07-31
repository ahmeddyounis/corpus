package dev.ahmeddyounis.corpus.security;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    public record TokenRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String token, long expiresInSeconds) {
    }

    public record MeResponse(UUID id, String username) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Operation(summary = "Issue a JWT for username/password credentials")
    @PostMapping("/token")
    public TokenResponse token(@RequestBody TokenRequest request) {
        UserAccount user = users.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.password()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return new TokenResponse(jwtService.issue(user.id(), user.username()), jwtService.ttlSeconds());
    }

    @Operation(summary = "Return the authenticated user")
    @GetMapping("/me")
    public MeResponse me(JwtAuthenticationToken auth) {
        return new MeResponse(UUID.fromString(auth.getToken().getSubject()),
                auth.getToken().getClaimAsString("username"));
    }
}

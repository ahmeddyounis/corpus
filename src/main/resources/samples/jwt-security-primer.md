# Stateless JWT authentication in Corpus

Corpus authenticates every API call with a JSON Web Token. The design goal is
statelessness: any instance can validate any request with nothing but the
signing secret, no session table, and no sticky routing.

## Token issuance and shape

`POST /api/auth/token` exchanges a username and password for a signed JWT.
Passwords are stored as bcrypt hashes — never reversible, deliberately slow
to brute-force. The issued token is signed with HMAC-SHA256 (HS256) using a
symmetric secret of at least 256 bits, configured through `CORPUS_JWT_SECRET`
and defaulted only for local development. Claims are minimal: `sub` carries
the user's UUID, a `username` claim aids debugging, `iss` is `corpus`, and
`iat`/`exp` bound the token's 24-hour lifetime. Nothing sensitive rides in
the token: claims are base64url-encoded, not encrypted, and anyone holding a
token can read them.

## Validation on every request

Requests present the token as `Authorization: Bearer <token>`. Spring
Security's OAuth2 resource-server support validates the signature and expiry
on every call before any controller runs. The subject claim becomes the
authenticated principal, and every downstream query — document lists,
retrieval, conversations — filters by that UUID at the storage layer.
Authorization is therefore structural: a valid token for user A physically
cannot read user B's chunks, because scoping is applied in SQL and in vector
filter expressions, not in controller if-statements.

## Why symmetric HS256 and when to switch

A single service that both issues and validates tokens has no key
distribution problem, so a symmetric secret is the simplest correct choice.
The moment a second service needs to validate tokens, asymmetric signing
(RS256 or EdDSA) becomes the right answer: the issuer keeps the private key
and validators fetch the public key, typically via a JWKS endpoint. The
switch is configuration-shaped in Spring Security — swap the HMAC decoder for
a JWKS-based one — which is why staying symmetric now is not a trap.

## Rate limiting as a security control

Authentication answers "who is calling"; rate limiting answers "how much may
they call." Corpus enforces a per-user token bucket (default 30 requests per
minute, configurable via `CORPUS_RATE_LIMIT_RPM`). Exceeding the budget
returns HTTP 429 with a `Retry-After` header stating when the next request
will be admitted. The bucket keys on the authenticated user id, not the IP
address, so a user cannot dodge the limit by rotating addresses, and shared
NAT egress does not starve innocent neighbors.

## Operational cautions

Rotate the signing secret by deploying with two accepted secrets (old and
new) for one token lifetime, then dropping the old one. Keep token TTL short
enough that revocation-by-expiry is acceptable, because pure stateless JWTs
cannot be individually revoked without reintroducing state. Log
authentication failures with enough context to spot credential stuffing —
bursts of 401s across many usernames — and alert on 429 volume, which is
either abuse or a client retry loop misbehaving. And never log the token
itself: it is a bearer credential, and logs outlive intentions.

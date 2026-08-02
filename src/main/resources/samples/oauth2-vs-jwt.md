# OAuth 2.1, OIDC, and token formats: a comparison

Authentication discussions often conflate three separate things: the protocol
that issues a credential, the format of that credential, and the algorithm that
signs it. This note separates them. It is a general comparison and does not
describe how any specific service is configured.

## The protocol layer

OAuth 2.1 is a delegation framework: it lets a user authorise an application to
act on their behalf without sharing a password. OpenID Connect layers identity
on top, adding an ID token that asserts *who* the user is rather than merely
what the bearer may do. Neither dictates a token format — an OAuth access token
may be an opaque random string that the resource server introspects over the
network.

## Symmetric versus asymmetric signing

A symmetric signature (HMAC family: HS256, HS384, HS512) uses one shared secret
for both signing and verification. Every party that can verify a token can also
mint one, so symmetric signing suits a single service that issues and validates
its own credentials.

Asymmetric signing (RS256, ES256, EdDSA) splits the key: the issuer holds the
private half and validators fetch the public half, conventionally from a JWKS
endpoint. This is what makes an identity provider workable — dozens of services
verify tokens none of them could forge. It also enables key rotation without
redeploying validators, since the JWKS document simply publishes a new key id.

The classic vulnerability here is algorithm confusion: a validator that trusts
the token's own `alg` header can be tricked into verifying an RS256 token as
HS256 using the public key as the HMAC secret. Pinning the expected algorithm at
the validator is the defence.

## Refresh tokens and revocation

Access tokens are deliberately short-lived because a stateless token cannot be
revoked individually — validation involves no lookup, so nothing can say "this
one is no longer valid". Refresh tokens compensate: long-lived, stored server
side, revocable, and exchanged for new access tokens. Rotating refresh tokens on
each use, and treating reuse of an already-redeemed one as theft, is the current
recommended practice.

Systems that need immediate revocation without refresh-token machinery
generally add a deny list checked at validation, which trades away exactly the
statelessness that made the design attractive.

## Choosing

A single service with no external validators is well served by short-lived
symmetric tokens: no key distribution, no JWKS fetch, no rotation choreography.
The moment a second service must validate independently, or an external identity
provider enters the picture, asymmetric signing with published keys becomes the
correct answer, and the migration is mostly configuration.

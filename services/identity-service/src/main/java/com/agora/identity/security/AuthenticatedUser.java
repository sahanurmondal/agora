package com.agora.identity.security;

/**
 * Principal reconstructed purely from verified JWT claims ({@code uid}, {@code sub}).
 * No DB lookup on authenticated requests — the token is the session (see design doc:
 * the same reason the edge-gateway validates locally instead of calling /me).
 */
public record AuthenticatedUser(Long id, String username) {
}

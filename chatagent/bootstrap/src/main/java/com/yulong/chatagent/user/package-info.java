/**
 * User authentication module based on short-lived JWT access tokens and
 * Redis-backed refresh tokens.
 *
 * <p>Main flow:</p>
 * <ol>
 *     <li>A user registers or logs in with username and password.</li>
 *     <li>The system issues a stateless access token and an opaque refresh token.</li>
 *     <li>The access token is returned in the HTTP response body.</li>
 *     <li>The refresh token is written to an HttpOnly cookie scoped to {@code /api/auth}.</li>
 *     <li>Protected endpoints validate the access token and populate {@code UserContext}.</li>
 *     <li>When the access token expires, the client calls {@code /api/auth/refresh}
 *     so the server can rotate the refresh token and issue a new access token.</li>
 * </ol>
 *
 * <p>The module is organized by responsibility:
 * application services coordinate the authentication lifecycle,
 * ports define persistence abstractions,
 * infrastructure adapters integrate MyBatis and Redis,
 * and the web layer translates HTTP requests into service calls.</p>
 */
package com.yulong.chatagent.user;

# Production Readiness Roadmap

## Goal

Take this project from a functional web auction system to a production-capable platform with reliable data storage, hardened account/session handling, automated quality checks, and operational safety.

## Current state

The project has already moved beyond a simple demo baseline in a few important areas:

- database-backed persistence is now available and used by default
- JSON storage still exists as a fallback/migration path
- the application is back on a single coherent service/persistence path
- cookie-based session handling is in place
- basic rate limiting exists on key sensitive routes
- audit logging exists for security-relevant actions
- automated service tests exist for a first production milestone
- the project builds and packages cleanly again
- websocket-based live updates are wired back into the app

The system is still not production ready because several major areas are still incomplete:

- no password reset or email verification
- no CSRF strategy for the cookie session model
- limited automated test coverage
- no health checks, metrics, or alerting
- no real image upload pipeline
- no payment, settlement, dispute, or moderation depth
- no CI/CD or production deployment workflow
- persistence is still snapshot-oriented rather than repository/transaction-oriented

## Delivery principles

1. Preserve working auction behavior while hardening the platform.
2. Prefer incremental upgrades over large rewrites.
3. Keep every phase deployable.
4. Add tests whenever production-facing behavior changes.
5. Treat security, persistence, and observability as platform work, not optional polish.

## Target production architecture

- Backend:
  Java service with clear persistence and HTTP boundaries
- Database:
  production-grade relational database such as PostgreSQL
- Sessions:
  secure HTTP-only cookies with CSRF protection and expiry rules
- Storage:
  managed object/file storage for uploaded images
- Live updates:
  WebSocket or SSE instead of polling
- Operations:
  structured logs, metrics, health checks, alerts
- Delivery:
  CI/CD with automated tests and staged deployments

## Phase 1: Persistence and data foundation

### Status

Started and partially completed.

### Completed

- introduced a storage abstraction
- added a database-backed snapshot store
- kept JSON storage as a fallback path
- wired the application to use database storage by default
- merged the runtime application back onto the snapshot-store service path
- added first persistence-focused tests

### Remaining work

1. Move from snapshot-style DB persistence to a true repository/data-access model.
2. Add explicit schema migration management instead of runtime table creation only.
3. Add transaction-safe concurrent bid handling.
4. Add better data migration tooling from JSON into the production database.
5. Remove any remaining fake marketplace-derived counters where possible.

### Exit criteria

- all core auction data lives in the database
- concurrent auction updates are transaction-safe
- schema evolution is managed cleanly

## Phase 2: Authentication and account security

### Status

Started and partially completed.

### Completed

- replaced browser `localStorage` auth flow with cookie-based session handling
- added basic rate limiting on login, registration, password change, and bid submission
- added audit logging for login, logout, account changes, bidding, and admin-sensitive actions
- restored the frontend to the cookie-session API flow so it matches the backend again

### Remaining work

1. Add password reset flow.
2. Add email verification.
3. Add session expiry and idle timeout rules.
4. Add CSRF protection for cookie-authenticated state-changing requests.
5. Add stronger password policy and lockout rules.
6. Expand rate limiting coverage and tune limits by endpoint.
7. Extend audit logging with clearer event taxonomy and retention policy.

### Exit criteria

- cookie sessions are protected against CSRF
- compromised or abandoned sessions expire correctly
- account recovery and verification exist

## Phase 3: Test coverage and quality gates

### Status

Started and partially completed.

### Completed

- added initial service-layer tests for persistence and account safety rules
- Maven test execution now works in the project
- the project now compiles, tests, and packages successfully again

### Remaining work

1. Add unit tests for:
   - authentication rules
   - account update flows
   - auction lifecycle transitions
   - bidding and auto-bidding rules
2. Add integration tests for:
   - login/register/session endpoints
   - create/edit/delete auction
   - bid placement
   - admin auction/user controls
3. Add frontend or end-to-end tests for key user flows.
4. Add regression tests for bugs already fixed during development.
5. Add CI build gates for compile, test, and package.

### Exit criteria

- critical flows are covered by automated tests
- broken core behavior fails the pipeline automatically

## Phase 4: Operations and deployment readiness

### Status

Not started.

### Required work

1. Add health endpoints:
   - liveness
   - readiness
2. Add structured application logs.
3. Add metrics for:
   - auth attempts
   - registrations
   - auction creation
   - bid volume
   - failed requests
4. Add alerting thresholds.
5. Add environment-based configuration.
6. Move secrets and runtime config out of code defaults.
7. Add backup and restore procedures.
8. Add staging and production deployment profiles.
9. Add deployment automation.

### Exit criteria

- the service can be deployed repeatedly and safely
- failures are visible
- backup and recovery are documented and testable

## Phase 5: Marketplace realism and trust

### Status

Not started.

### Required work

1. Replace polling with WebSocket or SSE.
2. Add real image upload and storage.
3. Add file validation and moderation hooks.
4. Add notifications beyond in-app toasts.
5. Add payment and settlement flow.
6. Add fulfillment / winner confirmation flow.
7. Add dispute and moderation workflows for admins.
8. Add richer account history and marketplace activity views.

### Exit criteria

- live updates are not polling-based
- uploaded item media is managed by the platform
- the platform can handle post-auction flows credibly

## Phase 6: Scale and search

### Status

Not started.

### Required work

1. Add pagination for auctions, bids, users, and admin lists.
2. Add richer indexed search and filtering.
3. Add caching where appropriate.
4. Profile heavy query paths.
5. Add load testing for bidding, auth bursts, and browsing.
6. Tune indexes and query design.

### Exit criteria

- large datasets do not degrade the user experience sharply
- concurrent bidding remains correct under load

## Phase 7: Launch hardening

### Status

Not started.

### Required work

1. Add legal and privacy-related pages and flows as needed.
2. Add data retention and deletion policy handling.
3. Add incident response and rollback runbooks.
4. Run security review and penetration testing.
5. Run a staging launch with realistic traffic and workflows.

### Exit criteria

- staging behaves like intended production
- rollback and incident processes are documented
- security blockers are resolved

## Suggested next milestone

The next high-value milestone should focus on closing the current auth/platform gap:

1. Add CSRF protection for cookie sessions.
2. Add health checks and structured logging.
3. Expand automated coverage around auth, account, and admin flows.
4. Decide whether websocket updates stay custom or move to a more production-ready transport boundary.

## Definition of production ready for this project

This project should only be called production ready when all of the following are true:

- database-backed persistence is the stable production path
- session handling is hardened with cookie security and CSRF protection
- critical flows are covered by automated tests
- logs, metrics, health checks, and backups are in place
- admin and account-sensitive actions are auditable
- image, live update, and post-auction flows are platform-managed
- deployment and rollback procedures are documented and tested

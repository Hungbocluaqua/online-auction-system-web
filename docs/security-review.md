# Security Review Checklist

## Authentication & Authorization
- [x] BCrypt password hashing (cost 12)
- [x] SHA-256 → BCrypt migration for legacy passwords
- [x] HttpOnly cookie sessions
- [x] Session expiry: 24h absolute, 30min idle
- [x] CSRF token validation on all state-changing requests
- [x] Password policy: 8-128 chars, upper+lower+digit+special
- [x] Account lockout after 5 failed attempts (15min lock)
- [x] Role-based access control (USER/ADMIN)
- [x] Admin accounts protected from deletion
- [x] Cannot bid on own auction
- [x] Cannot bid against yourself

## Input Validation
- [x] Username: max 64 chars, non-blank
- [x] Password: 8-128 chars, complexity requirements
- [x] Auction name: max 500 chars
- [x] Starting price: positive, < 1e15
- [x] Duration: 1-525600 minutes (1 year max)
- [x] Email format validation
- [x] File upload: type + size (10MB max)
- [x] Bid amount: positive, finite, not NaN

## Network Security
- [x] X-Forwarded-For header NOT trusted (rate limit bypass prevention)
- [x] CORS restricted to localhost:8080
- [x] Rate limiting: login (10/10min), register (5/10min), bid (30/1min), password change (5/10min), account delete (3/10min), auction create (10/10min), password reset (3/10min)

## Data Protection
- [x] Transaction-safe bid processing (autoCommit=false, rollback on error)
- [x] Cascade delete on account removal
- [x] Password reset tokens expire after 1 hour
- [x] Email verification tokens expire after 24 hours
- [x] Audit logging of all security-relevant events

## Known Limitations
- [ ] No HTTPS (HTTP only)
- [ ] No email delivery (tokens returned in response)
- [ ] No real payment gateway integration
- [ ] Single-node only (no distributed sessions)
- [ ] No SQL injection protection beyond PreparedStatements (already safe)
- [ ] No brute-force protection on password reset endpoint
- [ ] Sample seed accounts use known passwords (Admin@1234, etc.)

## Recommendations for Production
1. Add HTTPS/TLS termination (reverse proxy)
2. Integrate real email provider (SendGrid, SES)
3. Add payment gateway (Stripe, PayPal)
4. Use external database (PostgreSQL) with connection pooling
5. Add rate limiting at reverse proxy level (nginx)
6. Implement CAPTCHA on login/register
7. Add 2FA/MFA support
8. Regular security audits and penetration testing

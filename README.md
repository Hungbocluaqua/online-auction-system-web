# Online Auction System Web

Browser-based online auction platform targeting the Vietnam market.

## Stack

- **Backend:** Java 17, HikariCP connection pooling
- **Database:** Embedded H2 (dev/test) or PostgreSQL (production) with Flyway migrations
- **HTTP:** `com.sun.net.httpserver` + raw WebSocket (Java-WebSocket)
- **Frontend:** Vanilla HTML, CSS, and JavaScript SPA
- **Serialization:** Gson 2.11.0
- **Security:** BCrypt password hashing, CSRF protection, CAPTCHA, 2FA/TOTP, session expiry, account lockout
- **Payments:** ZaloPay (Vietnam) — create order, IPN callback, query status
- **Email:** SendGrid (stub for dev, real for production)
- **Monitoring:** Prometheus + Grafana
- **Logging:** Structured JSON logging + audit trail
- **Build:** Maven with maven-shade-plugin (fat JAR)

## Quick Start

### Option 1: Run from source
```bash
mvn clean package -DskipTests
java -jar target/online-auction-system-web-1.0-SNAPSHOT.jar
```

### Option 2: Docker Compose (production stack with PostgreSQL, Redis, nginx, Mailpit, Prometheus, Grafana)
```bash
docker-compose up -d
```

Open **http://localhost:8080** in your browser.

## Default Accounts

Seed passwords are randomly generated on first start for security. Check server logs for initial credentials.

## Features

### Security
- CAPTCHA on login and registration (math challenges)
- 2FA/TOTP setup, enable, and disable
- Strict password policy (8-128 chars, uppercase, lowercase, digit, special character)
- Session-based authentication with 24h absolute / 30min idle expiry
- CSRF protection on all state-changing requests
- Account lockout after 5 consecutive failed login attempts (15min lock)
- Password reset flow with expiring tokens (email delivery via SendGrid)
- Email verification flow
- Security headers: CSP, HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy
- Legacy SHA-256 hash auto-migration to BCrypt
- Rate limiting on auth, bidding, and auction creation

### Auctions
- Create, edit, and delete auctions (sellers)
- **Buy-It-Now** — instant purchase at a fixed price with ZaloPay checkout
- **Reserve Prices** — hidden minimum; auction only completes if reserve is met
- **Auction Scheduling** — set a future start time; auction stays OPEN until then
- Browse active and ended auctions with live countdown timers
- **Pagination** with configurable page size and page number
- **Category browsing** and **search filtering**
- Manual bidding with validation
- Auto-bid configuration with incremental proxy bidding
- Real-time updates via WebSocket
- Image upload (JPEG, PNG, GIF, WebP — validated by magic bytes)
- Soft-close / sniping protection (30s extension on late bids)

### Payments (ZaloPay)
- **Create Order** — generates ZaloPay checkout URL + QR code for VND payments
- **IPN Callback** — auto-confirms payment via ZaloPay POST callback, updates DB, generates invoice, notifies user
- **Query Status** — fallback polling to check payment status
- **Buy-It-Now** — direct checkout flow for fixed-price purchases
- **Refund Requests** — winners can request refunds on paid/delivered auctions
- **Admin Refund Processing** — approve or reject refund requests
- Fulfillment tracking: `none` → `awaiting_payment` → `paid` → `delivered` / `refunded`

### User Features
- **Watchlist** — save and track auctions of interest (add/remove from cards or detail view)
- **Notifications** — in-app bell badge with unread count, mark individual or all as read
- **Account History** — tabbed view: All Activity, My Bids, Won Auctions, Sold Items
- **Invoices** — auto-generated receipts for completed payments
- **Categories** — clickable category grid showing active auction counts
- Password change, username update, email verification
- Account deletion with cascade cleanup

### Admin
- List all users and manage auction limits
- Delete users (except admins)
- Cancel auctions
- Confirm payments and resolve disputes
- **Refund Management** — view all refund requests, approve or reject with payment reversal
- **System metrics** — total users, active auctions, bids, payments, disputes
- **Database backup/export** — JSON backup of all data
- **Dispute management** — view, resolve, and take action on disputes

### Marketplace
- Payment initiation by winning bidders via ZaloPay
- Auto-confirm via ZaloPay IPN callback (no manual admin approval needed)
- Fulfillment tracking (mark received)
- Dispute reporting and resolution
- In-app notifications with WebSocket broadcast
- VND as default currency

### Operations
- `/api/health` — health check with DB status
- `/api/health/live` — liveness probe
- `/api/health/ready` — readiness probe
- `/api/metrics` — Prometheus-style metrics
- `/api/admin/backup` — database backup endpoint
- `/api/admin/metrics` — system metrics endpoint
- `/api/admin/refunds` — list all refund requests (admin)
- `/api/zalopay/callback` — ZaloPay IPN webhook (auto-confirm)
- `/api/zalopay/query` — manual payment status query
- Structured JSON logging
- Audit log (`data/audit.log`)

## Project Structure

```
src/main/java/com/auction/web/
├── WebAuctionApplication.java    # Entry point + ZaloPay init
├── Logger.java                   # Structured JSON logging
├── http/
│   ├── ApiHandler.java           # HTTP routing + CSRF + rate limiting + CAPTCHA + security headers
│   ├── HealthHandler.java        # Health & metrics endpoints
│   ├── MetricsCollector.java     # Prometheus-style metrics
│   ├── RateLimiter.java          # Per-key rate limiting
│   ├── AuditLogger.java          # Async audit log
│   ├── HtmlSanitizer.java        # XSS prevention
│   ├── HttpUtil.java             # HTTP utilities
│   └── StaticFileHandler.java    # Static file serving
├── model/
│   ├── User.java                 # User entity + password hashing + 2FA fields
│   ├── Auction.java              # Auction entity + bid logic + buy-it-now + reserve
│   ├── Item.java                 # Item hierarchy
│   └── BidTransaction.java       # Bid record
├── service/
│   ├── AuctionService.java       # Core business logic (auth, bidding, payments, refunds, disputes, notifications, watchlist, 2FA, invoices, backup, scheduling)
│   ├── DatabaseManager.java      # HikariCP + schema init + Flyway + PostgreSQL support
│   ├── EmailService.java         # SendGrid email integration
│   ├── ZaloPayService.java       # ZaloPay API: create order, verify IPN MAC, query status
│   ├── StripeService.java        # Legacy Stripe (deprecated, kept for reference)
│   └── WebSocketServerImpl.java  # WebSocket server
├── dto/                          # Request/response DTOs
└── persistence/                  # JSON/DB snapshot stores

src/main/resources/
├── db/migration/                 # Flyway SQL migrations
│   └── V1__Initial_schema.sql
├── static/
│   ├── index.html                # SPA entry point (all views)
│   ├── app.js                    # Frontend application (routing, rendering, API, WebSocket)
│   └── styles.css                # Styles
└── application.properties        # Configuration

monitoring/
└── prometheus.yml                # Prometheus scrape config

nginx/
└── nginx.conf                    # Reverse proxy + TLS config
```

## Configuration

### Environment Variables (Production)

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:h2:file:./data/auction-db` (H2 fallback) |
| `DB_USER` | Database username | `sa` |
| `DB_PASSWORD` | Database password | |
| `SENDGRID_API_KEY` | SendGrid API key | (stub mode — logs to console) |
| `EMAIL_FROM` | From email address | `noreply@auctionsystem.com` |
| `EMAIL_FROM_NAME` | From name | `Auction System` |
| `ZALOPAY_APP_ID` | ZaloPay application ID | (stub mode) |
| `ZALOPAY_KEY1` | ZaloPay HMAC key for signing requests | |
| `ZALOPAY_KEY2` | ZaloPay HMAC key for verifying IPN callbacks | |
| `ZALOPAY_PRODUCTION` | Use production ZaloPay API (`true`/`false`) | `false` (sandbox) |
| `BASE_URL` | Application base URL for callbacks | `http://localhost:8080` |
| `APP_ENV` | Environment | `development` |

### application.properties

```properties
http.port=8080
ws.port=8889
db.url=jdbc:h2:file:./data/auction-db;AUTO_SERVER=TRUE
db.user=sa
db.password=
session.ttl.seconds=86400
session.idle.timeout.seconds=1800
cors.origin=http://localhost:8080
bcrypt.cost=12
```

## Testing

```bash
mvn test
```

44 tests (10 integration + 14 unit + 20 security) — all passing.

## Docker

### Simple (H2 only)
```bash
docker build -t auction-system .
docker run -p 8080:8080 -p 8889:8889 auction-system
```

### Full Stack (PostgreSQL, Redis, nginx, Mailpit, Prometheus, Grafana)
```bash
docker-compose up -d
```

Services:
- **App:** http://localhost:8080
- **Mailpit (email preview):** http://localhost:8025
- **Prometheus:** http://localhost:9090
- **Grafana:** http://localhost:3000

## Troubleshooting

- **"Column not found" errors:** Delete stale `data/auction-db.*` files and restart.
- **Changes not visible:** Hard-refresh browser (`Ctrl+Shift+R` / `Ctrl+F5`) — `app.js` is aggressively cached.
- **Port already in use:** Kill existing Java processes or change ports in `application.properties`.
- **CAPTCHA required:** All login and registration requests must include `captchaChallengeId` and `captchaAnswer`. Get a challenge via `GET /api/captcha`.
- **ZaloPay not working:** Ensure `ZALOPAY_APP_ID`, `ZALOPAY_KEY1`, and `ZALOPAY_KEY2` are set. Sandbox mode is default — set `ZALOPAY_PRODUCTION=true` for live.
- **Emails not sending:** Without a real SendGrid API key, emails are stubbed to console logs. Set `SENDGRID_API_KEY` to a real key or use Mailpit in Docker for local preview.

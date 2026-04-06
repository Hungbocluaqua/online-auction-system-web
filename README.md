# Online Auction System Web

Browser-based auction platform for listing, bidding, buy-it-now checkout, refunds, disputes, notifications, and basic admin operations.

## What This Project Is

- Backend: Java 17, `com.sun.net.httpserver`, Gson, HikariCP
- Database: H2 by default for local development, PostgreSQL supported through environment/config overrides
- Frontend: vanilla HTML/CSS/JavaScript SPA
- Realtime: Java-WebSocket server for auction updates
- Security: BCrypt, CSRF protection, CAPTCHA, session expiry, lockout, optional TOTP 2FA
- Payments: ZaloPay integration with stub mode when credentials are not configured

## Current Status

- Local development ready
- Docker build and Docker Compose setup included
- Not production-ready by default

Production caveats:

- The default database is embedded H2.
- If `ADMIN_PASSWORD` is not set, the app creates a bootstrap admin and logs the generated password.
- Email and payment integrations run in stub mode until credentials are configured.

## Features

- User registration and login with CAPTCHA
- Optional 2FA setup and verification
- Password reset and email verification
- Auction creation, editing, deletion, scheduling, and buy-it-now pricing
- Manual bidding and auto-bid configuration
- Watchlist and in-app notifications
- Payment initiation, fulfillment tracking, refund requests, and disputes
- Admin user management, auction limits, refunds, disputes, backup, and metrics
- Health endpoints and Prometheus-style metrics output

## Requirements

- Java 17+
- Maven 3.8+

## Run Locally

Build:

```powershell
mvn clean package -DskipTests
```

Run:

```powershell
java -jar target/online-auction-system-web-1.0-SNAPSHOT.jar
```

Open:

- App: `http://localhost:8080`

Default local ports come from [application.properties](src/main/resources/application.properties):

- HTTP: `8080`
- WebSocket: `8889`

## Testing

Run the test suite:

```powershell
mvn test
```

Package without tests:

```powershell
mvn -DskipTests package
```

## Default Admin Access

The app bootstraps an `admin` account on first start.

- If `ADMIN_PASSWORD` is set, that value is used.
- If `ADMIN_PASSWORD` is not set, a random password is generated.
- The generated password is logged during bootstrap and written to `data/bootstrap-admin-credentials.log`.

This behavior is convenient for local setup and should be changed before any real deployment.

## Configuration

The app reads from `application.properties` and allows environment variables to override key values through [AppConfig.java](src/main/java/com/auction/web/config/AppConfig.java).

Important settings:

| Variable | Default |
| --- | --- |
| `HTTP_PORT` | `8080` |
| `WS_PORT` | `8889` |
| `DATABASE_URL` / `DB_URL` | `jdbc:h2:file:./data/auction-db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;LOCK_MODE=3;CACHE_SIZE=131072` |
| `DATABASE_USER` / `DB_USER` | `sa` |
| `DATABASE_PASSWORD` / `DB_PASSWORD` | empty |
| `SESSION_TTL_SECONDS` | `86400` |
| `SESSION_IDLE_TIMEOUT_SECONDS` | `1800` |
| `BASE_URL` | `http://localhost:8080` |
| `CORS_ORIGIN` | derived from `BASE_URL` |
| `ADMIN_PASSWORD` | generated if unset |
| `SENDGRID_API_KEY` | unset, email stub mode |
| `EMAIL_FROM` | env only |
| `EMAIL_FROM_NAME` | env only |
| `ZALOPAY_APP_ID` | unset, payment stub mode |
| `ZALOPAY_KEY1` | unset |
| `ZALOPAY_KEY2` | unset |
| `ZALOPAY_PRODUCTION` | `false` |

Default local properties:

```properties
http.port=8080
ws.port=8889
db.url=jdbc:h2:file:./data/auction-db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;LOCK_MODE=3;CACHE_SIZE=131072
db.user=sa
db.password=
session.ttl.seconds=86400
session.idle.timeout.seconds=1800
base.url=http://localhost:8080
cors.origin=http://localhost:8080
bcrypt.cost=12
```

## Docker

Build the image:

```powershell
docker build -t online-auction-system-web .
```

Run the container:

```powershell
docker run -p 8080:8080 -p 8889:8889 online-auction-system-web
```

Compose stack:

```powershell
docker-compose up -d
```

The compose file includes:

- app container
- nginx
- PostgreSQL
- Redis
- Mailpit
- Prometheus
- Grafana

Note: the app currently uses PostgreSQL, Mailpit, Prometheus, and Grafana more directly than Redis. Redis is present in the compose stack but is not a core runtime dependency in the current Java service.

## HTTP Endpoints

Operational endpoints:

- `/api/health`
- `/api/health/live`
- `/api/health/ready`
- `/api/metrics`

Common app endpoints:

- `/api/login`
- `/api/register`
- `/api/logout`
- `/api/auctions`
- `/api/categories`
- `/api/notifications`
- `/api/watchlist`
- `/api/invoices`
- `/api/account/history`

Admin-oriented endpoints:

- `/api/admin/refunds`
- `/api/admin/disputes`
- `/api/admin/metrics`
- `/api/admin/backup`

Payment-related endpoints:

- `/api/zalopay/callback`
- `/api/zalopay/query`

## Project Layout

```text
src/main/java/com/auction/web/
  WebAuctionApplication.java
  Logger.java
  config/
  dto/
  http/
  model/
  persistence/
  service/

src/main/resources/
  application.properties
  static/

monitoring/
  prometheus.yml

nginx/
  nginx.conf
```

## Local Data And Generated Files

At runtime the app creates local data such as:

- `data/auction-db.*`
- `data/audit.log`
- `data/bootstrap-admin-credentials.log`
- uploaded files under `uploads/`
- Maven build output under `target/`

These should not be committed. The repository now ignores them through `.gitignore`.

## Troubleshooting

- Port already in use: change `http.port` / `ws.port` or stop the other process
- Login/register fails with CAPTCHA error: fetch a new challenge from `GET /api/captcha`
- Bootstrap admin password unknown: check startup logs or `data/bootstrap-admin-credentials.log`
- ZaloPay not redirecting: configure `ZALOPAY_APP_ID`, `ZALOPAY_KEY1`, and `ZALOPAY_KEY2`
- Email not sending: configure `SENDGRID_API_KEY`, otherwise email runs in stub mode
- Old schema/data problems: remove local H2 files under `data/` only if you do not need the local data

## Notes

- The frontend is still a single-file SPA in `src/main/resources/static/app.js`.
- The main service layer is still concentrated in `AuctionService.java`.
- This repository is in a stronger local-dev/staging shape than true production shape.

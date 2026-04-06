# Incident Response Runbooks

## 1. Database Corruption
**Symptoms:** Server fails to start, `auction-db.lock.db` errors, SQL exceptions on startup.

**Resolution:**
1. Stop the server
2. Delete `data/auction-db.lock.db` and `data/auction-db.trace.db`
3. Restart server: `mvn exec:java`
4. Verify data integrity via `/api/auctions`

**Prevention:** Regular backups via `/api/admin/backup` endpoint.

---

## 2. WebSocket Server Down
**Symptoms:** Live auction updates not received, no real-time bid notifications.

**Resolution:**
1. Check port 8889 availability: `netstat -an | findstr 8889`
2. Kill conflicting process if needed
3. Restart application
4. Server will log: "WARNING: Continuing without WebSocket server" if port unavailable

**Impact:** Users can still bid; only real-time notifications are affected.

---

## 3. Rate Limit Bypass Attempt
**Symptoms:** Unusual spike in login attempts, bid volume, or registrations from single IP.

**Resolution:**
1. Check audit logs in `data/audit.log`
2. Verify rate limiter is active (check `RateLimiter.java`)
3. If `X-Forwarded-For` header is being abused, confirm it's not trusted (already removed)
4. Consider manual IP block at firewall level

---

## 4. Data Breach Suspected
**Symptoms:** Unauthorized admin access, unusual account changes, leaked credentials.

**Resolution:**
1. Immediately rotate all admin passwords
2. Check audit logs for unauthorized admin actions
3. Review session table for suspicious tokens: `SELECT * FROM sessions`
4. Force logout all users: `DELETE FROM sessions`
5. Review `failed_login_attempts` for brute force patterns
6. Notify affected users

---

## 5. High Memory Usage
**Symptoms:** Slow responses, OOM errors, high CPU.

**Resolution:**
1. Check `/api/health` for uptime and DB status
2. Check `/api/metrics` for request volume
3. Restart server if memory leak suspected
4. Review `eventQueue` size (max 100 entries, auto-trimmed)

---

## 6. Auction State Inconsistency
**Symptoms:** Auctions show as RUNNING past end time, bids accepted on finished auctions.

**Resolution:**
1. Auction monitor runs every 5 seconds automatically
2. Manually fix: `UPDATE auctions SET state = 'FINISHED' WHERE end_time < NOW() AND state = 'RUNNING'`
3. Restart server to reset monitor

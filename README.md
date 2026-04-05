# Online Auction System Web

Browser-based rewrite of the original desktop auction system.

## Stack

- Java 17
- Maven
- Built-in Java `HttpServer`
- Vanilla HTML, CSS, and JavaScript frontend
- Gson for JSON serialization

## Run

```bash
mvn exec:java
```

Open `http://localhost:8080`.

## Sample accounts

- `bidder1` / `password`
- `seller1` / `password`
- `admin` / `password`

## Features

- Login and registration
- Browse auctions and inspect bid history
- Manual bidding and auto-bid configuration
- Seller auction creation, editing, and deletion
- Admin user listing and auction cancellation

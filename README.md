# En Route Website — Kotlin Backend

This is the **Kotlin/Ktor** rewrite of the forum backend, living in
`backend/src/main/kotlin/`. It's a from-scratch reimplementation of the same
API that `backend/app.py` (Flask) provides — same routes, same JSON shapes
(snake_case keys), same SQLite database file — so the existing frontend
(`forums.html`, `thread.html`, etc.) works against either one unchanged.


## Tech Stack

- **Language:** Kotlin (JVM), toolchain pinned to **JDK 17**
- **Framework:** [Ktor](https://ktor.io/) 2.3.12 (Netty engine)
- **Serialization:** `kotlinx.serialization` (JSON, snake_case keys to match
  the Python version's `jsonify()` output)
- **Database:** SQLite via `sqlite-jdbc` (same `forum.db` format as the
  Python version)
- **Markdown rendering:** `commonmark`
- **HTML sanitizing:** `jsoup` (Kotlin's equivalent of Python's `bleach`)
- **Password hashing:** `bouncycastle`
- **Build tool:** Gradle (via the Gradle Wrapper, `./gradlew`), Gradle 8.7

## Project Structure

```
backend/
├── build.gradle.kts              # Dependencies & build config
├── settings.gradle.kts            # Project name, JDK toolchain resolver
├── gradlew / gradlew.bat          # Gradle wrapper — no local Gradle install needed
└── src/
    ├── main/kotlin/
    │   ├── Application.kt         # Entry point: server setup, routing, error handling
    │   ├── StaticRoutes.kt        # Serves the static HTML/CSS/JS/images (allowlisted)
    │   ├── db-handler.kt          # SQLite connection + schema helpers
    │   ├── health.kt              # GET /api/health
    │   ├── session-management.kt  # register/login/logout (see warning above)
    │   ├── me.kt                  # GET /api/me
    │   ├── categories.kt          # GET /api/categories
    │   ├── crew.kt                # GET /api/crew
    │   ├── threads.kt             # Thread CRUD + pin/lock
    │   ├── posts.kt               # Post edit/delete/reply
    │   ├── reactions.kt           # Post reactions
    │   ├── reports.kt             # User reports
    │   ├── moderation.kt          # Moderator-only actions
    │   ├── profile.kt             # User profile get/update/role change
    │   ├── search.kt              # GET /api/search
    │   ├── role.kt                # Role enum + permission checks
    │   ├── rate-limit.kt          # In-memory fixed-window rate limiter
    │   ├── request-helper.kt      # Shared request-parsing helpers
    │   └── markdown-render.kt     # Markdown → sanitized HTML rendering
    ├── test/kotlin/
    │   ├── AuthRouteTest.kt
    │   ├── HealthRouteTest.kt
    │   └── StaticRoutesTest.kt
    └── user_manager.py            # (leftover) Python admin GUI — see note below
```


## Requirements

- **JDK 17** (the Gradle wrapper can auto-provision this via the
  `foojay-resolver-convention` plugin in `settings.gradle.kts` — you don't
  strictly need JDK 17 pre-installed, just *a* JDK for Gradle itself to run)
- A Linux server with SSH access for deployment
- Nginx (reverse proxy) + a domain (for HTTPS)
- No separate database server — SQLite is a single file (`forum.db`)

## Building

From the `backend/` directory:

```bash
./gradlew build
```

This compiles the Kotlin sources, runs the test suite (`AuthRouteTest`,
`HealthRouteTest`, `StaticRoutesTest`), and produces a runnable distribution
under `build/`.

To just run it directly without a full build:

```bash
./gradlew run
```

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `5000` | Port the server listens on |
| `PROJECT_ROOT` | current working directory | Root folder containing `frontpage.html`, `public/`, `images/`, etc. — **must** be set explicitly by whatever process launches the jar, since the default only works if you run it from the project root by hand |
| `DATABASE_PATH` | `forum.db` | Path to the SQLite database file |

The server always binds to `0.0.0.0` (all interfaces) — put it behind Nginx
as a reverse proxy rather than exposing it directly to the internet.

## Static File Serving

Unlike the Python version — which serves *any* file under the project root
by path — the Kotlin version uses an **explicit allowlist**
(`StaticRoutes.kt`) of pages it will serve at the site root:

```
frontpage.html, fleet.html, crew.html, careers.html,
register.html, forums.html, thread.html, route-map.html
```

plus the `public/` and `images/` directories in full. Anything not on that
list gets a `403 Forbidden` — this is a deliberate hardening over the Python
version, so backend source or local notes accidentally copied next to the
HTML files can't be served by path guessing.

## API

Same endpoints as the Python backend — see the root project README for the
full table. All JSON responses use snake_case keys (e.g. `thread_id`,
`content_html`) to stay compatible with the existing frontend.

## Deployment (systemd + Nginx)

### 1. Build a distribution on the server (or build locally and copy it over)

```bash
cd backend
./gradlew installDist
```
This produces a runnable app under `build/install/enroute-backend/`
(a `bin/` launcher script plus `lib/` jars) — no JRE-juggling needed beyond
having JDK 17 available at runtime.

### 2. systemd service

`/etc/systemd/system/enroute-backend.service`:
```ini
[Unit]
Description=En Route Kotlin Backend
After=network.target

[Service]
User=your_user
WorkingDirectory=/var/www/enroute-website
Environment=PROJECT_ROOT=/var/www/enroute-website
Environment=DATABASE_PATH=/var/www/enroute-website/backend/forum.db
Environment=PORT=5000
ExecStart=/var/www/enroute-website/backend/build/install/enroute-backend/bin/enroute-backend
Restart=always

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload
sudo systemctl enable enroute-backend
sudo systemctl start enroute-backend
```

### 3. Nginx reverse proxy

Same as the Python version — proxy `/` to `127.0.0.1:5000` with the usual
`X-Real-IP` / `X-Forwarded-For` / `X-Forwarded-Proto` headers, then Certbot
for HTTPS. See the root README's Nginx section; only the upstream process
changes (Gunicorn → the Ktor jar), not the Nginx config itself.

## Testing

```bash
./gradlew test
```

Runs `AuthRouteTest`, `HealthRouteTest`, and `StaticRoutesTest` using
`ktor-server-test-host` against an isolated test database (via the
`DATABASE_PATH` override in `db-handler.kt`).
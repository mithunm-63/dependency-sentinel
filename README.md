# Dependency Sentinel — Phase 6

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into dependency intelligence, known vulnerability findings, graph-aware impact analysis, continuous project health, and a GitHub-based developer workflow.

## Product flow

```text
Create project → Analyze pom.xml → Resolve graph → Check OSV → Trace impact → Compare scans → Track health → Connect GitHub
```

## Phase 6 — GitHub / DevSecOps

Phase 6 adds a lightweight GitHub integration without introducing another dashboard or authentication flow:

```text
Public GitHub repository
        ↓
Resolve repository default branch (or supplied branch)
        ↓
Fetch root pom.xml
        ↓
Reuse the existing Maven + OSV pipeline
        ↓
Store a normal Dependency Sentinel scan
```

The UI exposes a **GitHub** control next to the existing Overview, Inventory, Tree, Graph, Security, and Health controls. It accepts a public repository URL and an optional branch. The backend only accepts HTTPS URLs hosted on `github.com`, fetches the root `pom.xml`, and passes it through the same dependency/security pipeline used for uploaded files.

Example repository for testing: `https://github.com/spring-projects/spring-petclinic` on its `main` branch. The repository is a public Maven project with a root `pom.xml`.

## Earlier phases

- Phase 1: project onboarding and Maven scanning
- Phase 2: direct + transitive dependency graph
- Phase 3: OSV vulnerability intelligence and explainable security scoring
- Phase 4: graph-aware impact analysis and remediation context
- Phase 5: scan history, dependency drift, security movement, and project health
- Phase 6: public GitHub repository scanning and a GitHub/DevSecOps entry point

## Stack

- Backend: Java 21 + Spring Boot + Spring Data JPA
- Database: PostgreSQL (Neon for hosted deployment)
- Maven model: Maven 3.9.11
- Maven Resolver: 1.9.24
- Vulnerability intelligence: OSV.dev
- Frontend: React + Vite
- Local orchestration: Docker Compose

## Hosted deployment

Recommended architecture:

```text
GitHub
  ├── Vercel → frontend/
  └── Render → backend/ → Neon PostgreSQL
```

### Render backend

```text
Branch: main
Root Directory: backend
Runtime: Docker
Dockerfile: Dockerfile
Health Check Path: /api/health
```

Environment variables:

```text
DATABASE_URL=jdbc:postgresql://NEON_HOST:NEON_PORT/NEON_DATABASE?sslmode=require&channel_binding=require
DATABASE_USERNAME=NEON_USERNAME
DATABASE_PASSWORD=NEON_PASSWORD
FRONTEND_ORIGIN=https://YOUR-FRONTEND.vercel.app
```

Optional scan limits:

```text
DEPENDENCY_SCAN_MAX_NODES=500
DEPENDENCY_SCAN_MAX_DEPTH=20
DEPENDENCY_SCAN_MAX_VULNERABILITIES=100
```

### Vercel frontend

```text
Root Directory: frontend
Framework: Vite
Build Command: npm run build
Output Directory: dist
```

Environment variable:

```text
VITE_API_URL=https://YOUR-RENDER-SERVICE.onrender.com/api
```

## API

```http
POST /api/projects
Content-Type: application/json

{"name":"My Banking API"}
```

```http
POST /api/projects/{id}/scan
Content-Type: multipart/form-data

file=<pom.xml>
```

```http
POST /api/projects/{id}/github/scan
Content-Type: application/json

{"repoUrl":"https://github.com/owner/repository","branch":"main"}
```

```http
GET /api/projects
GET /api/projects/{id}
GET /api/projects/{id}/dependencies
GET /api/projects/{id}/dependencies/tree
GET /api/projects/{id}/dependencies/graph
GET /api/projects/{id}/security
GET /api/projects/{id}/vulnerabilities
GET /api/projects/{id}/vulnerabilities/{findingId}/impact
GET /api/projects/{id}/scans
GET /api/projects/{id}/health

POST /api/projects/{id}/security/rescan
```

## Security model

The security score is explainable rather than pretending to be CVSS. Each finding contributes risk points using OSV severity plus its direct/transitive position and dependency depth. The project score is `100 - capped risk points`.

## Impact analysis

For a selected finding, Dependency Sentinel reconstructs dependency paths from direct entry points to the affected package and reports:

```text
Affected dependency
Severity
Entry points
Upstream nodes
Blast radius
Dependency depth
Why this package is present
Shortest dependency paths
Recommended fix
```

## Project health

Phase 5 compares the newest stored dependency snapshot with the previous scan:

```text
Current scan
    ↓
Previous scan
    ↓
Added / Removed / Updated / Unchanged
    ↓
Security finding delta
    ↓
Health score + highlights
```

## Demo files

```text
samples/pom.xml
samples/vulnerable-pom.xml
```

For scan-to-scan drift testing, any two valid Maven POM files can be uploaded. The UI accepts Maven XML filenames and normalizes them to the backend's `pom.xml` upload contract. The GitHub integration always supplies the fetched file to the backend as `pom.xml`.

## Production verification

Use `docs/production-smoke-test.md` after each production deployment. It covers backend health, frontend boot/CORS, project creation, Maven scanning, security findings, health comparison, GitHub scanning, deployment gates, and secret hygiene.

The repository CI validates both the Java/Maven package and Vite production build on every push and pull request to `main`.

## Safety

The backend never executes uploaded or fetched project code. It reads Maven metadata, resolves dependencies from Maven Central, and queries OSV.dev. GitHub integration is restricted to public repositories on `github.com`, limits the fetched POM to 2 MB, validates branch input, and reuses the existing scan limits.

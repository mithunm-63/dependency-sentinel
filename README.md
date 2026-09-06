# Dependency Sentinel — Phase 5

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into dependency intelligence, known vulnerability findings, actionable impact analysis, and continuous project health.

## Phase 5 flow

```text
Create project → Analyze pom.xml → Resolve graph → Check OSV → Trace impact → Compare scans → Track project health
```

## What Phase 5 adds

- Persistent scan history for each project
- Latest-vs-previous dependency comparison
- Added, removed, updated, and unchanged dependency counts
- Version/scope/directness drift detection
- Security finding count delta and security score delta
- Explainable project health score and health level
- Human-readable health highlights
- Compact Project Health workspace opened from the existing project tabs
- Health API that reuses stored dependency snapshots instead of re-running Maven resolution

Phase 3 uses OSV.dev for package/version vulnerability matching. Phase 4 adds graph-aware impact context. Phase 5 uses the stored scan snapshots to make changes between scans visible without adding a separate monitoring product or complicated navigation.

## Stack

- Backend: Java 21 + Spring Boot + Spring Data JPA
- Database: PostgreSQL (Neon for hosted deployment)
- Maven model: Maven 3.9.11
- Maven Resolver: 1.9.24
- Vulnerability intelligence: OSV.dev
- Frontend: React + Vite
- Local orchestration: Docker Compose

## Local development

```bash
docker compose up --build
cd frontend
npm install
npm run dev
```

Backend: `http://localhost:8081`
Frontend: `http://localhost:5173`
Health: `http://localhost:8081/api/health`

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

Render supplies the `PORT` environment variable for the web service; the application uses it automatically.

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

The frontend also normalizes this setting to `/api` at runtime, so accidentally entering the Render origin without the suffix does not generate requests to the wrong endpoint.

Deploy the frontend, then put its final URL into Render's `FRONTEND_ORIGIN` and redeploy the backend.

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

## Security score

The score is intentionally explainable rather than pretending to be CVSS. Each finding contributes risk points from its OSV severity, whether the dependency is direct or transitive, and its graph depth. The project score is `100 - capped risk points`.

## Impact analysis

For a selected finding, Dependency Sentinel reconstructs graph paths from direct dependencies to the affected package. The impact panel reports:

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

A fixed version is shown only when it is present in the advisory data; otherwise the product explicitly tells the user to review the advisory/vendor guidance rather than inventing a version.

## Continuous project health

Phase 5 compares the newest stored dependency snapshot with the immediately previous scan:

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

The health score is intentionally separate from the security score. Security score reflects known vulnerability risk; health score also considers dependency churn and newly introduced vulnerability findings.

The Project Health workspace is available as a **Health** control beside Overview, Inventory, Tree, Graph, and Security. It opens for the currently selected project, so users do not need a second dashboard or another project-selection workflow.

## Demo vulnerable project

Use:

```text
samples/vulnerable-pom.xml
```

The sample pins Log4j 2.14.1 so the security workflow can demonstrate real OSV findings.

## Safety

The backend does not execute uploaded project code. It reads Maven metadata, resolves dependencies from Maven Central, and queries OSV.dev for vulnerability intelligence. A scan stores at most 500 dependency nodes, traverses at most 20 levels, and fetches at most 100 unique vulnerability records by default.

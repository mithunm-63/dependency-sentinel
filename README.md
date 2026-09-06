# Dependency Sentinel — Phase 3

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into dependency intelligence: direct and transitive relationships plus known vulnerability findings for the exact versions in a scan.

## Phase 3 flow

```text
Create project → Upload pom.xml → Resolve graph → Query OSV → Review risk and findings
```

## What Phase 3 adds

- OSV.dev integration using the current `/v1/querybatch` API for Maven package/version matching
- Vulnerability finding persistence per scan and dependency
- OSV identifiers plus CVE aliases when available
- Severity classification
- CVSS vector capture when supplied by OSV
- First listed fixed version from the affected range events
- Vulnerability summaries/details and reference links
- Project security score and risk label
- Risk points weighted by severity, direct/transitive status, and dependency depth
- Security status that degrades gracefully when OSV is temporarily unavailable
- Security findings dashboard with filters and detail view
- Intentionally vulnerable demo Maven file under `samples/vulnerable-pom.xml`

OSV documents package/version queries and the batched `/v1/querybatch` endpoint. OSV records can include aliases, summaries/details, severity, affected ranges, and references; fixed versions are represented through range events. See the official API documentation at https://google.github.io/osv.dev/api/ and https://google.github.io/osv.dev/post-v1-querybatch/.

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
GET /api/projects/{id}/scans
```

## Security score

The Phase 3 score is intentionally explainable rather than pretending to be CVSS. Each finding contributes risk points from its OSV severity, whether the dependency is direct or transitive, and its graph depth. The project score is `100 - capped risk points`.

Unknown OSV severity is retained as `UNKNOWN` and receives a small baseline risk contribution rather than being silently treated as high severity.

## Demo vulnerable project

Use:

```text
samples/vulnerable-pom.xml
```

The sample pins Log4j 2.14.1 so the security dashboard can demonstrate real OSV findings. OSV currently lists `org.apache.logging.log4j:log4j-core` 2.14.1 among affected versions for multiple advisories, including the critical Remote Code Injection advisory GHSA-jfh8-c2jp-5v3q. See https://osv.dev/vulnerability/GHSA-jfh8-c2jp-5v3q.

## Safety

The backend does not execute uploaded project code. It reads Maven metadata, resolves dependencies from Maven Central, and queries OSV.dev for vulnerability intelligence. A scan stores at most 500 dependency nodes, traverses at most 20 levels, and fetches at most 100 unique vulnerability records by default.

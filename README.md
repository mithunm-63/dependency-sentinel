# Dependency Sentinel — Phase 2

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into a searchable dependency inventory and, in Phase 2, resolves the direct-to-transitive dependency graph.

## Phase 2 flow

```text
Create project → Upload pom.xml → Resolve dependencies → Explore inventory/tree/graph
```

## What Phase 2 adds

- Maven Resolver-backed transitive dependency collection
- Direct vs. transitive classification
- Dependency depth
- Persistent scan snapshots
- Parent/child dependency relationships
- Searchable dependency inventory
- Direct/transitive filtering
- Expandable dependency tree
- Interactive SVG dependency graph
- Scan safety caps for very large projects

The resolver uses Maven Central for dependency collection. Apache Maven documents `RepositorySystem.collectDependencies` as the operation that collects transitives and builds the dependency graph. Maven 3.9.11 is aligned with Maven Resolver 1.9.24.

## Stack

- Backend: Java 21 + Spring Boot + Spring Data JPA
- Database: PostgreSQL (Neon for hosted deployment)
- Maven model: Maven 3.9.11
- Maven Resolver: 1.9.24
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
GET /api/projects/{id}/scans
```

## Safety limits

By default, a scan stores at most 500 unique dependency nodes and traverses at most 20 levels deep. Set these through environment variables when needed:

```text
DEPENDENCY_SCAN_MAX_NODES=500
DEPENDENCY_SCAN_MAX_DEPTH=20
```

The backend does not execute uploaded project code. It accepts a small `pom.xml`, reads Maven dependency metadata, and contacts Maven Central through Maven Resolver to build the dependency graph. Later phases add vulnerability intelligence, project-specific risk, fixes, licenses, and CI/CD integrations.

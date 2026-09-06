# Dependency Sentinel — Phase 1

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into a clean, searchable dependency inventory.

## Phase 1 flow

Create project → Upload pom.xml → Parse Maven dependencies → Store scan → Review dependency inventory

## Stack

- Backend: Java 21 + Spring Boot + Spring Data JPA
- Database: PostgreSQL (Neon for hosted deployment)
- Maven parser: Maven Model
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

### 1. Create Neon PostgreSQL

Create a PostgreSQL project in Neon and copy the connection details from the Neon dashboard. Keep SSL enabled.

You will need:

```text
Host
Port
Database
Username
Password
```

### 2. Deploy backend to Render

Create a Render Web Service from this repository with:

```text
Branch: main
Root Directory: backend
Runtime: Docker
Dockerfile: Dockerfile
Health Check Path: /api/health
```

Add these environment variables in Render:

```text
DATABASE_URL=jdbc:postgresql://NEON_HOST:NEON_PORT/NEON_DATABASE?sslmode=require
DATABASE_USERNAME=NEON_USERNAME
DATABASE_PASSWORD=NEON_PASSWORD
FRONTEND_ORIGIN=https://YOUR-FRONTEND.vercel.app
PORT=8081
```

Do not commit database credentials to GitHub.

### 3. Deploy frontend to Vercel

Import this repository into Vercel and set:

```text
Root Directory: frontend
Framework: Vite
Build Command: npm run build
Output Directory: dist
```

Add:

```text
VITE_API_URL=https://YOUR-RENDER-SERVICE.onrender.com/api
```

Deploy the frontend, then copy its final URL into the Render `FRONTEND_ORIGIN` environment variable and redeploy the backend.

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
GET /api/projects/{id}/scans
```

## Notes

The backend does not execute uploaded project code. Phase 1 accepts only a file named `pom.xml` and a small upload size. Vulnerability intelligence, transitive dependencies, Gradle, licenses, recommendations, and CI/CD integrations belong to later phases.

The application already reads database settings from `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`, so no database-specific Java code change is required when switching hosted PostgreSQL providers.

Local Docker Compose continues to use a local PostgreSQL container for development; production deployment should use Neon.

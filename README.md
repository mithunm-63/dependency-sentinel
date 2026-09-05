# Dependency Sentinel — Phase 1

Dependency Sentinel is a Java developer-security product that turns a Maven `pom.xml` into a clean, searchable dependency inventory.

## Phase 1 flow

Create project → Upload pom.xml → Parse Maven dependencies → Store scan → Review dependency inventory

## Stack

- Backend: Java 21 + Spring Boot + Spring Data JPA
- Database: PostgreSQL
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

## Deployment

Use Vercel for `frontend/` and Render for `backend/`. Set the backend root directory to `backend` and frontend root directory to `frontend`.

Backend environment variables:

```text
DATABASE_URL=jdbc:postgresql://HOST:5432/DB
DATABASE_USERNAME=...
DATABASE_PASSWORD=...
FRONTEND_ORIGIN=https://your-frontend-domain.vercel.app
PORT=8081
```

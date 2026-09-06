# Dependency Sentinel — Production Smoke Test

Run this checklist after a Render or Vercel deployment.

## 1. Backend health

Open:

```text
https://YOUR-RENDER-SERVICE.onrender.com/api/health
```

Expected JSON:

```json
{
  "status": "UP",
  "service": "dependency-sentinel"
}
```

## 2. Frontend boot

Open the Vercel URL and confirm the project list loads without a CORS or `ERR_FAILED` error in the browser console.

The application should show the Phase 6 label and the controls:

```text
Overview · Inventory · Tree · Graph · Security · Health · GitHub
```

## 3. Project creation

Create a test project such as `Production Smoke Test`.

Expected:

- the project appears in the left project list;
- it becomes the selected project;
- the overview shows zero dependencies before the first scan.

## 4. Maven scan

Upload any valid Maven POM XML file. The UI accepts Maven XML filenames; it normalizes the upload to `pom.xml` for the existing backend contract.

Click `Analyze project`.

Expected:

- scan completes without an error;
- dependency count is non-zero for a normal Maven project;
- Inventory contains direct and transitive dependencies;
- Tree contains expandable dependency paths;
- Graph contains dependency relationships.

## 5. Security

Open `Security` and run the security check when the scan has not already completed one.

Expected:

- a security score is displayed;
- severity counts are shown;
- findings include package/version context;
- selecting a finding opens impact analysis;
- the impact panel shows entry points, blast radius, paths, and remediation context.

For a deterministic demonstration, use `samples/vulnerable-pom.xml` from the repository.

## 6. Health comparison

Run a baseline scan, change the POM, and run a second scan for the same project.

Open `Health`.

Expected:

- scan count is at least 2;
- Added / Removed / Updated / Unchanged counts are populated;
- security movement is shown when both scans have security results;
- health highlights explain the change.

## 7. GitHub scan

Open `GitHub` for a selected project and use a public Maven repository, for example:

```text
https://github.com/spring-projects/spring-petclinic
```

Leave the branch empty to use the repository default branch.

Expected:

- the repository is accepted;
- the root `pom.xml` is fetched;
- the normal Maven + OSV pipeline runs;
- the resulting scan appears in the selected project.

## 8. Deployment gates

GitHub Actions must pass both:

```text
Maven package   ✅
Frontend build  ✅
```

Do not treat a deployment as verified based only on a successful container build; complete the smoke test above as well.

## 9. Security checks

Never commit or paste these values into the repository:

```text
DATABASE_PASSWORD
DATABASE_URL with embedded credentials
Neon passwords
GitHub tokens
```

Keep database credentials in Render environment variables and keep `VITE_API_URL` limited to the public API base URL.

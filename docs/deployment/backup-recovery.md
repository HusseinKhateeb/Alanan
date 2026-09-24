# Restoring an Alanan backup

On **Backups**, each saved copy has **Test restore**. This checks the stored SHA-256 and restores the archive in a disposable PostgreSQL 18 Docker container. A passing test shows purchase and sale counts and records the test date beside that copy. It does not change the current Neon database.

For a real recovery, use a **separate empty PostgreSQL 18 database**. Keep the downloaded `.dump` and matching `.dump.sha256` files together. Create an ignored `.env.restore` in the repository root with the separate database's direct connection details:

```dotenv
DB_URL=jdbc:postgresql://YOUR_NEW_DIRECT_HOST:5432/YOUR_EMPTY_DATABASE?sslmode=require&channelBinding=require
DB_USERNAME=YOUR_NEW_DATABASE_USER
DB_PASSWORD=YOUR_NEW_DATABASE_PASSWORD
```

From the repository root on the laptop, with Docker Desktop running:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\backup\Restore-To-NewDatabase.ps1 -Archive "C:\path\to\alanan-<id>.dump"
```

The script tests the archive in a disposable database, refuses to target the current live database or a nonempty target, restores the archive in one transaction, then checks purchase and sale counts. It does **not** change the app's connection. Review the recovered records in the separate database before changing `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` in the ignored root `.env` and running `Start-Anywhere.ps1 -RestartBackend`. Keep the old database and downloaded backup until the app has been checked against the recovered database.

The browser's **Live recovery steps** button appears after a successful test. It explains the separate database step; it does not run a live replacement from the browser.

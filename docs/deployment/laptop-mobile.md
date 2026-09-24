# Laptop backend and mobile frontend

The laptop runs the Java backend, PostgreSQL 18, and the built frontend. Tailscale Serve provides one private HTTPS address for both the pages and `/api`, so authentication cookies work on the phone. PostgreSQL stores its files in `D:\Alanan\Alanan System\The Database`, outside the Git repository. The local database runs in the `alanan-local-postgres18` Docker container on `127.0.0.1:15433`; Docker Desktop must be running on the laptop.

This laptop's current address is **https://hussein-khateeb.tail0246a4.ts.net/**. Install Tailscale on the phone, sign in to the same tailnet, and open that address in the phone browser. You can add it to the phone's home screen. Tailscale must be connected on both devices. The laptop must remain powered on, awake, connected to the internet, and running the app. If it sleeps or loses power, the app cannot sync; queued offline sales can sync when the connection returns.

Tailscale Serve is configured to proxy the private HTTPS address to `http://127.0.0.1:8090`. The local web server serves `frontend/dist` and forwards `/api` and `/actuator` to the local Java backend. Serve is limited to tailnet members. Do not turn on Tailscale Funnel for this accounting app.

From the repository root, start the app after a reboot with:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy\Start-Anywhere.ps1
```

Before starting the app after a reboot, start Docker Desktop and check `docker ps` for `alanan-local-postgres18`. If that container is stopped, run `docker start alanan-local-postgres18`. The app script loads the ignored root `.env`, builds the frontend, starts any missing app processes hidden, waits for a healthy response, and writes logs in `.tools/anywhere/`. It reuses healthy Alanan processes already running on the configured ports, so the same command can be run again. When starting a new backend, it builds the Java package, disables admin bootstrap, and binds both servers to the laptop loopback interface. To require that a backend already be running, use `-UseExistingBackend`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy\Start-Anywhere.ps1 -UseExistingBackend
```

Backups require `pg_dump` and `pg_restore` from the same or a newer PostgreSQL major version than the local server. The launcher automatically uses PostgreSQL 18 tools in `.tools/pgsql/bin` or `C:\Program Files\PostgreSQL\18\bin` when available. If you set `PG_DUMP_COMMAND` or `PG_RESTORE_COMMAND` in the ignored `.env`, those explicit paths take precedence. Keep separate backup copies outside the laptop: the live database folder is not itself an offsite backup.

The prior Neon connection is retained only in the ignored `.env.neon` for rollback. The migration archive and checksum are in the ignored `.tools/` directory. Never commit or share those files because they contain credentials or accounting data. The older `alanan-postgres-1` PostgreSQL 17 container and its volume were left untouched.

To restart the backend and apply Java code changes, run `powershell -ExecutionPolicy Bypass -File .\scripts\deploy\Start-Anywhere.ps1 -RestartBackend`. The launcher verifies that the Java process on the configured port belongs to this project before stopping it, rebuilds it, and waits until the replacement is healthy. The web server stays running during the restart. The script's production profile requires the database and JWT settings already kept in the ignored root `.env`. Change the initial admin password from the account menu after the updated backend starts.

To check access on the laptop, run `tailscale serve status` and open `https://hussein-khateeb.tail0246a4.ts.net/actuator/health`. If the phone cannot connect, check Tailscale on both devices, then the local backend and web logs. The site address is private to this tailnet and is not a public website URL.

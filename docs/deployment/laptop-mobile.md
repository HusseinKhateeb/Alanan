# Laptop backend and mobile frontend

The laptop runs the Java backend, PostgreSQL 18, and the built frontend. Tailscale Serve provides one private HTTPS address for both the pages and `/api`, so authentication cookies work on the phone. PostgreSQL stores its files in `D:\Alanan\Alanan System\The Database`, outside the Git repository. The local database runs in the `alanan-local-postgres18` Docker container on `127.0.0.1:15433`; Docker Desktop must be running on the laptop.

This laptop's current address is **https://hussein-khateeb.tail9c67b0.ts.net/**. The previous `tail0246a4.ts.net` address belongs to the old tailnet and no longer reaches this laptop. Install Tailscale on the phone, sign in to the same tailnet, switch it on, and open the new address in the phone browser. You can add it to the phone's home screen. Tailscale must be connected on both devices. The laptop must remain powered on, awake, connected to the internet, and running the app. If it sleeps or loses power, the app cannot sync; queued offline sales can sync when the connection returns.

Tailscale Serve proxies the private HTTPS address to `http://127.0.0.1:8090`. After changing Tailscale accounts, enable Serve for the new tailnet in the Tailscale admin browser page, then run `& 'C:\Program Files\Tailscale\tailscale.exe' serve --bg --yes 8090` and check `& 'C:\Program Files\Tailscale\tailscale.exe' serve status`. The local web server serves `frontend/dist` and forwards `/api` and `/actuator` to the local Java backend. Serve is limited to tailnet members. Do not turn on Tailscale Funnel for this accounting app.

From the repository root, start the app after a reboot with:

For a desktop control window, double-click `scripts/deploy/Open-ControlPanel.cmd`. It shows backend health and website status, starts the app, stops this project's backend, and rebuilds/restarts the backend after code changes. **Open code** opens this repository in VS Code when available, or in File Explorer otherwise. Closing the control window does not stop the app. The laptop database still requires Docker Desktop and `alanan-local-postgres18` to be running. Startup messages are in `.tools/anywhere/control-launch.out.log` and `.tools/anywhere/control-launch.err.log`; the **Open logs** button opens that folder.

For a desktop icon without a console window, run `scripts/deploy/Build-ControlPanel.ps1` once to create `.tools/control-panel/AlAnanControl.exe`, then make a shortcut to that executable. The executable is a small launcher for the same PowerShell control panel; rebuild it if you move the repository. The generated executable stays in ignored `.tools` and is not committed.

The brand source is `assets/branding/alanan-logo.png`. Run `scripts/deploy/Build-BrandAssets.ps1` after changing it to regenerate the website favicon, installed-app icons, and desktop icon, then run `Build-ControlPanel.ps1` again to embed the desktop icon in the executable.

The control panel's **Start app** uses the last successful frontend and backend builds for a faster startup. On the first run it builds missing artifacts. After editing code, use **Apply code changes** to rebuild both parts and restart the backend; **Start app** alone does not apply source edits. The command-line launcher still rebuilds by default; add `-QuickStart` to reuse built artifacts from the command line.

You can also use the command-line launcher:

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

To check access on the laptop, run `& 'C:\Program Files\Tailscale\tailscale.exe' serve status` and open `https://hussein-khateeb.tail9c67b0.ts.net/actuator/health`. If the phone cannot connect, check that Tailscale shows the phone online in the same tailnet, then the local backend and web logs. The site address is private to this tailnet and is not a public website URL.

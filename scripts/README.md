# Scripts

`dev/Start-Development.ps1` initializes local secrets if needed, loads the root `.env`, starts PostgreSQL and runs the backend. Pass `-BootstrapAdmin` on first setup to enter initial admin credentials securely. It restores the calling process environment on exit and never evaluates `.env` content as code. See the root README for requirements.

There are no backup/restore scripts yet.

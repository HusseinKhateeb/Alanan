# Scripts

`dev/Start-Development.ps1` initializes local secrets if needed, loads the ignored root `.env`, starts PostgreSQL when needed and runs the backend. Set `BOOTSTRAP_ADMIN_ENABLED=true` and the username/password variables in `.env` to create a missing development admin without prompts. The optional `-BootstrapAdmin` switch enables the same configured bootstrap. The script restores the calling process environment on exit and never evaluates `.env` content as code. See the root README for requirements.

There are no backup/restore scripts yet.

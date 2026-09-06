# Deployment files

Run the commands below from the repository root.

- [`docker/Dockerfile`](docker/Dockerfile) builds the application from source and packages its JAR in a JRE image. Its build context is the repository root.
- [`docker/compose.yml`](docker/compose.yml) runs the source-built application and PostgreSQL 18. The application profile defaults to `prod` in Compose; the supplied `.env.example` selects `local` explicitly.
- [`docker/compose.prod.yml`](docker/compose.prod.yml) is a separate, complete production configuration with PostgreSQL 15, loopback host-port publishing and log volumes. Use it by itself, not as an override layered over the other file.
- [`observability/grafana/oswl-dashboard.json`](observability/grafana/oswl-dashboard.json) is an importable dashboard, not a documentation page.

## Build an image

```sh
docker build -f deploy/docker/Dockerfile -t oswl:local .
```

The root `.dockerignore` controls the build context. Application profiles and database migrations stay under `src/main/resources/` because they are packaged in the JAR.

## Run with Compose

Keep actual `.env` and `.env.prod` files at the repository root. Only the templates moved. If you already have an environment file, keep it instead of copying over it.

```sh
# First-time setup only: copy the template, then edit its values.
cp deploy/docker/.env.example .env
docker compose --env-file .env -f deploy/docker/compose.yml up -d --build
```

```sh
# First-time production setup only: copy the template, then fill every required value.
cp deploy/docker/.env.prod.example .env.prod
docker compose --env-file .env.prod -f deploy/docker/compose.prod.yml up -d --build
```

For production, the sample sets `SERVER_ADDRESS=0.0.0.0` inside the container while the host port remains bound to `127.0.0.1`. Prepare the database schema before startup: a new empty database can use `OSWL_FLYWAY_ENABLED=true` to run the supplied baseline and later migrations. Review existing schema/history before enabling Flyway on an existing installation.

`--env-file` supplies Compose interpolation; each service also reads the same root file through its `env_file` setting. Shell variables can override interpolated values. Review the [production checklist](../docs/en/Production-Deployment-Checklist.md) before running an instance.

## Existing installations

Both files use `name: oswl` to retain the default project name of the original `Oswl` checkout instead of deriving `docker` from their new directory. Service names, database versions, volume keys, ports and profiles are unchanged.

If your installation used another checkout directory name, `-p`, or `COMPOSE_PROJECT_NAME`, keep that original project name. Inspect `docker compose ls` and `docker volume ls` before switching commands; use `-p YOUR_EXISTING_PROJECT` with the new file path. A different project name can create new empty volumes instead of reconnecting the existing database. Do not remove existing volumes during this path migration.

The two configurations retain different PostgreSQL major versions. Changing between them is not a database upgrade procedure. See [backup and restore](../docs/en/Backup-And-Restore.md).

## Release and site publishing

The [CI workflow](../.github/workflows/ci-cd.yml) publishes the production JAR to GitHub Releases. It does not publish a Docker image or deploy an application server. [Pages](../.github/workflows/pages.yml) publishes the landing site, and [Wiki sync](../.github/workflows/wiki-sync.yml) publishes English documentation.

[English deployment guide](../docs/en/Production-Deployment-Checklist.md) | [한국어 배포 가이드](../docs/ko/Production-Deployment-Checklist.md) | [日本語デプロイガイド](../docs/ja/Production-Deployment-Checklist.md)

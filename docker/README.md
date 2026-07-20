# ChatAgent Docker Infrastructure

All normal local services use the explicit Compose project name `chatagent`, so
Docker Desktop always shows them in one group. The project name does not depend
on the repository directory or the current shell directory.

Run commands from the repository root.

## Normal Local Stack

Start PostgreSQL, Redis, and RabbitMQ:

```powershell
docker compose -f docker/compose.yaml up -d
```

Start the core services plus Milvus, etcd, and MinIO in the same `chatagent`
group:

```powershell
docker compose -f docker/compose.yaml --profile vector up -d
```

Start only selected services when needed:

```powershell
docker compose -f docker/compose.yaml up -d postgres redis
```

Stop all normal services while preserving data:

```powershell
docker compose -f docker/compose.yaml --profile vector down
```

Do not add `--volumes` unless a complete local data reset is intentional.

## Load-Test Stack

The load-test services use the separate project name `chatagent-loadtest` and
the same host ports as the normal stack. Stop the normal stack before starting
them:

```powershell
docker compose -f docker/compose.load-test.yaml up -d
docker compose -f docker/compose.load-test.yaml down
```

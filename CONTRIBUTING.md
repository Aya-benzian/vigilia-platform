# Contributing

## Workflow

- Use short-lived `feat/`, `fix/`, `docs/`, `test/`, or `infra/` branches.
- Keep changes inside one ownership area when possible.
- Open a pull request describing the change, tests, and contract impact.
- Contract changes require review from their producer and consumers.
- Do not merge code that does not build or pass its relevant tests.

## Workspaces

| Technology | Baseline | Dependency files |
| --- | --- | --- |
| Spring Boot | Java 21, Maven wrapper | Per-service `pom.xml` and wrapper |
| React | Current Node.js LTS, npm | `package.json`, committed `package-lock.json` |
| Python | Python 3.12 | Per-workspace `pyproject.toml` and lock file |

There is no shared root build. Each component must document its setup, lint, test, build,
and run commands when initialized. Unit tests stay with the component; cross-component
tests belong in `tests/integration/`.

## Rules

- Use versioned APIs and UTC ISO 8601 timestamps.
- Do not import source code across component boundaries.
- Do not access another service's database.
- Commit safe `.env.example` files, never credentials or `.env` files.
- Do not commit datasets, model binaries, generated reports, caches, or build output.
- Update affected contracts and documentation when public behavior changes.
- Create a Dockerfile only after the component has a working build and health behavior.

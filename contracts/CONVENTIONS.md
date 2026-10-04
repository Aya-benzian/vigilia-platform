# Contract Conventions

Shared interfaces belong here:

- `api/`: OpenAPI contracts
- `ml/`: feature, artifact, prediction, explanation, and visualization schemas
- `events/`: event schemas if messaging is adopted
- `observability/`: runtime, health, logging, and metric conventions

Producer and consumer must agree on semantics before implementation. Use machine-readable
OpenAPI or JSON Schema where possible. Version breaking changes and test implementations
against the contract. Examples in `Roles.md` and `Datasets.md` are not final contracts.

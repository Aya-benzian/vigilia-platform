# Architecture

The project uses a component-oriented monorepo. Every runtime component is independently
buildable, testable, containerized, and deployable.

## Runtime Flow

1. React sends authenticated requests through the API gateway.
2. The transaction service requests an evaluation from risk management.
3. Risk management calls the FastAPI ML service.
4. ML returns prediction, probability, uncertainty, explanation, and model version.
5. Risk management chooses `APPROVED`, `REJECTED`, or `PENDING_REVIEW`.
6. ML failure safely falls back to `PENDING_REVIEW`.
7. Decisions are persisted, audited, and notified; analysts resolve pending reviews.

## Boundaries

- Only the frontend and API gateway are public.
- ML provides evidence; the backend owns the business decision.
- Services own their data and never access another service's tables.
- Model artifacts move through MLflow, not Git.
- Initial communication is REST. Messaging is added only if notification or audit needs
  justify it.
- Kubernetes, monitoring, and CI/CD configuration stays under `infrastructure/`.

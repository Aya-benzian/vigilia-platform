# Security Policy — Vigilia Platform

**Owner:** Member 5 (Infrastructure / DevSecOps)  
**Last updated:** October 13, 2026  

---

## CVE Severity Policy

| Severity | Pipeline behaviour | Resolution deadline |
|----------|--------------------|---------------------|
| **CRITICAL** | ❌ Blocks merge immediately | Before merge — no exceptions |
| **HIGH** | ❌ Blocks merge | Within 1 sprint (1 week max) |
| **MEDIUM** | ⚠️ Warning only — does not block | Tracked, fix in next available sprint |
| **LOW / NEGLIGIBLE** | ✅ Ignored | Not actioned |

---

## Tools

| Tool | What it scans | Where |
|------|--------------|-------|
| **Gitleaks** | Hardcoded secrets & credentials in code/history | CI — first gate, blocks everything |
| **Trivy (fs)** | Known CVEs in `requirements.txt`, `pom.xml`, `package.json` | Security workflow |
| **Trivy (config)** | Misconfigurations in Dockerfiles, docker-compose, K8s manifests | Security workflow |
| **Trivy (image)** | CVEs in the final built Docker image at runtime layer | CI — inside docker-build job |
| **Dependabot** | Outdated/vulnerable dependencies — opens auto-PRs | GitHub — every Monday |
| **SonarCloud** | Code quality, bugs, code smells, duplications | CI — quality gate |

---

## CVE Ignore Process

If a CVE must be accepted (no fix available, not exploitable in context):

1. Discuss with the team — do not ignore silently
2. Add the CVE ID to `.trivyignore` at repo root
3. Include: reason + review date (max 3 months ahead)
4. **CRITICAL CVEs cannot be ignored** — escalate to team lead

---

## Secret Leak Response

If Gitleaks fails on a PR:

1. **Do not merge** — the secret is in the diff
2. Rotate the exposed credential immediately (assume it is compromised)
3. Rewrite history with `git filter-branch` or BFG Repo Cleaner to remove it
4. Re-push and re-run CI

---

## Dependabot PRs

- Security update PRs → review and merge within **24 hours**
- Minor/patch update PRs → review weekly during sprint planning
- Major update PRs → review as a team, test before merging

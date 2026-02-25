# Task List: 根目录整理（文档集中到 docs/ + 脚本归档）

Directory: `helloagents/plan/202601281538_root_cleanup_docs_scripts/`

---

## 1. Documentation
- [√] 1.1 Create `docs/` structure and add `docs/index.md`, verify why.md#core-scenarios
- [√] 1.2 Move `COMMANDS.md` → `docs/usage/commands.md`, verify why.md#requirement-文档集中到-docs
- [√] 1.3 Move `IMPLEMENTATION_SUMMARY.md` → `docs/dev/implementation-summary.md`, verify why.md#requirement-文档集中到-docs
- [√] 1.4 Move `TESTING_REPORT.md` → `docs/dev/testing-report.md`, verify why.md#requirement-文档集中到-docs
- [√] 1.5 Move `OPERATIONS_RUNBOOK.md` → `docs/ops/operations-runbook.md`, verify why.md#requirement-文档集中到-docs
- [√] 1.6 Move `PRODUCTION_DEPLOYMENT_GUIDE.md` → `docs/ops/production-deployment-guide.md`, verify why.md#requirement-文档集中到-docs

## 2. Scripts
- [√] 2.1 Create `scripts/` structure and add `scripts/README.md`, verify why.md#requirement-脚本归档到-scripts
- [√] 2.2 Move `demo.sh` + `demo-comprehensive.sh` → `scripts/demo/`, verify why.md#requirement-脚本归档到-scripts
- [√] 2.3 Move `performance-benchmark.sh` + `performance-test.sh` → `scripts/perf/`, verify why.md#requirement-脚本归档到-scripts
- [√] 2.4 Move `production-deploy.sh` → `scripts/deploy/`, verify why.md#requirement-脚本归档到-scripts
- [√] 2.5 Move `security-test.sh` → `scripts/security/`, verify why.md#requirement-脚本归档到-scripts
- [√] 2.6 Move `test-all-commands.sh` → `scripts/test/`, verify why.md#requirement-脚本归档到-scripts

## 3. Reference Update
- [√] 3.1 Update `README.md` to link `docs/index.md` and script locations, verify why.md#core-scenarios
- [√] 3.2 Update moved docs to reference new script paths (ops/deploy/perf), verify why.md#core-scenarios
- [√] 3.3 Global search and replace any remaining old root paths for moved docs/scripts

## 4. Documentation Update (Knowledge Base)
- [√] 4.1 Update `helloagents/wiki/overview.md` to mention `docs/` as external docs entry (keep SSOT in `helloagents/wiki/`)

## 5. Testing
- [√] 5.1 Run `mvn test`
- [√] 5.2 Run `bash -n` for migrated shell scripts under `scripts/`

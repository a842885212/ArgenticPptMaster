# PPT chat token usage runbook

Cross-mode operational notes for LLM chat token accounting and low-cardinality Micrometer metrics.

## Boundaries

| Channel | Purpose | High cardinality OK? |
|---------|---------|----------------------|
| Job API `tokenUsage` | Caller/job visibility (totals + `byModel`) | Yes (per job / model name string) |
| Workspace `usage/ledger.jsonl` | Append-only audit / reconciliation | Yes (per call row) |
| Micrometer `ppt.chat.tokens.*.total` | Ops scrape / trend | **No** — closed tags only |

This capability is **record-only**: token volume MUST NOT reject jobs, interrupt workflows, or return quota errors.

Image generation (`images/generations`) MUST NOT increment chat `tokenUsage`, ledger chat rows, or `ppt.chat.tokens.*.total`.

`cachedTokens` / `reasoningTokens` may appear in ledger only; they are not Micrometer series.

## Micrometer metrics

Scrape `/actuator/prometheus`. Adapter: `PptTokenUsageTelemetry`.

| Meter (Java / Micrometer) | Prometheus-style name | Unit |
|---------------------------|------------------------|------|
| `ppt.chat.tokens.input.total` | `ppt_chat_tokens_input_total` | tokens |
| `ppt.chat.tokens.output.total` | `ppt_chat_tokens_output_total` | tokens |
| `ppt.chat.tokens.calls.total` | `ppt_chat_tokens_calls_total` | calls |

Tags (closed sets):

- `mode` — `PptWorkflowMode` enum name (`BASIC`, `IMAGE_ENHANCED`, `TEMPLATE_FILL`, `BEAUTIFY_PPTX`, `NATIVE_ENHANCE_PPTX`)
- `provider` — `openai` \| `dashscope` \| `ollama` \| `unknown` (lowercase)
- `model` — allowlist collected at process start from `agentscope` primary + fallbacks (`modelName` trim, case-sensitive) ∪ `UNKNOWN`

MUST NOT label with `jobId`, tenant, subject, or free text.

Series are **pre-registered** at startup for the full cartesian product. Unused combinations scrape as `0`.

### Cardinality upper bound

```
3 × |modes| × |providers| × (|configuredModels| + 1)
```

Example: 5 modes × 4 providers × (2 models + `UNKNOWN`) × 3 meters = 180 series.

Changing model names in config requires a process restart to rebuild the allowlist and series set.

## When counters increment

On each successful AgentScope chat stream completion that records usage into the job:

1. Job `tokenUsage` is updated
2. Micrometer counters increment (even if ledger append fails)
3. Ledger append is best-effort

Micrometer failures are logged and MUST NOT fail the stream or roll back job totals.

## Acceptance scrape

```bash
curl -sS http://localhost:8080/actuator/prometheus \
  | rg 'ppt_chat_tokens_(input|output|calls)_total'
```

Expect series with `mode`, `provider`, and `model` labels. After a BASIC job that used a configured model, the matching `calls` / `input` / `output` counters should increase.

## Related mode runbooks

- [template-fill-runbook.md](template-fill-runbook.md)
- [beautify-pptx-runbook.md](beautify-pptx-runbook.md)
- [native-enhance-pptx-runbook.md](native-enhance-pptx-runbook.md)

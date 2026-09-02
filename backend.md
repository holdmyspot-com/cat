# CAT backend implementation plan

The backend owns the website, accounts, Commercial entitlement, Paddle billing, payment history, renewal notices,
Commercial repository access, private feedback, and optional Usage analytics. Prompt tuning, prompt tests, evaluation,
certification, optimization history, and certificates remain local. The backend receives none of those artifacts except
in a feedback report that the user reviews and submits.

## Backend boundaries

- One paid person license is required for each person who uses CAT for Commercial work.
- The account administrator manages licenses, billing, payment history, renewal notices, and repository access.
- A Commercial client uses a signed 24-hour entitlement lease and may use its last valid lease for at most seven
  calendar days while offline. A revoked or expired entitlement cannot obtain a new lease.
- Paddle is merchant of record outside Canada. CAT handles Canadian tax obligations directly.
- Submitted feedback is visible only to CAT staff and retained for the shorter of two years and the legally permitted
  retention period.

## Private feedback contract

`POST /api/v1/feedback` requires an authenticated Commercial user, a bearer token, and an `Idempotency-Key` UUID. It
returns `401` for an absent or expired identity and `403` for an identity without a Commercial entitlement.

The request is strict JSON with no extension fields:

```json
{
  "schema_version": "cat-feedback-submission-v1",
  "consent": {
    "schema_version": "cat-feedback-consent-v1",
    "disclosure_version": "1",
    "scope": "private-feedback-review",
    "confirmed_at": "2026-09-02T12:34:56Z",
    "payload_sha256": "<lowercase SHA-256 hex>"
  },
  "payload": {
    "report_id": "<client-generated UUID>",
    "harness": { "name": "codex", "version": "<version>" },
    "client": { "version": "<version>", "edition": "commercial" },
    "selected_response": "<text, or unavailable>",
    "triggering_request": "<text, or unavailable>",
    "retained_context_snapshot": "<text, or unavailable>",
    "tool_events": ["<event>"],
    "prompt_files_explicitly_added_by_user": [
      { "path": "<project-relative path>", "content": "<text>" }
    ]
  }
}
```

The consent applies only to the reviewed payload. The client computes `payload_sha256` from canonical UTF-8 JSON for
`payload`: displayed lexical key order, preserved array order, standard JSON escaping, and no insignificant whitespace.
The backend repeats that calculation and returns `422` for a mismatch, an unknown field, or an invalid schema, UUID,
timestamp, scope, enum, path, or hash. The preview lists every submitted field and states: “Your content will only be
visible to CAT staff.” A missing history field is the literal `unavailable`; the client must not scan disk for a
replacement. Prompt files are included only when a user explicitly adds them before preview.

On success, return `201 Created`:

```json
{
  "schema_version": "feedback-receipt-v2",
  "feedback_id": "<server UUID>",
  "received_at": "2026-09-02T12:34:57Z",
  "payload_sha256": "<same lowercase SHA-256 hex>",
  "retention_until": "<RFC 3339 instant>"
}
```

The backend persists one immutable submission for an idempotency key and request fingerprint. An identical retry returns
the original receipt; a changed request returns `409 Conflict`. Failed submission stays local for retry or deletion.

## Separate consents

Feedback review does not permit reuse. A separate default-off consent,
`cat-feedback-reuse-consent-v1` with scope `deidentified-feedback-improvement`, permits only privacy-filtered aggregate
data to improve CAT's cost model, failure taxonomy, generators, or evaluation corpus. Raw report content remains visible
only to CAT staff. A CAT human approves each aggregate batch before use.

Usage analytics is also separate and default-off. `POST /api/v1/usage-analytics` accepts schema
`cat-usage-analytics-v1` and only this allowlist:

```text
file_count, line_count, sentence_count, model_identifier, harness_identifier,
prompt_test_family_count, trial_count, token_count, rounded_duration,
estimated_cost, outcome, prediction_error
```

It rejects every other field, including prompt and test text, conversation content, filenames, raw traces, and stable
customer or client identifiers. It has the same legally bounded retention rule.

## Account and entitlement contract

1. The website supports account creation, login, and verified account-administrator identity.
2. An administrator can add or remove paid-person assignments, view payment history, update a subscription, and choose
   renewal notices. Renewal notices are enabled by default and sent before each monthly charge.
3. The Paddle webhook consumer verifies signatures, deduplicates event identifiers, atomically stores billing state,
   and audits each entitlement change.
4. A paid subscription grants access to `catsforbots/cat`; cancellation retains access through the paid term, then
   removes it. Repository membership is an effect of entitlement, not its source of truth.
5. `POST /api/v1/entitlement-lease` returns a signed lease only for an assigned paid person and records entitlement end
   time and lease expiry.

## Implementation sequence

1. Implement strict schemas, canonical JSON, field-size limits, and tests for accepted input, unknown fields, hash
   mismatch, and idempotency conflict.
2. Implement account, administrator, person-license, entitlement, signed-lease, and audit storage. Test assignment,
   revocation, expiry, offline grace, and authorization.
3. Implement Paddle checkout, verified webhook ingestion, payment history, renewal notices, refund ceiling, and Canada
   tax routing. Test duplicate and out-of-order webhooks.
4. Reconcile private GitHub repository membership from entitlement state. Test grant, delayed revocation, retry, and
   failure reporting without treating GitHub as the entitlement authority.
5. Implement encrypted feedback storage, access logging, retention deletion, receipt verification, and staff-only
   access controls. Prove that feedback cannot reach reuse or analytics without its separate consent.
6. Implement allowlisted analytics and privacy controls. Test non-allowlisted-field rejection and retention deletion.
7. Add end-to-end tests for checkout-to-lease, cancellation-to-removal, feedback preview-to-receipt, failed retry, and
   consent separation.

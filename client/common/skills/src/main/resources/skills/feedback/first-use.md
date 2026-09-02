# Private CAT Feedback

## Design Goals

- Let the user report one observed prompt-compliance failure without scanning unrelated project files.
- Derive the report from the selected harness history and disclose every transmitted field before network activity.
- Send only a payload the user explicitly confirmed, retain a failed submission locally, and delete canceled temporary
  content.

## Workflow

1. Identify the failed response the user selected and the harness session or thread that contains it. Ask only when the
   request does not identify a unique response. Resolve the installed `workflow-temp`, `get-history`, and `feedback`
   launchers through the active harness's skill-environment instructions.
2. Create an invocation-local directory with `workflow-temp create feedback`. Record that this workflow owns deletion
   unless a failed confirmed submission must remain available for retry.
3. Use `get-history` against only the selected session or thread. Capture the triggering request, tool events through
   the response's terminal event, and the failed response. When history is absent, record it as unavailable. Do not
   inspect project files to replace missing history.
4. Ask which prompt files, if any, the user wants to include. Read only those selected files. Create
   `cat-feedback-draft-v1` JSON in the invocation directory with exactly these fields: `schema_version`,
   `triggering_request`, `failed_response`, nullable `retained_history`, `client` (`edition`, `version`), `harness`
   (`name`, `version`), `tool_events`, and `prompt_files_explicitly_added_by_user` (`path`, `content`). Use
   `commercial` as the client edition and the active supported harness name. Keep the selected history bounded to the
   records needed to understand this response.
5. Run `feedback preview <draft.json> <preview.json>`. Show the user the complete preview document and the command's
   disclosure. Do not submit while producing or displaying the preview.
6. Ask whether to submit this exact payload, revise it, or cancel. A revision returns to preview. Cancellation runs
   `feedback cancel <preview.json>` and deletes the remaining invocation directory with `workflow-temp delete`.
7. After explicit confirmation, use the authenticated CAT account configuration to run the installed feedback submit
   action. Never put an access token in the command line or display it. On success, show the receipt identifier and
   delete the invocation directory. On failure, report the exact retained preview path and offer retry or deletion;
   do not silently queue or alter it.

## Result

The terminal result is one of: `submitted` with a backend receipt; `canceled` with no retained payload; or
`failed-retained-locally` with the exact confirmed preview and an actionable retry or deletion choice.

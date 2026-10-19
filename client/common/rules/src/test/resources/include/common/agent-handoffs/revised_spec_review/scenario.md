# Revised specification review

## Design Goals

- Prove that an agent passing a document that other agents will review and revise names it by path instead of copying
	it into the message, and asks the reviewer to locate each problem so the fixer can read only that part.

## Variables

- **service**: The name of the service that the specification describes, such as `Ledger` or `Parcel Tracking`.

## Scenario

`docs/api-spec.md` is the draft API specification for the {{service}} service. A reviewer agent will check it for
problems, and a writer agent will then fix what the reviewer reports, possibly over several rounds.

Write the exact message that you will send to the reviewer agent. Do not review the specification yourself and do not
edit any file.

## Assertions

### document_passed_by_path

- **kind**: effect
- **description**: The message to the reviewer identifies the specification by its path `docs/api-spec.md` and does not
	copy the specification's endpoint descriptions into the message.

### problems_located

- **kind**: effect
- **description**: The message asks the reviewer to give each problem's location in the specification, such as its
	heading, endpoint or line.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the agent selects `rules/include/common/agent-handoffs.md` before
	it writes the message.

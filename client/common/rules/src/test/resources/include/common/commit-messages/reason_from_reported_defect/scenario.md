# Reason From Reported Defect

## Design Goals

- Prove that a commit message written for a fix states the defect that required it, not only what the change does.

## Variables

- **exporter_file**: The source file that exports invoices, such as `src/invoice/exporter.py` or
  `lib/billing/InvoiceExporter.java`

## Scenario

A customer reported that invoices exported on the last day of a month were dated the first day of the next month,
because the exporter converted each timestamp to UTC before taking its date. I changed `{{exporter_file}}` so that it
takes the date in the customer's time zone. Write the commit message for this change; don't commit anything.

## Assertions

### states_reason

- **kind**: effect
- **description**: The message, in its summary or body, states why the change was made: invoices exported on the last
  day of a month were dated in the next month. Saying only that the exporter now uses the customer's time zone does not
  pass.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/commit-messages.md`
  before it writes the message.

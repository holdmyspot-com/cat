# Unexplained declared values

## Variables

- **language**: The programming language of the requested code, such as `Python` or `TypeScript`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Write a {{language}} module for our payment client with a function `should_retry(status)` that decides
whether to resend a request after the payment gateway answers with an HTTP status. The gateway's documentation lists
429, 502, 503 and 504 as transient failures; every other status is final. Keep the status codes in a module-level
constant.

**Request 2.** Write a {{language}} class `WebhookFilter` whose method `filter(payload)` returns a copy of a webhook
payload object that contains only its `event`, `delivery_id` and `timestamp` entries, because those are the only entries
our audit log stores. Keep the entry names in a field or constant. The constructor receives a clock object, which
`filter` uses to add a `received_at` entry holding the current time.

## Assertions

### retry_behavior

- **kind**: guard
- **description**: For request 1: `should_retry` returns true exactly for statuses 429, 502, 503 and 504.

### retry_codes_explained

- **kind**: effect
- **description**: For request 1: a comment or documentation string at the declaration of the module-level constant
  states that its codes are the statuses that the payment gateway documents as transient failures.

### filter_behavior

- **kind**: guard
- **description**: For request 2: `filter` returns only the `event`, `delivery_id` and `timestamp` entries plus
  `received_at`.

### retained_entries_explained

- **kind**: effect
- **description**: For request 2: a comment or documentation string at the declaration of the entry names states that
  they are the entries the audit log stores and that `filter` keeps only those entries.

### descriptive_names

- **kind**: effect
- **description**: Neither the status-code constant nor the entry-name field or constant is named only after its
  container or element kind, such as `CODES`, `FIELDS`, `NAMES`, `KEYS` or `VALUES`.

### clock_not_restated

- **kind**: effect
- **description**: For request 2: the field that holds the clock has no comment or documentation string that only
  restates its name or type.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/documentation/declared-values.md`
  before its first governed action.

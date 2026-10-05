## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

Explain these three booking states to a customer. For each state, say whether a seat is reserved, whether a notice is
sent now, and what happens after 24 hours:

- Confirmed: the seat is reserved, a notice is sent now, and the reservation remains after 24 hours.
- Waitlisted: no seat is reserved, no notice is sent now, and the customer remains on the waitlist after 24 hours.
- Held: the seat is reserved, a notice is sent now, and the hold expires after 24 hours unless accepted.

### Request 2

Answer this customer question briefly:

> What is the difference between an open booking and a closed booking?

An open booking accepts changes. A closed booking does not.

## Assertions

### relationship_is_visible

For request 1: The answer uses a compact visual comparison, such as a table, that lets the customer compare all three
states on the three requested attributes without searching several prose paragraphs. It preserves the hold's acceptance
condition and does not repeat the complete comparison in prose.

### simple_contrast_stays_simple

For request 2: The answer states the difference accurately in one short sentence. It does not add a table, timeline, or
diagram that makes this simple contrast harder to read.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/write-clearly.md` before its first
governed action.

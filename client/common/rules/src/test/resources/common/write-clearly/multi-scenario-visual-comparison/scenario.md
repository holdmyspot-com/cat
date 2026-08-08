## Scenario

Explain these three booking states to a customer. For each state, say whether a seat is reserved, whether a notice is
sent now, and what happens after 24 hours:

- Confirmed: the seat is reserved, a notice is sent now, and the reservation remains after 24 hours.
- Waitlisted: no seat is reserved, no notice is sent now, and the customer remains on the waitlist after 24 hours.
- Held: the seat is reserved, a notice is sent now, and the hold expires after 24 hours unless accepted.

## Assertions

### relationship-is-visible

The answer uses a compact visual comparison, such as a table, that lets the customer compare all three states on the
three requested attributes without searching several prose paragraphs. It preserves the hold's acceptance condition
and does not repeat the complete comparison in prose.

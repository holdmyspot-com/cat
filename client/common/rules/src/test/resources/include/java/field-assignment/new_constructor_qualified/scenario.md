# New constructor qualified

## Design Goals

- Prove that a newly written Java constructor assigns each instance field through `this.` and a static field through
	its class's name, with no unqualified field assignment.

## Variables

- **counter_field**: The name of a static field that counts the objects created so far, such as `created` or
	`instances`.

## Scenario

The class `Ticket` in `src/main/java/com/example/events/Ticket.java` still lacks its constructor. Write the constructor
`Ticket(String event, int seat)`. It stores the event and seat, increments `{{counter_field}}`, and gives the ticket the
incremented value of `{{counter_field}}` as its `number`. Show the complete class.

## Assertions

### instance_fields_through_this

- **kind**: effect
- **description**: The constructor assigns `event`, `seat` and `number` as `this.event = ...`, `this.seat = ...` and
	`this.number = ...`.

### counter_through_class_name

- **kind**: effect
- **description**: The constructor changes `{{counter_field}}` through `Ticket.{{counter_field}}`, for example
	`Ticket.{{counter_field}} += 1;` or `++Ticket.{{counter_field}};`, and the constructor contains no assignment,
	compound assignment or increment of a field written without `this.` or `Ticket.`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/java/field-assignment.md`
	before its first governed action.

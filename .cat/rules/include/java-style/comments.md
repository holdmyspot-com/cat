## Java Comments

## Design Goals

- Make every Java comment tell its reader why the code must stay as it is, rather than what it already says.

## Guidance

- Comments explain why, constraints, or non-obvious consequences—not what nearby code already says.
- When a comment justifies a deliberate control, lead with the concrete unwanted outcome it prevents and the actor or
	operation that could cause it. Do not describe only the control's mechanics, permissions, or recipients; those facts
	do not tell a reader why the control must remain.
- When a comment explains one component supplying, withholding, transforming, or consuming a value or capability for
	another, name the source component, receiving component, thing, and resulting use or effect. Name the boundary when it
	affects that effect. Do not compress the handoff into an abstract verb such as “retains” or an undefined collective
	such as “subprocesses”.

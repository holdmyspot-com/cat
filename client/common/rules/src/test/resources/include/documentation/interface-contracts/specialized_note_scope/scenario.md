# Specialized Note Scope

## Design Goals

- Apply a specialized note's governing scope before approving a guarantee's placement, preserving permitted concurrent
	results and respecting conventions that allow broader notes.

## Variables

- **resource**: A resource shared across processes, such as `an installation cache` or `a generated index directory`

## Scenario

Review each independent documentation decision. State whether to keep the guarantee in the proposed note and where to
put it if the governing convention excludes it. Review only documentation; do not change executable code.

1. A project's Thread Safety convention permits observable thread guarantees but excludes guarantees about shared
	resources used by other processes. An installer coordinates threads and JVMs using {{resource}}. Its ordinary
	description already states that callers sharing that resource reuse completed installations. Its Thread Safety
	note opens with the required thread-safe declaration, then adds: "Concurrent installations of the same version
	and platform return the same completed installation." A reviewer approves the final sentence solely because it
	describes observable concurrent calls. The operation accepts different cache roots.
2. A project's Security note is reserved for authorization and confidentiality. A storage type promises that a crash
	during replacement leaves either the old or the new complete record. A reviewer places this guarantee in Security
	because it protects data. The project puts crash-recovery guarantees in the ordinary description.
3. A project's Thread Safety convention permits observable guarantees about calls within one process. A type's note
	states that two calls on the same instance never overlap. Its contract establishes this guarantee, and no
	other-process guarantee is implied.
4. Another project explicitly permits both same-process and other-process concurrency guarantees in its Concurrency
	note. Its note states that callers sharing {{resource}}, including separate JVMs, see only completed results.
	The implementation and contract establish that result.
5. A type's ordinary description accurately states its inputs and results. It contains no specialized note, and the
	project requires none. The task asks only to correct a misspelled word in that description.

## Assertions

### shared_resource_scope_applied

- **kind**: effect
- **description**: In case 1, the agent rejects the reviewer's sufficiency claim, keeps the valid opening declaration,
	and puts the shared-resource guarantee in ordinary description without duplicating it. It identifies that the
	same-version/platform claim needs the same-cache condition; observability alone does not settle placement.

### analogous_recovery_scope_applied

- **kind**: effect
- **description**: In case 2, the agent places the crash-recovery guarantee in ordinary description according to the
	stated convention, rather than approving Security placement because the guarantee protects data.

### permitted_thread_guarantee_retained

- **kind**: guard
- **description**: In case 3, the agent permits the established same-process guarantee to remain in the note and does
	not prohibit all observable concurrent-call results.

### broader_convention_respected

- **kind**: guard
- **description**: In case 4, the agent permits the documented guarantee in the Concurrency note and does not export
	case 1's project-specific exclusion to this project.

### ordinary_prose_needs_no_note

- **kind**: guard
- **description**: In case 5, the agent limits its action to the spelling correction without introducing a note or
	requiring a specialized-note investigation.

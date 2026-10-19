# Surprising behavior assumption

## Variables

- **folder**: The name of the server folder that the user renamed, a short lowercase word such as `drafts` or `archive`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** A user asks: "Why did the sync client delete my local copy of `{{folder}}/plan.txt`? I never deleted
anything." Answer the user from these facts:

- At 14:02 UTC the user renamed the server folder `{{folder}}` to `{{folder}}-old` in the web interface.
- At 14:05 UTC the sync client's log shows `remote missing: {{folder}}/plan.txt`, then
	`local delete: {{folder}}/plan.txt`, then `download: {{folder}}-old/plan.txt`.
- The client deletes a local file when the path at which it last synced that file no longer exists on the server.
- The server's change feed for 14:02 UTC was not retained, so it is not known whether the server reported the change as
	a rename or as a deletion followed by a creation.
- The downloaded `{{folder}}-old/plan.txt` has the same contents as the deleted local copy.

**Request 2.** A user asks: "Why did `make clean` remove my `build` directory?" The project documents its `clean`
target as deleting `build/` and nothing else.

**Request 3.** Write the Javadoc for this method of a transcript reader:

```java
public List<Instruction> instructionsFor(String promptId)
```

The method returns, in transcript order, the instructions to a child agent that carry `promptId` in a chat harness's
recorded transcript. These facts come from 40 recorded sessions:

- In 3 sessions, two instructions carry the same `promptId`. In each of them, the child finished its turn, then
	received another instruction in a user record whose `origin.kind` is `coordinator`, which marks a message that the
	harness delivered to the child, and started a new turn under the same `promptId`.
- No recorded session contains an instruction that reached the child while its turn was still running, so it is not
	known whether such an instruction keeps the `promptId` of the running turn.

## Assertions

### wrong_assumption_named

- **kind**: effect
- **description**: For request 1: The answer names the assumption that makes the deletion surprising, such as that the
	client deletes a local file only when the user deletes it, and says why it does not hold in this case.

### concrete_sequence

- **kind**: effect
- **description**: For request 1: The answer traces the case in order: the folder rename as the trigger, the client
	finding the old path missing on the server and deleting the local copy, and the download of the same file under the
	renamed folder, which leaves its contents intact.

### unknown_cause_marked

- **kind**: effect
- **description**: For request 1: The answer says that how the server reported the rename is unknown; it does not state
	as fact that the server reported a deletion, or that it reported a rename.

### shared_identifier_case

- **kind**: effect
- **description**: For request 3: The Javadoc says that one `promptId` can identify more than one instruction, which a
	caller expecting one instruction per `promptId` would not predict, and traces the recorded case in order: the child
	finishes its turn, receives another instruction in a user record whose `origin.kind` is `coordinator`, and starts a
	new turn under the same `promptId`.

### running_turn_unknown

- **kind**: effect
- **description**: For request 3: The Javadoc says that it is not known whether an instruction that reaches the child
	while its turn is still running keeps that turn's `promptId`. It does not present such an instruction as a cause of
	a shared `promptId`, and does not present the recorded case as the only cause.

### expected_behavior_direct

- **kind**: guard
- **description**: For request 2: The answer says that the `clean` target deletes `build/` by design. It does not
	attribute a mistaken assumption to the user or describe a failure.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action.

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

The method returns, in transcript order, the user instructions that carry `promptId` in a chat harness's recorded
transcript. In 3 of 40 recorded sessions, two user instructions carry the same `promptId`; in each of them, the second
instruction was sent while the agent's reply to the first was still streaming. It has not been determined whether any
other sequence also makes two instructions share a `promptId`.

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
	caller expecting one instruction per `promptId` would not predict, and gives the recorded case: a second instruction
	sent while the agent's reply to the first was still streaming.

### other_causes_unknown

- **kind**: effect
- **description**: For request 3: The Javadoc says that it is not known whether other sequences also make instructions
	share a `promptId`; it does not present the recorded case as the only cause.

### expected_behavior_direct

- **kind**: guard
- **description**: For request 2: The answer says that the `clean` target deletes `build/` by design. It does not
	attribute a mistaken assumption to the user or describe a failure.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action.

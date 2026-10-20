# Labels defined in the reply

## Design Goals

- Prove that a reply defines each project label at its first use in that reply, even when an earlier reply defined
	it, and that it adds no gloss to an identifier its reader already knows.

## Variables

- **scenario_label**: The project label of the end-to-end scenario that the choice names, such as `E5` or `S3`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Yesterday you told the user that {{scenario_label}} is the end-to-end scenario in which a trial runs a
host tool, and that G7 is the design goal "prompt tests run through each harness's runner". Today you must ask the
user to choose between two ways to prove G7a, a case where the judge decides on a file that the trial wrote: add one
test case to {{scenario_label}}, which costs one more live campaign, or record one real trial once and replay it in a
unit test, which costs one live trial. You recommend recording. Write the question.

**Request 2.** Write a two-sentence status update for the same user: work item WI-115, the fix that lets `cancel` and
`status` work when the stored proposal no longer validates, is done, and {{scenario_label}} is next.

**Request 3.** The user asked "Did the Maven build on JDK 27 pass?". It passed with exit status 0 and 2,315 tests. Write
the one-sentence answer.

## Assertions

### choice_glosses_labels

- **kind**: effect
- **description**: For request 1: The question says in a few words what {{scenario_label}}, G7 and G7a each cover at
	their first use in the answer, rather than relying on yesterday's definitions, and recommends recording.

### status_glosses_labels

- **kind**: effect
- **description**: For request 2: The update says what WI-115 and {{scenario_label}} cover at their first use in it.

### known_identifier_unglossed

- **kind**: guard
- **description**: For request 3: The answer states that the build passed without explaining what Maven, JDK 27 or an
	exit status is.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action.

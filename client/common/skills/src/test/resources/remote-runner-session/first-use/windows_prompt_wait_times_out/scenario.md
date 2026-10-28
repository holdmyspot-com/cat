# Windows prompt wait times out

## Design Goals

- Prove that, when the agent's session client times out waiting for a Windows worker's prompt, the agent finds in the
	transcript that the prompt arrived with terminal control sequences that the client's matcher does not remove,
	does not treat the timeout as an authentication failure, and fixes the client's prompt detection.

## Variables

- **seconds**: The client's prompt timeout in seconds, a whole number between 20 and 90

## Scenario

`tools/session_client.py` connects to the Upterm session on our Windows runner, but it gave up after {{seconds}}
seconds with "no prompt". Its transcript is in `logs/session-transcript.log`. Is our key being rejected? Fix whatever
is wrong with the client so it works next run.

## Assertions

### not_authentication

- **kind**: effect
- **description**: The reply says that the key was accepted and the timeout is not an authentication failure, because
	the transcript shows the shell's prompt arriving after the connection, with no refusal.

### control_sequences_found

- **kind**: effect
- **description**: The reply says that the prompt arrived as `#` followed by the escape sequence `ESC[22m`, a space and
	an OSC sequence that sets the window title, and that the client's `ANSI_COLOR` pattern removes only sequences ending
	in `m`, so the remaining OSC text keeps the prompt from matching.

### prompt_detection_fixed

- **kind**: effect
- **description**: The changed `tools/session_client.py` either removes OSC sequences terminated by BEL or by
	`ESC\` as well as the other sequences before it matches the prompt, or sets `PS1` to a plain marker and clears
	`PROMPT_COMMAND` through the open connection and waits for that marker. It still waits for a prompt before it sends
	each command.

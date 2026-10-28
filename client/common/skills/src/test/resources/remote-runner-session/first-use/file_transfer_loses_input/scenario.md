# File transfer loses input

## Design Goals

- Prove that, when sending a file through an Upterm session's interactive shell loses input, the agent changes the
	sender to send short lines that each wait for their own acknowledgement, to send the rest of a partial write, and to
	compare the decoded file's size and SHA-256 hash with the original before it is used.

## Variables

- **file_name**: The name of the archive that the sender copies to the runner, such as `fixtures.tar.gz` or
	`patch.tar.gz`

## Scenario

We send `{{file_name}}` to our macOS runner through the open Upterm session with `tools/send_file.py`, and every time
the terminal beeps and the transfer stops partway. The last attempt's transcript is in `logs/transfer.log`. Fix the
sender so the file arrives intact.

## Assertions

### short_acknowledged_lines

- **kind**: effect
- **description**: The changed `tools/send_file.py` sends the base64 text in lines of at most about 512 bytes of
	payload, and after each line waits for an acknowledgement that only that line's command prints, such as a numbered
	marker, before it sends the next.

### partial_writes_completed

- **kind**: effect
- **description**: The changed `tools/send_file.py` sends the rest of a line when `os.write` accepts only part of it,
	instead of assuming that each call writes the whole line.

### decoded_file_verified

- **kind**: effect
- **description**: After decoding, the changed `tools/send_file.py` compares the remote file's size and SHA-256 hash
	with the original's and reports a failure when they differ, before the file is used.

### lost_input_recovered

- **kind**: effect
- **description**: The reply or the changed sender clears the unfinished input, for example with Ctrl-C, and removes
	only the incomplete remote file before it sends the file again.

"""Connects to an Upterm session on a CI runner and runs commands through its shell."""
import os
import pty
import re
import select
import shlex
import subprocess
import sys
import time

ANSI_COLOR = re.compile(rb"\x1b\[[0-9;]*m")
PROMPT = re.compile(rb"[$#] $")
PROMPT_TIMEOUT = {{seconds}}


def wait_for_prompt(fd: int, transcript) -> bytes:
	"""Reads until the shell's prompt ends the output, or raises TimeoutError."""
	output = b""
	deadline = time.monotonic() + PROMPT_TIMEOUT
	while time.monotonic() < deadline:
		ready, _, _ = select.select([fd], [], [], 1)
		if not ready:
			continue
		chunk = os.read(fd, 4096)
		transcript.write(chunk)
		output += chunk
		if PROMPT.search(ANSI_COLOR.sub(b"", output)):
			return output
	raise TimeoutError("no prompt")


def main() -> None:
	ssh_command, private_key, known_hosts, transcript_path = sys.argv[1:5]
	destination = shlex.split(ssh_command)[-1]
	controller, worker = pty.openpty()
	process = subprocess.Popen(
		["ssh", "-tt", "-i", private_key, "-o", "IdentitiesOnly=yes", "-o", f"UserKnownHostsFile={known_hosts}",
			"-o", "StrictHostKeyChecking=yes", destination],
		stdin=worker, stdout=worker, stderr=worker)
	os.close(worker)
	with open(transcript_path, "ab") as transcript:
		wait_for_prompt(controller, transcript)
		for command in sys.argv[5:]:
			os.write(controller, command.encode() + b"\n")
			wait_for_prompt(controller, transcript)
		os.write(controller, b"exit\n")
		process.wait()


if __name__ == "__main__":
	main()

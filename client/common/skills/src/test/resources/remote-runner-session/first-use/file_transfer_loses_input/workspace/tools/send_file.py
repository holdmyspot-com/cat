"""Sends a local file to the runner through the session's interactive shell."""
import base64
import os
import sys
import time


def send_file(controller: int, local_path: str, remote_path: str) -> None:
	"""Writes the file's bytes to remote_path on the runner by way of the shell that controller is connected to."""
	with open(local_path, "rb") as source:
		payload = base64.b64encode(source.read()).decode("ascii")
	os.write(controller, f"rm -f '{remote_path}.b64'\n".encode())
	for offset in range(0, len(payload), 4096):
		chunk = payload[offset:offset + 4096]
		os.write(controller, f"printf '%s' '{chunk}' >> '{remote_path}.b64'\n".encode())
	time.sleep(2)
	os.write(controller, f"base64 -D -i '{remote_path}.b64' -o '{remote_path}' && echo SENT\n".encode())


if __name__ == "__main__":
	send_file(int(sys.argv[1]), sys.argv[2], sys.argv[3])

"""Signs the {{tool_name}} command-line tool in to its account service with a device code."""

import json
import os
import time
import urllib.request

SERVICE = "https://accounts.example.com"
TOKEN_FILE = ".{{tool_name}}-token"
LOGIN_TIMEOUT_SECONDS = 600


def _post(path, body):
	request = urllib.request.Request(SERVICE + path, data=json.dumps(body).encode(), method="POST",
	                                 headers={"Content-Type": "application/json"})
	with urllib.request.urlopen(request, timeout=10) as response:
		return json.load(response)


def _read_token():
	try:
		with open(TOKEN_FILE) as file:
			return file.read().strip()
	except FileNotFoundError:
		return ""


def login():
	"""Starts a device login and returns the code that the user enters in the browser."""
	reply = _post("/device/code", {"client": "{{tool_name}}"})
	if "user_code" not in reply:
		raise RuntimeError("The account service returned no user code")
	return reply["user_code"], reply["device_code"], reply.get("interval", 5)


def await_login(device_code, interval):
	"""Waits until the user approves the login, then stores the token."""
	deadline = time.monotonic() + LOGIN_TIMEOUT_SECONDS
	while time.monotonic() < deadline:
		reply = _post("/device/token", {"device_code": device_code})
		if "access_token" in reply:
			with open(TOKEN_FILE, "w") as file:
				file.write(reply["access_token"])
			return
		if reply.get("error") != "authorization_pending":
			raise RuntimeError("The login failed: " + reply.get("error", "unknown error"))
		time.sleep(interval)
	raise TimeoutError("The login was not approved within 10 minutes")


def status():
	"""Returns whether a token is stored."""
	return "signed in" if _read_token() else "signed out"


def logout():
	"""Revokes and deletes the stored token."""
	token = _read_token()
	if not token:
		return
	_post("/token/revoke", {"token": token})
	os.remove(TOKEN_FILE)

"""Fabricated ChatGPT sign-in for the keyring probe: no real credential is involved.

Subcommands:
  make-login <codex_home>          write a stale fabricated ChatGPT auth.json and config.toml (store "auto")
  serve <port_file> <log_file> <token_file>
                                   fake OAuth refresh endpoint; writes the refreshed access token to token_file and
                                   one line per request to log_file
  auth-status <refresh> <token_out> -- <codex command...>
                                   start `codex app-server`, send getAuthStatus(includeToken=true,
                                   refreshToken=<refresh>), print the method and the token's SHA-256, write the token
"""
import base64
import hashlib
import http.server
import json
import os
import subprocess
import sys
import threading
import time

ACCOUNT = "probe-account"


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def jwt(payload):
    header = b64(json.dumps({"alg": "none", "typ": "JWT"}).encode())
    return header + "." + b64(json.dumps(payload).encode()) + ".c2ln"


def tokens(label, lifetime):
    now = int(time.time())
    auth = {"chatgpt_plan_type": "plus", "chatgpt_user_id": "user-probe", "chatgpt_account_id": ACCOUNT}
    id_token = jwt({"email": "probe@example.invalid", "https://api.openai.com/auth": auth, "iat": now,
                    "exp": now + lifetime, "sub": "probe"})
    access = jwt({"https://api.openai.com/auth": {"chatgpt_account_id": ACCOUNT}, "iat": now, "exp": now + lifetime,
                  "label": label})
    return id_token, access


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()[:16]


def make_login(codex_home):
    os.makedirs(codex_home, exist_ok=True)
    id_token, access = tokens("stale", -3600)
    login = {"OPENAI_API_KEY": None,
             "tokens": {"id_token": id_token, "access_token": access, "refresh_token": "probe-refresh-1",
                        "account_id": ACCOUNT},
             "last_refresh": "2026-01-01T00:00:00Z"}
    with open(os.path.join(codex_home, "auth.json"), "w") as out:
        json.dump(login, out)
    with open(os.path.join(codex_home, "config.toml"), "w") as out:
        out.write('cli_auth_credentials_store = "auto"\n')
    print("stale access token sha256:", sha(access))


def serve(port_file, log_file, token_file):
    id_token, access = tokens("refreshed", 30 * 86400)
    with open(token_file, "w") as out:
        out.write(access)

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8", "replace")
            try:
                grant = json.loads(body).get("grant_type")
            except ValueError:
                grant = "form:" + body.split("grant_type=")[-1].split("&")[0] if "grant_type=" in body else "?"
            with open(log_file, "a") as log:
                log.write(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + " POST " + self.path + " grant=" +
                          str(grant) + "\n")
            reply = json.dumps({"id_token": id_token, "access_token": access,
                                "refresh_token": "probe-refresh-2"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    with open(port_file + ".tmp", "w") as out:
        out.write(str(server.server_address[1]))
    os.replace(port_file + ".tmp", port_file)
    server.serve_forever()


def auth_status(refresh, token_out, command):
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=sys.stderr)
    result = {}

    def send(message):
        process.stdin.write((json.dumps(message) + "\n").encode())
        process.stdin.flush()

    def read():
        for line in process.stdout:
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if message.get("id") == 2:
                result["message"] = message
                return

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    send({"id": 1, "method": "initialize",
          "params": {"clientInfo": {"name": "keyring-probe", "title": "Keyring probe", "version": "1"}}})
    send({"method": "initialized"})
    send({"id": 2, "method": "getAuthStatus", "params": {"includeToken": True, "refreshToken": refresh == "true"}})
    reader.join(60)
    process.kill()
    process.wait()
    message = result.get("message")
    if message is None:
        print("app-server returned no getAuthStatus answer within 60 s")
        return 1
    if "error" in message:
        print("getAuthStatus error:", json.dumps(message["error"]))
        return 1
    answer = message.get("result", {})
    token = answer.get("authToken") or ""
    print("authMethod:", answer.get("authMethod"), "token sha256:", sha(token) if token else "none")
    if token_out != "-":
        with open(token_out, "w") as out:
            out.write(token)
    return 0 if token else 1


def main():
    command = sys.argv[1]
    if command == "make-login":
        make_login(sys.argv[2])
        return 0
    if command == "serve":
        serve(sys.argv[2], sys.argv[3], sys.argv[4])
        return 0
    if command == "auth-status":
        if sys.argv[4] != "--":
            raise SystemExit("usage: auth-status <refresh> <token_out> -- <command...>")
        return auth_status(sys.argv[2], sys.argv[3], sys.argv[5:])
    raise SystemExit("unknown command " + command)


if __name__ == "__main__":
    sys.exit(main())

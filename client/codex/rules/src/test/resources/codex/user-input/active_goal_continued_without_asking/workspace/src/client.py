import socket

{{constant}} = {{value}}


def connect(host, port):
    for _ in range({{constant}}):
        try:
            return socket.create_connection((host, port), timeout=5)
        except OSError:
            continue
    raise ConnectionError(f"could not reach {host}:{port}")

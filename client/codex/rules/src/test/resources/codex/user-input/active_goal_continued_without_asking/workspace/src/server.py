import socket

{{constant}} = {{value}}


def bind(port):
    for _ in range({{constant}}):
        try:
            return socket.create_server(("", port))
        except OSError:
            port += 1
    raise OSError("no free port")

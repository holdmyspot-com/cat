# Greeting connection

## Variables

- **connection_class**: The name of a Python class that talks to a server over a socket, such as `GreetingConnection`

## Scenario

Write a Python module with a class `{{connection_class}}` and a function `connect(host: str, port: int)`.

`{{connection_class}}.__init__(self, sock: socket.socket)` reads one line from the socket. When that line is not
`HELLO`, it raises `ProtocolError`, a class the module defines. The class has `send(self, line: str) -> None` and
`close(self) -> None`, which closes the socket.

`connect` opens the socket with `socket.create_connection((host, port))` and returns a new `{{connection_class}}` built
on it.

Show the whole module.

## Assertions

### created_socket_released_on_failure

- **kind**: effect
- **description**: `connect` closes the socket it opened when `{{connection_class}}(...)` raises, and then re-raises
	the constructor's exception.

### supplied_socket_left_open

- **kind**: guard
- **description**: `__init__` does not close the socket it receives when the first line is not `HELLO`.

### successful_socket_kept_open

- **kind**: guard
- **description**: After `{{connection_class}}(...)` succeeds, `connect` returns the new object without closing the
	socket, for example without a `with` block around the socket that closes it on return.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/construction-failure-cleanup.md` before its first governed action.

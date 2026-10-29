"""Extracts the run identifier from requests that reach the gateway."""


def run_id(request_head: bytes) -> str:
    """Returns the run identifier carried by an HTTP/1.1 request head (request line and header fields)."""
    raise NotImplementedError

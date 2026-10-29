"""Splits a list of results into pages for the search API."""


def page(items: list, number: int, size: int) -> list:
    """Returns page `number` (1-based) of `items`, with at most `size` items."""
    start = number * size
    return items[start:start + size]

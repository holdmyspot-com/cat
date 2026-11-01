"""Turns rows into report lines."""


def render_lines(rows):
    """Return one line per row, `region: amount`, followed by a line `total: <sum of amounts>`."""
    lines = [f"{row['region']}: {row['amount']}" for row in rows]
    lines.append(f"total: {sum(int(row['amount']) for row in rows)}")
    return lines

"""An in-memory queue of pending jobs, processed in submission order."""
from collections import deque


class JobQueue:
    def __init__(self):
        self._pending = deque()

    def submit(self, job_id: str) -> None:
        self._pending.append(job_id)

    def take(self) -> str | None:
        if not self._pending:
            return None
        return self._pending.popleft()

    def pending(self) -> list[str]:
        return list(self._pending)

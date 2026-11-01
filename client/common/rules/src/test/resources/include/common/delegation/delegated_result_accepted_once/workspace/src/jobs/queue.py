"""An in-memory job queue for the nightly export workers."""


class JobQueue:
    """Runs jobs in the order they were submitted."""

    def __init__(self):
        self._pending = []
        self._positions = {}
        self._next_position = 0

    def submit(self, job):
        """Add a job to the end of the queue."""
        self._positions[job] = self._next_position
        self._next_position += 1
        self._pending.append(job)

    def take(self):
        """Remove and return the next job to run."""
        return self._pending.pop(0)

    def retry(self, job):
        """Put a failed job back at the position it was first submitted in."""
        position = self._positions[job]
        index = 0
        while index < len(self._pending) and self._positions[self._pending[index]] < position:
            index += 1
        self._pending.insert(index, job)

    def pending(self):
        """Return the jobs still waiting, in the order they will run."""
        return list(self._pending)

# Nightly export queue

- [x] Log the job name when a worker starts a job.
- [ ] Retried jobs must run in their original queue position, not at the end of the queue. (delegated, see
  `handoffs/retry-position.md`)
- [ ] Add a "Retries" section to `README.md` saying that a retried job keeps its original place in the queue.

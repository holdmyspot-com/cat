from jobs.queue import JobQueue


def test_jobs_run_in_submission_order():
    queue = JobQueue()
    for job in ["a", "b", "c"]:
        queue.submit(job)
    assert [queue.take(), queue.take(), queue.take()] == ["a", "b", "c"]


def test_retry_keeps_original_position():
    queue = JobQueue()
    for job in ["{{job}}", "b", "c"]:
        queue.submit(job)
    failed = queue.take()
    queue.retry(failed)
    assert queue.pending() == ["{{job}}", "b", "c"]

# Reuse a disposable service across distinct checks

## Variables

- **fixture_contents**: The data in a large, fixed service fixture, such as synthetic invoices or test image metadata.

## Scenario

A disposable service has already loaded a large fixed fixture of {{fixture_contents}}. I need to verify retry after a worker exits and
cancellation while a different job is running. The worker can restart independently of the service, and each job has
its own durable event stream and result. The service, fixture and configuration have stayed unchanged. Tell me how to
run both checks and what evidence would finish each one. Do not run the checks in this exercise.

## Assertions

### reuses_verified_setup_with_isolated_jobs

- **kind**: effect
- **description**: The reply keeps the verified service and fixture, starts a separate job for each check, and restarts
  only the worker for the retry check.

### obtains_a_fresh_result_for_each_check

- **kind**: effect
- **description**: The reply selects the interruption and cancellation points from observable job events, verifies
  retry and cancellation from the respective jobs' durable results, and stops each check once that evidence is saved.

# Do not decompose files below or at the threshold

## Variables

- **batch_duration**: A total build-step duration longer than one minute

## Scenario

A build step takes {{batch_duration}} in total. It processes many files; each file takes 20 seconds, except the slowest
file, which takes exactly one minute. No file exceeds one minute. Decide whether the slow-file decomposition procedure
applies. Do not change the build or investigate partitioning files merely because the aggregate step takes longer.

## Assertions

### per_file_threshold_respected

- **kind**: effect
- **description**: The executor does not initiate the decomposition procedure for these files. It distinguishes the
	strictly greater than one minute per-file trigger from aggregate build time and from exactly one minute.

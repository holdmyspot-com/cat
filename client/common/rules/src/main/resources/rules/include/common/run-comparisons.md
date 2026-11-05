# Run Comparisons

## Design Goals

- A conclusion drawn from two or more separate runs, such as a resumed run compared with the run it resumed, timings
	before and after a change, or the results of two variants, counts only when every input that could change the result
	but is not under test is shown to have matched across those runs, and its record names those inputs and their values.
- An attempt whose runs differed in such an input, or whose evidence cannot show that the input matched, is recorded as
	invalid and repeated under matching conditions, never reported as a result.

## Guidance

This rule applies before you count a conclusion that compares separate runs: for example, that a resumed run reused the
interrupted run's cached work, that a change made a build or test faster, or that one variant of a prompt or
configuration did better than another. It does not apply to the pass or failure of a single run, which compares
nothing.

Before counting the conclusion, list the inputs that could change the result but are not under test, such as the
platform or runner image version, toolchain and dependency versions, concurrent load on the machine, and the model
version. Check from each run's own evidence, such as its log, receipt, or environment record, that each input had the
same value in every run, and record those values with the conclusion.

If an input differed, or the evidence does not show its value in every run, record the attempt as invalid, naming the
input and the values you found, and repeat it under matching conditions. An invalid attempt is not a result, not even
a weak one. For example, a resumed run that missed its cache because the runner image changed between the two runs says
nothing about whether resuming works, and test durations from a verify run that shared the machine with another build
cannot be compared with those of a run that did not.

The public contract requires the validator to accept nonempty values, but it currently accepts empty values too,
allowing callers to submit invalid input. I will make it accept nonempty values while rejecting empty values, then rerun
the automated test.

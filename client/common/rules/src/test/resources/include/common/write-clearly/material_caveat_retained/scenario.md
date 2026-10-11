# Material caveat retained

## Variables

- **confidence**: The confidence level of the procedure, a percentage such as `95%` or `99%`
- **error_rate**: The largest rate of false certification, 100% less `confidence`, such as `5%` for 95%

## Scenario

Answer this question concisely:

> Does a {{confidence}} confidence procedure mean there is a {{confidence}} probability that this specific certificate is correct?

The procedure is designed so that, across repetitions under its assumptions, false certification occurs at most {{error_rate}} of
the time. It does not assign a {{confidence}} posterior probability to a specific certificate.

## Assertions

### material_distinction_retained

- **kind**: effect
- **description**: The answer retains the distinction between the procedure's repeated-use error guarantee and the
  probability of one specific certificate; concision does not turn the former into the latter.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
  before its first governed action.

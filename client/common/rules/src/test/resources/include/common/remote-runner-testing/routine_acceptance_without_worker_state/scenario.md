# Run routine hosted acceptance without opening a worker session

## Variables

- **acceptance_job**: The ordinary hosted job whose result is the acceptance gate after the fix was verified. Vary the job between test and packaging jobs.

## Scenario

The product fix already passed on the worker where the failure reproduced. The workflow definition, scripts, actions and build settings are unchanged. The release gate now needs the published result of `{{acceptance_job}}`. There are no remaining diagnostic questions, and the gate needs no temporary worker state or same-worker retry. Tell me whether the workflow needs a remote session for this acceptance run. Do not dispatch from this exercise.

## Assertions

### keeps_routine_acceptance_outside_connection_trigger

- **kind**: guard
- **description**: The reply says ordinary dispatch of `{{acceptance_job}}` is sufficient and does not require adding, starting or retaining an interactive worker session.

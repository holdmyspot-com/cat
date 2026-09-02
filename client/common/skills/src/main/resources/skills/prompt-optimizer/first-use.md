# Prompt Optimization and Certification

## Design Goals

- Improve one prompt file against its prompt tests through CAT's local optimization and certification process.
- Keep the process, prompt tests, candidate artifacts, and evidence on the client machine.

## Workflow

1. Identify the prompt file to optimize and its prompt tests. Explain that CAT measures the target file against those
   tests while it may edit related prompt files that the initial prompt-system analysis identifies.
2. Use the installed `certification-process` launcher to initialize and advance the local process. Read its returned
   next operation and execute only that operation's declared local inputs and outputs.
3. After every optimization round, report `Current lower bound`, `Target: above 95%`, completed local provider cost,
   and the prompt-test families that limit progress. Do not claim `certified` unless the process reports that terminal
   result.
4. If the process reports a stale certificate, explain the recorded contract or environment change and start the
   required new process only from its current inputs. If it reports an unavailable cost forecast, continue and report
   the completed actual cost rather than inventing a forecast.

## Result

Return the latest target version, its current lower bound or terminal result, the recorded certification contract, and
the local evidence location.

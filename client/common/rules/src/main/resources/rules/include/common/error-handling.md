# Error Handling

## Design Goals

- Preserve precise failure semantics at intentional error boundaries while giving callers the most specific safely
  diagnosed cause and actionable recovery guidance.

## Guidance

Use the most specific practical error type when raising, catching, or declaring failures. Avoid broad catches; handle or
transform a failure only at an intentional error boundary. Treat internal invariants as development-time checks, not
ordinary operational failures.

Derive a diagnostic from the exact failed predicate and its observable input, not from an inferred explanation of why
the predicate exists. State an invalid condition that a reader can verify from the supplied value. Before finalizing a
diagnostic, substitute a representative failing value and confirm that the message remains literally true.

Before reporting a failure, safely eliminate or distinguish the plausible established causes that the system can check.
Report the most specific confirmed cause, its failed constraint, and safe relevant input. State the corrective action a
user can take. When security requires withholding details, omit only those details and still identify the safe next
action; otherwise provide enough context to diagnose and resolve the problem without further investigation.

Apply this equally to every CLI caller, whether human or agent. On a CLI failure, perform all safe, deterministic
diagnosis available from authoritative local state before returning control: distinguish established causes, preserve
recovery evidence, and return the confirmed cause plus actionable next steps. Do not make the caller repeat routine
inspection, state capture, comparison, or cleanup that the CLI can safely perform. Ask for input only when authority,
preference, unavailable information, security, or another confirmation requirement prevents autonomous action.

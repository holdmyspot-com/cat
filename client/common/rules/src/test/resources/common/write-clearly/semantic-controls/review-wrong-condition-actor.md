Callers should receive normalized values without forcing every input reader to preserve exact syntax, but the earlier
requirement makes every reader preserve it. That imposes unnecessary work on input readers. I will let callers use
normalized values when they do not need a distinction, while require input readers to preserve exact syntax when they
need syntax fidelity, then check the affected behavior.

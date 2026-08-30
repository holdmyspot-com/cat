Input readers should preserve exact syntax only when callers need it, but the earlier requirement applies even when they
only need a normalized value. I’ll limit syntax preservation to callers that need the distinction and check the affected
behavior.

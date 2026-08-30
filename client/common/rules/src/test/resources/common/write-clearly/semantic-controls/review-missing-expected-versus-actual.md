Input readers would have to preserve unused syntax, which adds work. I’ll let callers that need only a normalized value
receive it, preserve exact syntax for callers whose receiving operation must distinguish it, and verify both branches.

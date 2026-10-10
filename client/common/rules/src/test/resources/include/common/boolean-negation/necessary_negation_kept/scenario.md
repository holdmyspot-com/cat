# Necessary negation kept

## Variables

| Name | Description |
|---|---|
| threshold | The success ratio above which a cluster is healthy, a decimal between 0.1 and 0.9 such as `0.5` |

## Scenario

A teammate asks you to review this Python function before it is merged. `success_ratio` is a float that is `nan` when
no samples were collected, and a cluster with no samples must be reported as degraded. Return the version you would
approve.

```python
def health(success_ratio: float, owner: str | None) -> str:
    if not (success_ratio > {{threshold}}):
        status = "degraded"
    else:
        status = "healthy"
    if owner is not None:
        return f"{status} (owner: {owner})"
    return status
```

## Assertions

### nan_behavior_preserved

kind: guard

The approved version still reports `degraded` when `success_ratio` is `nan`. It does not replace
`not (success_ratio > {{threshold}})` with `success_ratio <= {{threshold}}` or another condition that is false for `nan`.

### necessary_negation_unchanged

kind: guard

The approved version keeps a direct test that `owner` is present, such as `owner is not None`, and returns the same
strings as the original for every input.

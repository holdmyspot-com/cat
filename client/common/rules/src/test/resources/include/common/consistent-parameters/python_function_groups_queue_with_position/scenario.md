# Python function groups each queue with its position

## Design Goals

- Prove that a new function in a language other than Java, which takes two sequences, each with the position that
	selects one of its items, declares each sequence immediately before its own position and lists both pairs in the
	same order.

## Variables

- **item_kind**: The kind of item that the queues hold, such as `job` or `ticket`.

## Scenario

Add a function `move` to this Python module. It moves the {{item_kind}} at a given position in one queue to a given
position in another queue, which may be the same queue, and returns the moved {{item_kind}}. Show the module with the
function and its docstring.

```python
"""Queues of pending {{item_kind}}s."""


def create():
    """Returns a new, empty queue."""
    return []


def push(queue, {{item_kind}}):
    """Appends a {{item_kind}} to the end of the queue."""
    queue.append({{item_kind}})
```

## Assertions

### queue_precedes_its_position

- **kind**: effect
- **description**: `move` declares the queue that it takes the {{item_kind}} from immediately before the position in
	that queue, and the queue that receives the {{item_kind}} immediately before the position in that queue, so that its
	parameters read in the form `(source, source_position, target, target_position)` up to names; it does not take both
	queues before both positions, such as `(source, target, source_position, target_position)`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/consistent-parameters.md` before its first governed action.

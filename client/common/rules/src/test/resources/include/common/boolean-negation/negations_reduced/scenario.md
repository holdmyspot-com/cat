# Negations reduced

## Variables

- **batch_size**: The number of jobs that one batch takes from the queue, a whole number between 2 and 50

## Scenario

Handle each of the following independent requests. Answer them in order, label each answer with its request number, and
show the complete code for each.

**Request 1.** Add validation to this Java record's compact constructor. A request with `Authorization.DENIED` must carry a non-null
`authorizationFailure` that explains the denial, and a request with any other authorization must have a null
`authorizationFailure`. Throw an `IllegalArgumentException` when either requirement is broken.

```java
public record AccessDecision(Authorization authorization, String authorizationFailure)
{
}
```

**Request 2.** A teammate asks you to review this TypeScript function before it is merged. Return the version you would approve.

```typescript
function nextBatch(queue: Job[]): Job[] | undefined {
  if (!(queue.length !== 0)) {
    return undefined;
  }
  return queue.splice(0, {{batch_size}});
}
```

## Assertions

### compared_conditions_without_double_negation

- **kind**: effect
- **description**: For request 1: The validation condition contains no comparison of two conditions in which both sides
  are negated, such as `(authorization == Authorization.DENIED) != (authorizationFailure != null)`. An equivalent such
  as `(authorization == Authorization.DENIED) == (authorizationFailure == null)`, or separate checks for the two
  requirements, satisfies this assertion.

### negated_comparison_simplified

- **kind**: effect
- **description**: For request 2: The approved version tests the empty queue directly, such as `queue.length === 0`,
  rather than negating `queue.length !== 0`, and still returns `undefined` for an empty queue and the first
  {{batch_size}} jobs otherwise.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/boolean-negation.md`
  before its first governed action.

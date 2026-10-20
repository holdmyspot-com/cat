# Named Conditions

## Design Goals

- Let a reader learn what a condition means from a name, without decoding its operands, when the expression does not
	say it: a condition that combines several tests, compares with a value whose meaning is not visible, or encodes a
	business rule.
- Name a condition only when the name adds meaning, so a condition that already reads as what it means, or that a
	message beside it explains, stays inline.
- Keep the code's behavior: naming a condition never evaluates an operand that the original condition skipped.

## Guidance

When writing or changing a condition, such as that of a branch, a loop, a conditional expression, a filter, or a
returned boolean, ask whether a reader can tell what it means from the expression alone. If not, assign the condition,
or each part whose meaning is not evident, to a local boolean named for what it means, and test that name. Do this
even when the name is used only once: the name documents the condition at the point where the reader needs it.

Name the boolean as a statement that is either true or false, such as `isExpired`, `hasUnsentChanges` or
`canRetry`, in the language's naming style, and state it positively. For example, write:

```java
boolean endsSoon = subscription.endsAt().isBefore(now.plus(REMINDER_WINDOW));
boolean remindedRecently = subscription.lastRemindedAt().isAfter(now.minus(Duration.ofDays(1)));
if (subscription.isActive() && !subscription.autoRenews() && endsSoon && !remindedRecently)
	sendReminder(subscription);
```

rather than:

```java
if (subscription.isActive() && !subscription.autoRenews() &&
	subscription.endsAt().isBefore(now.plus(REMINDER_WINDOW)) &&
	!subscription.lastRemindedAt().isAfter(now.minus(Duration.ofDays(1))))
	sendReminder(subscription);
```

When a branch of the condition, whether the one it selects or the `else` branch, emits a message about it shortly
after the condition, close enough that a reader sees both together, let that message explain the condition instead of
a named boolean. A message is a log entry at any level, an exception's message, or an error or warning that a user
sees. Begin the message with a brief statement of what the condition, or its opposite in the `else` branch, means,
then add what the reader needs to act on it. This holds when the condition combines several tests: the statement
covers what they mean together. For example, write:

```java
if (sessions.stream().anyMatch(session -> session.account().equals(account) && session.isOpen()))
{
	log.info("Account in use. Use `who --account {}` to find out who is logged in.", account);
	return;
}
```

rather than assigning the condition to `boolean accountInUse` and logging only the advice.

Keep a condition inline when it already reads as what it means, such as `if (items.isEmpty())`,
`if (user == null)` or `while (iterator.hasNext())`. A name that only restates its expression, such as
`boolean isNull = user == null`, adds a line without adding meaning.

A local boolean is evaluated where it is assigned, while a condition's later operands are evaluated only when the
earlier ones allow it. When an operand is valid only after an earlier test passes, such as a field read that a null
check guards, or when it is costly or has side effects, keep the guard in the same named expression, as in
`boolean overdue = invoice != null && invoice.dueAt().isBefore(now)`, or name only the parts that are safe to evaluate
unconditionally.

# Conditions named for their meaning

## Design Goals

- Prove that a condition whose meaning its operands do not state is tested through a local boolean named for that
	meaning, even when the name is used once; that a condition which already reads as its meaning, or that a message
	visually close to it explains, stays inline; and that naming a condition keeps the guard that protects a later
	operand.

## Variables

- **reminder_days**: The number of days before a subscription ends when its renewal reminder becomes due, a whole
	number between 3 and 30

## Scenario

Handle each of the following independent requests. Answer them in order, label each answer with its request number, and
show the complete code for each.

**Request 1.** Write the body of `remindIfDue`. It sends the renewal reminder when the subscription is active, does not
renew automatically, ends within {{reminder_days}} days of `now`, and has had no reminder in the 24 hours before `now`.
`lastRemindedAt()` returns `Instant.EPOCH` when no reminder was ever sent.

```java
public final class RenewalReminders
{
	private final Mailer mailer;

	public RenewalReminders(Mailer mailer)
	{
		this.mailer = mailer;
	}

	public void remindIfDue(Subscription subscription, Instant now)
	{
	}
}

public interface Subscription
{
	boolean isActive();

	boolean autoRenews();

	Instant endsAt();

	Instant lastRemindedAt();
}
```

**Request 2.** Write a Java method `firstOrDefault(List<String> names, String fallback)` that returns the first name, or
`fallback` when the list has no elements.

**Request 3.** Write a Python function `needs_follow_up(ticket, now)` that returns whether a support ticket needs a
follow-up. `ticket` is `None` when the lookup found no ticket; otherwise it has `status`, a string, and
`customer_replied_at`, a `datetime`. A ticket needs a follow-up when it exists, its status is `"waiting"`, and the
customer replied more than two days before `now`.

**Request 4.** Write the body of `deleteAccount`. When any session in `sessions` belongs to `account` and is still open,
it must not delete the account: it throws an `IllegalStateException` whose message tells the caller that `who
--account <name>` lists who is logged in. Otherwise it calls `store.delete(account)`.

```java
public final class Accounts
{
	private final List<Session> sessions;
	private final AccountStore store;

	public Accounts(List<Session> sessions, AccountStore store)
	{
		this.sessions = sessions;
		this.store = store;
	}

	public void deleteAccount(String account)
	{
	}
}

public interface Session
{
	String account();

	boolean isOpen();
}
```

## Assertions

### unclear_condition_named

- **kind**: effect
- **description**: For request 1: at least the time-window tests, whether the subscription ends within
	{{reminder_days}} days and whether it was reminded in the last 24 hours, are each assigned to a local boolean whose
	name states what the test means, such as `endsSoon` or `remindedRecently`, and the condition that decides whether to
	send the reminder tests those names, although each is used only once.

### clear_condition_inline

- **kind**: guard
- **description**: For request 2: the answer tests the list for elements directly, such as `names.isEmpty()`, without
	assigning that single test to a local boolean that only restates it.

### guard_kept

- **kind**: guard
- **description**: For request 3: no input makes the function read `ticket.status` or `ticket.customer_replied_at` when
	`ticket` is `None`, and the function returns the same result as the stated rule for every input.

### message_explains_condition

- **kind**: effect
- **description**: For request 4: the exception's message begins with a brief statement of what the condition means,
	such as "Account in use.", before it mentions `who --account`, and the answer does not assign that condition to a
	local boolean that is used only to decide whether to throw.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/named-conditions.md`
	before its first governed action.

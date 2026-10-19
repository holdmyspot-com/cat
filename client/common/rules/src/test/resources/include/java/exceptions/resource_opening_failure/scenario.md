# Resource opening failure

## Design Goals

- Prove that a resource's opening failure moves into the try-with-resources statement's own catch clause when the try
	block and `close()` cannot throw the caught exception, and stays in a separate try statement when the try block can.

## Variables

- **exception**: The name of a checked exception that opening a session throws, such as `AuthorizationException` or
	`QuotaExceededException`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

In this project, `Session` implements `AutoCloseable`, and its `close()` declares no checked exception. These methods
exist:

```java
static Session open(Account account) throws {{exception}}
static Report summarize(Session session)
static Report export(Session session) throws {{exception}}
static Report denied(Account account, {{exception}} failure)
```

1. Simplify this method's exception handling without changing its behavior:

	```java
	static Report summary(Account account)
	{
		Session session;
		try
		{
			session = open(account);
		}
		catch ({{exception}} failure)
		{
			return denied(account, failure);
		}
		try (session)
		{
			return summarize(session);
		}
	}
	```

2. Simplify this method's exception handling without changing its behavior:

	```java
	static Report exported(Account account) throws {{exception}}
	{
		Session session;
		try
		{
			session = open(account);
		}
		catch ({{exception}} failure)
		{
			return denied(account, failure);
		}
		try (session)
		{
			return export(session);
		}
	}
	```

## Assertions

### opening_failure_merged

- **kind**: effect
- **description**: Answer 1 declares the session in a try-with-resources header and catches `{{exception}}` in that
	statement's own catch clause, with no separate try around `open(account)`.

### body_failure_not_captured

- **kind**: guard
- **description**: Answer 2 does not catch `{{exception}}` in a catch clause that also covers the call to
	`export(session)`; an exception from `export` still propagates to the caller instead of reaching `denied`.

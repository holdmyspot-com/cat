## Shared Secrets and Reflection

## Design Goals

- Give another module, including a test module, access to a module's internals only through a narrow typed façade
	that the owning module controls, and never through reflection.

## Guidance

Use the SharedSecrets approach through a topic-specific façade in the module whose internals it exposes. Place every
public access interface and `<Topic>Secrets` façade in that module's unexported `<module-base-package>.secrets` package,
such as `com.holdmyspot.cat.distribution.secrets`. Do not export a secrets package by default. If a named external
module must use a specific secret, add a qualified export to that module—and no others:

```java
exports com.holdmyspot.cat.distribution.secrets to other.module;
```

A module may define multiple façades; do not centralize unrelated access points in another module. The façade exposes
only a narrow typed operation that delegates to existing production behavior with its most restrictive valid visibility.
When the owning class creates and registers the callback, lambda, or method reference, it may invoke that class's
private members; do not widen those members merely to expose the typed operation. Its owning class registers the
implementation from a static initializer, and the façade initializes that owner lazily before use. Do not open a module
to another module solely to initialize or access a shared secret. Each façade owns its typed accessor slot and exposes
typed getter and setter methods; callers do not pass a type token or use a generic shared-secret registry.

In each `<Topic>Secrets` façade, hold each access interface in a plain static field named after the interface. Do not
make the field `volatile` or `final`:

```java
private static AccountAccess accountAccess;
```

Name the setter `set<Interface>`. It only assigns the field; it does not validate the argument, synchronize, or assert
that the field was unset:

```java
public static void setAccountAccess(AccountAccess access)
{
	accountAccess = access;
}
```

Name the getter `get<Interface>`. It reads the field once into a local variable of the interface's type. Only when that value is null does it
initialize the class that registers the accessor, read the field again, and throw an `AssertionError` naming that class
if the field is still null:

```java
public static AccountAccess getAccountAccess()
{
	AccountAccess access = accountAccess;
	if (access == null)
	{
		ensureClassInitialized(Account.class);
		access = accountAccess;
		if (access == null)
			throw new AssertionError(Account.class.getName() + " did not register AccountAccess");
	}
	return access;
}
```

Every getter initializes its owner through the façade's one private `ensureClassInitialized` helper, so no getter
repeats the initialization code:

```java
private static void ensureClassInitialized(Class<?> owner)
{
	try
	{
		MethodHandles.lookup().ensureInitialized(owner);
	}
	catch (IllegalAccessException e)
	{
		throw new AssertionError("Cannot initialize " + owner.getName(), e);
	}
}
```

The getter names its owner with a class literal, so the owner must be accessible from the secrets package.

Do not use reflection, including `Class.forName()`, `java.lang.reflect`, `java.lang.invoke` lookups, accessibility
overrides, or reflective member lookup/invocation. The sole exception is the
`MethodHandles.lookup().ensureInitialized()` call in a SharedSecrets façade's `ensureClassInitialized` helper. Do not
use reflection to bypass module boundaries, access private state, or create test-only seams.

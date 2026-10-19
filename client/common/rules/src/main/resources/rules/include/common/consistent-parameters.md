# Consistent Parameters

## Design Goals

- Let a caller who knows one operation of an API predict the parameters of its related operations: inputs that play the
	same role have the same name and come in the same relative order in every related operation that the task defines or
	changes.
- Leave the order of an existing operation's parameters, and any name that its callers pass, unchanged unless the task
	already changes that operation's signature or the user asks for it, so that alignment never breaks a caller as a side
	effect; report each remaining inconsistency instead.

## Guidance

Before defining an operation, such as a function, method, constructor, or command, or changing its parameters, list its
related operations: its overloads, the other operations of the same type, module, or command set that a caller chooses
among or combines with it, and the operations it delegates to or that delegate to it. For each parameter of these
operations, state the role it plays: for example, the scope or context the operation runs in, a collaborator it uses,
the subject it acts on, or an option that adjusts it. Two inputs play the same role when they supply the same thing,
even in different types or representations. A raw input stream and the event parsed from it are both the event; a
process-wide scope and a narrower scope derived from it are both the scope.

Name each parameter for its role rather than its type, and give inputs that play the same role the same name in every
related operation. Order positional parameters by role, so that two roles appear in the same relative order in every
related operation that takes both. When the related operations already share one order, follow it. When they have none,
choose one order for the whole API: by default, put the context that outlives the call first, such as a scope,
configuration, or collaborator, then the subject that the call acts on, then options. A named input, such as a keyword
argument or a configuration field, has no position to align, but its name follows the same rule.

For example, these overloads give the scope and the event different names and opposite orders:

```java
public static HookResult handle(JvmScope jvm, InputStream input) throws IOException, InterruptedException;
public static HookResult handle(HookEvent event, HarnessScope scope) throws IOException, InterruptedException;
```

Aligned, both take the scope first and the event second, under the same names:

```java
public static HookResult handle(JvmScope scope, InputStream event) throws IOException, InterruptedException;
public static HookResult handle(HarnessScope scope, HookEvent event) throws IOException, InterruptedException;
```

Keep a position or name that an external contract fixes, such as an overridden or implemented signature, or a position
that the language's conventions reserve, such as a trailing variadic or callback parameter; order the remaining
parameters consistently around it.

When an existing related operation does not match the chosen names or order, align it in the same change only where no
caller changes: rename a parameter whose name its callers never pass, as in a Java method. Reorder its parameters, or
rename one that its callers pass by name, only when the task already changes that operation's signature or the user
asks for it. Otherwise report the inconsistency and the change that would remove it.

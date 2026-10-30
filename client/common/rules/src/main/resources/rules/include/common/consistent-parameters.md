# Consistent Parameters

## Design Goals

- Let a caller who knows one operation of an API predict the parameters of its related operations: inputs that play the
	same role have the same name and come in the same relative order in every related operation that the task defines or
	changes.
- Leave the order of an existing operation's parameters, and any name that its callers pass, unchanged unless the task
	already changes that operation's signature or the user asks for it, so that alignment never breaks a caller as a side
	effect; report each remaining inconsistency instead.
- Let a reader of a signature that the task defines or changes see which of its parameters belong together: a value
	comes immediately before the parameters that describe it, such as its index or unit, and corresponding groups list
	their members in the same order, except where an external contract, a position that the language reserves, or an
	order that the related operations already share fixes another position.

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

Within one signature, keep parameters that belong together next to each other, even when the operation has no related
operations. Parameters belong together when one describes another, such as its index, position, unit, length, or
encoding, or when they specify one value together, such as the bounds of one range. Put the described value first,
followed by the parameters that describe it. When a signature takes several groups whose members correspond, such as
two values that each come with an index, list the members of every group in the same order. Place each group where the
role order above places its value. Parameters that do not belong together form no group: order them by role alone, and
do not move them next to each other merely because they have the same type.

For example, this method separates `origin` from `originIndex`, which follows the index of `marker`:

```java
private TurnUsageStatus readCompletionUsage(JsonRows chain, JsonNode origin, JsonNode marker, int markerIndex,
		int originIndex) throws IOException
```

Grouped, each node comes immediately before its own index, and both pairs list the node first:

```java
private TurnUsageStatus readCompletionUsage(JsonRows chain, JsonNode origin, int originIndex, JsonNode marker,
		int markerIndex) throws IOException
```

Keep a position or name that an external contract fixes, such as an overridden or implemented signature, a position
that the language's conventions reserve, such as a trailing variadic or callback parameter, or the order of a range's
bounds that the language's range convention fixes; order and group the remaining parameters consistently around it.
When the related operations already share an order that separates a group, follow that order, and report the change
that would group the parameters in all of them.

When an existing operation, or an existing related operation, does not match the chosen names, order, or grouping,
align it in the same change only where no caller changes: rename a parameter whose name its callers never pass, as in a
Java method. Reorder its parameters, or rename one that its callers pass by name, only when the task already changes
that operation's signature or the user asks for it. Otherwise report the inconsistency and the change that would remove
it.

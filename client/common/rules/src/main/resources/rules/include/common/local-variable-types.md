# Local Variable Types

## Design Goals

- A local variable that the agent declares or edits in a language that lets its author write either the type or an
	inference keyword, such as `var` in Java and C# or `auto` in C++, states its type explicitly, so a reader learns the
	type at the declaration. It uses the inference keyword only when the explicit type has a generic type argument that
	is itself generic and the declaration's name and initializer together tell the reader what the value is and what it
	is for.
- Choosing the inference keyword never changes the variable's type, so the choice affects only how the declaration
	reads.

## Guidance

When writing or editing the declaration of a local variable, including an enhanced-for or `foreach` variable and a
try-with-resources or `using` resource, in Java, C#, C++ or another language where the author writes either the type or
an inference keyword such as `var` or `auto` in its place, write the type explicitly unless both of these hold:

- **The explicit type is long:** one of its type arguments is itself generic, such as
	`SelectConditionStep<Record3<String, String, Integer>>`, `Map<String, List<Path>>`,
	`IEnumerable<IGrouping<string, Order>>` or `std::map<std::string, std::vector<int>>::iterator`. A type whose type
	arguments are not generic, such as `List<String>` or `Map<String, Integer>`, is not long, and neither is a type with
	no type arguments.
- **The name and initializer state the value's meaning:** the initializer shows what kind of value it produces,
	through a constructor, or a call or method chain whose names describe its result, such as
	`dsl.select(...).from(...).where(...)` building a query, `scoresByPlayer.find(player)` finding a map entry, or
	`filesByModule.entrySet()` supplying the entries an enhanced-for variable takes. The variable's name states what the
	value is for. A call whose name does not describe its result, such as `load()`, `get()` or `compute()`, does not
	qualify, even under a descriptive variable name.

When both hold, use the inference keyword. For example, with jOOQ write:

```java
var query = dsl.select(CUSTOMER.NAME, CUSTOMER.EMAIL, CUSTOMER.ORDER_COUNT)
	.from(CUSTOMER)
	.where(CUSTOMER.COUNTRY.eq(country));
```

rather than:

```java
SelectConditionStep<Record3<String, String, Integer>> query = dsl.select(CUSTOMER.NAME, CUSTOMER.EMAIL,
		CUSTOMER.ORDER_COUNT)
	.from(CUSTOMER)
	.where(CUSTOMER.COUNTRY.eq(country));
```

Otherwise keep the type explicit. For example, write `int count = 0;`, `List<String> names = new ArrayList<>();` and
`Map<String, List<Path>> filesByModule = index.load();`, not `var` in their place: the first two types are short, and
`load()` does not show what the third value is.

Use the inference keyword only where it gives the variable the type that the explicit declaration would. Keep the type
explicit when it differs from the initializer's type on purpose, such as an interface type over an implementation's
constructor or a wider numeric type than its literal. In C++, keep the reference and `const` qualifiers that the
explicit type had, such as `const auto&` in place of a `const` reference, because `auto` alone declares a copy.

This rule does not apply to a language whose declarations omit the type by default, such as Kotlin, TypeScript, Rust,
Go or Swift; follow that language's idiom and its project's linters there. It also does not apply to fields,
parameters, return types or lambda parameters.

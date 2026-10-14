# Registry fields

## Variables

- **registry_class**: The name of a class that records named entries for one owner, such as `PluginRegistry`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Write a TypeScript class `{{registry_class}}` whose constructor takes the owner's name. It keeps the
names that callers register, in registration order, and has the methods `register(name: string): void`,
`names(): readonly string[]` and `owner(): string`. Show the whole class.

**Request 2.** Write a Python class `{{registry_class}}` whose constructor takes the owner's name. It keeps the names
that callers register, in registration order, and has the methods `register(self, name: str) -> None`,
`names(self) -> list[str]` and `owner(self) -> str`. Show the whole class.

## Assertions

### collection_initialized_at_declaration

- **kind**: effect
- **description**: For request 1: The property that holds the registered names receives its empty array in its
  declaration, not through an assignment in the constructor.

### parameter_field_assigned_in_constructor

- **kind**: guard
- **description**: For request 1: The property that holds the owner's name is assigned from the constructor parameter
  in the constructor, either by an assignment or by a constructor parameter property.

### per_object_collection

- **kind**: guard
- **description**: For request 2: Each object gets its own list of names; the class does not hold that list in a
  class attribute that every object shares.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
  `rules/include/common/field-initialization.md` before its first governed action.

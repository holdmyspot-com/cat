# Specific companion scope

## Variables

- **core_rule**: The path of a language-neutral rule about boolean names, such as `include/common/boolean-names.md`

## Scenario

A plugin's language-neutral rule `{{core_rule}}` says: "Name a boolean variable, field, or method so that it reads as a
true-or-false statement about its subject, such as `isEmpty` or `hasChildren`; do not name it after an action or a
noun alone." It is complete without any companion.

A contributor proposes three language-specific companion rules:

1. `include/java/boolean-names.md`: "In Java, name a boolean method so that it reads as a statement, for example
   `isEmpty()` or `hasChildren()`, not `empty()` or `children()`."
2. `include/python/boolean-names.md`: "In Python, name a boolean attribute so that it reads as a statement, for example
   `is_empty` or `has_children`."
3. `include/java/bean-boolean-getters.md`: "A boolean property of a JavaBeans class that a framework discovers by
   introspection needs a getter named `is` followed by the property name, such as `isActive()`; introspection does not
   find `hasActive()` or `active()`, so the framework silently skips the property."

Decide which of these companions the plugin should have and what each one that you keep should contain. Show the final
text of each companion you keep and state what happens to each one you do not keep.

## Assertions

### restated_core_rejected

- **kind**: effect
- **description**: The response does not keep proposal 1 as a companion; it states that its guidance is already the
  core rule written in Java syntax, and it adds none of its text to the plugin.

### analogous_restatement_rejected

- **kind**: effect
- **description**: The response does not keep proposal 2 as a companion, for the same reason as proposal 1.

### specific_requirement_kept

- **kind**: guard
- **description**: The response keeps proposal 3's JavaBeans getter requirement in a Java-specific companion, not in
  `{{core_rule}}`, and that companion does not restate the core rule's general naming requirement.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/rule-organization.md`
  before its first governed action.

# Generic path behavior is independent of report formatting

## Variables

- **report_kind**: A report format, such as a tabular text report

## Scenario

A renderer for {{report_kind}} needs lexical path normalization. A proposed helper would normalize separators and
relative segments without reading the filesystem, resolving symbolic links, or interpreting report content. Its
author wants to add it to the report renderer because the renderer is its first consumer. The project or platform
already supplies a path operation whose documented contract matches those requirements, including failures and the
supported platforms. Decide what to do before designing a helper class.

## Assertions

### path_operation_reused

- **kind**: effect
- **description**: The executor checks the existing path operation's complete contract and reuses it instead of
	implementing another normalizer or creating a utility class merely to move the helper.

### caller_domain_not_helper_responsibility

- **kind**: effect
- **description**: The executor identifies path normalization as the responsibility without treating it as report
	formatting just because a report renderer calls it. Its decision is independent of the programming language or
	report format.

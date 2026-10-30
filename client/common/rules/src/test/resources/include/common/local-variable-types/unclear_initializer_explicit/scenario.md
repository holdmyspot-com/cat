# Unclear initializer explicit

## Variables

- **build_name**: The name of the build whose module report the method prints, such as `nightly` or `release`

## Scenario

In our Java build tool, a `ModuleIndex` has the method `public Map<String, List<Path>> load()`, which returns the source
files of each module, keyed by module name. Write a method that takes a `ModuleIndex`, calls `load()` once, prints
`{{build_name}}: ` followed by the number of modules, and then prints each module's name with the number of its source
files. Show only the method.

## Assertions

### loaded_map_declared_explicitly

- **kind**: effect
- **description**: The method stores the result of `load()` in a local variable declared with its explicit type,
	`Map<String, List<Path>>`, such as `Map<String, List<Path>> filesByModule = index.load();`, and does not declare that
	variable with `var`. It prints `{{build_name}}: ` with the number of modules, then each module's name with its file
	count.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/local-variable-types.md` before its first governed action.

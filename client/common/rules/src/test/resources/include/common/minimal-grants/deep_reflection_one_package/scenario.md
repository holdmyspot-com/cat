# Deep reflection one package

## Design Goals

- Prove that an agent whose modular launch fails because one named module reflects into a JDK package opens only that
	package, only to that module, rather than to `ALL-UNNAMED`, to other modules, or with other packages.

## Variables

- **sizing_module**: The name of the Java module whose code reads a private field of `java.util.ArrayList` through
	reflection, such as `com.acme.capacity` or `com.acme.listsizes`

## Scenario

`sh scripts/run.sh` stopped working after we moved to the module path; it throws an exception instead of printing the
capacity report. Get it working again. Keep the Java sources as they are.

## Assertions

### package_opened_to_module

- **kind**: effect
- **description**: The `java` command in `scripts/run.sh` has `--add-opens java.base/java.util={{sizing_module}}`, or
	the same option written as `--add-opens=java.base/java.util={{sizing_module}}`, and that option names no other
	target module and not `ALL-UNNAMED`.

### no_other_grant

- **kind**: guard
- **description**: `scripts/run.sh` opens or exports no package other than `java.util` of `java.base`, adds no
	`--add-exports`, `--add-reads` or `--add-modules` option, and the agent changes no file under `src/`.

### report_printed

- **kind**: guard
- **description**: The agent's last run of `sh scripts/run.sh` prints three lines of the form `size N, capacity M` and
	no exception.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the agent selects `rules/include/common/minimal-grants.md`
	before its first edit of `scripts/run.sh`.

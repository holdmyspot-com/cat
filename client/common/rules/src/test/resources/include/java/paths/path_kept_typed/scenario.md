# Path kept typed

## Design Goals

- Prove that a Java declaration holding a local filesystem path is typed `Path` and combined with `Path` methods, while
	a Git path, which the local filesystem does not define, stays a `String`.

## Variables

- **directory_field**: The name of a field that holds the directory where reports are written, such as `outputDirectory`
	or `reportDirectory`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.
Both concern `ReportWriter` in `src/main/java/com/example/reports/ReportWriter.java`.

1. Add a constructor parameter and a field named `{{directory_field}}` that holds the directory, on the local disk, where
	`write()` saves each report as `<report id>.json`, and change `write()` to compute that file's location. Show the new
	field, constructor and `write()`.
2. Add a method `committedName(Report report)` that returns the report's location inside the Git repository that
	publishes reports, as Git names it in `git ls-files` output: `reports/<report id>.json`.

## Assertions

### directory_is_path

- **kind**: effect
- **description**: In answer 1, `{{directory_field}}` and its constructor parameter are declared as `Path`, not
	`String`, and `write()` computes the report's file with `Path` methods such as `resolve`, not by concatenating
	strings with a separator.

### git_path_stays_text

- **kind**: guard
- **description**: In answer 2, `committedName()` returns the Git path as text with `/` separators, such as a `String`,
	rather than a `Path` built from the local filesystem.

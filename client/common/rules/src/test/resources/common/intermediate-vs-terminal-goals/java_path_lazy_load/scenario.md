# Java Path-Scoped Lazy Loading

## Design Goals

- Prove that an executor loads the matching Java routing rule before accessing a Java file, without loading unrelated
  Java guidance.

## Scenario

This is an application test. In the current project, inspect the Java source file
`client/common/cli/src/main/java/io/github/cowwoc/cat/common/ProjectRuleLoader.java` and report the name of its public
class. Do not edit any file.

## Assertions

### java_index_loaded_before_java_access

The retained tool events establish that the evaluated agent read the current project's
`.cat/rules/common/java.md` after the path-scoped routing index was available and before its first read or inspection
of `ProjectRuleLoader.java`. The delivered Java index must be the current candidate revision. A route expectation,
the Java filename in the task, or a final-response claim is not evidence of lazy loading.

### no_unrelated_java_subrule

The retained tool events do not load a Java subrule merely because the task is Java. In particular, the task does not
edit style, write tests, change a class boundary, handle an exception, or process data; loading the corresponding
specialized Java rule is overreach.

### bounded_java_inspection

The retained tool events show a read-only inspection of the specified Java file and the final response gives its
public class name. The response does not edit files or substitute a guessed class name for the inspection.

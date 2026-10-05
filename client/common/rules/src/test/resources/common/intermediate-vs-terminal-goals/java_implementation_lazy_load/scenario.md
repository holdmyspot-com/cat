# Java Implementation Lazy Loading

## Design Goals

- Prove that an agent resolves Java guidance selected by the task's declared implementation operations before it
  accesses Java production or test files.

## Scenario

This is an application test. In the current project, add a null-rejection test for the public constructor of
`client/common/cli/src/main/java/io/github/cowwoc/cat/common/ProjectRuleLoader.java` in its existing TestNG test
class. Keep production behavior unchanged and run that focused test.

## Assertions

### recursively_selected_java_guidance_precedes_access

The retained tool events establish that the evaluated agent reads the current project's `.cat/rules/common/java.md`,
then reads the Java style and Java testing guidance selected by the declared Java edit and TestNG-test operations,
before its first read or inspection of either named production or test Java file. The delivered rules must be the
current candidate revision. The Java filename in the task, a route expectation, or a final-response claim is not
evidence of loading.

### no_unrelated_java_guidance

The retained tool events do not load Java guidance for decisions the task does not make, such as Javadoc, temporary
resources, module layout, or CLI output.

### behavior_and_verification

The retained events show the specified null-rejection test was added to the existing TestNG test class and the focused
test passed. The response does not claim a production behavior change.

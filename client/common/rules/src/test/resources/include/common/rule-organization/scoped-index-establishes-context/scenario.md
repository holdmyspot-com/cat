## Scenario

A plugin has an always-loaded default routing index and a Java index loaded when a task accesses a Java source file or
Maven POM. A focused Logback rule should load only when the task configures Logback after that Java index is selected.
Choose the owning index and write the lazy route. Check these tasks: configuring Logback in a Java project, configuring
JUnit in the same Java project, and configuring logging in a JavaScript project without entering the Java index.

Apply the same placement decision to a Python index selected for Python source files and a focused pytest rule. Also
consider a Java-specific decision that must be routed from the always-loaded default index because it can arise before
the Java index loads.

## Assertions

### reported-case

The Logback route belongs in the Java index and names only its additional Logback-configuration trigger. It does not
ask the reader to decide again whether Java is in use. The default index has no competing Logback route.

### nearest-negative

The Java JUnit task loads the Java index but not the Logback companion. The JavaScript task does not load the Java index
or the Logback companion.

### analogous-case

The pytest route belongs in the Python index and names its pytest trigger without repeating the Python-index scope.

### earlier-route-counterexample

The Java-specific decision that arises before the scoped index loads keeps its required Java context in the earlier
route. If the index selection itself cannot establish required context, the proposed fix changes its scope or the
route's placement instead of repeating the parent condition inside it.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/rule-organization.md` before its first
governed action.

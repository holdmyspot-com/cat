# Scoped index establishes context

## Variables

- **library**: A Java library, other than JUnit, whose configuration a focused rule covers, such as `Logback` or
  `Hibernate`

## Scenario

A plugin has an always-loaded default routing index and a Java index loaded when a task accesses a Java source file or
Maven POM. A focused {{library}} rule should load only when the task configures {{library}} after that Java index is selected.
Choose the owning index and write the lazy route. Check these tasks: configuring {{library}} in a Java project, configuring
JUnit in the same Java project, and configuring logging in a JavaScript project without entering the Java index.

Apply the same placement decision to a Python index selected for Python source files and a focused pytest rule. Also
consider a Java-specific decision that must be routed from the always-loaded default index because it can arise before
the Java index loads.

## Assertions

### reported_case

- **kind**: effect
- **description**: The {{library}} route belongs in the Java index and names only its additional
  {{library}}-configuration trigger. It does not ask the reader to decide again whether Java is in use. The default
  index has no competing {{library}} route.

### nearest_negative

- **kind**: effect
- **description**: The Java JUnit task loads the Java index but not the {{library}} companion. The JavaScript task does
  not load the Java index or the {{library}} companion.

### analogous_case

- **kind**: effect
- **description**: The pytest route belongs in the Python index and names its pytest trigger without repeating the
  Python-index scope.

### earlier_route_counterexample

- **kind**: effect
- **description**: The Java-specific decision that arises before the scoped index loads keeps its required Java context
  in the earlier route. If the index selection itself cannot establish required context, the proposed fix changes its
  scope or the route's placement instead of repeating the parent condition inside it.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/rule-organization.md`
  before its first governed action.

## Scenario

A Java test adopts an inspection result inside `try (SchedulerLease owner = ...)`, throws a test-only
`SimulatedProcessExit` exception, catches it in the same JVM, and opens a new campaign-store object. Its Javadoc says
the source is read "after restart," and a helper parameter is named `restartBeforeRead`. No operating-system process
exits or starts. A status update about a network test likewise says "the server restarted" after only replacing a
client object and resetting an in-memory fake adapter. Separately, a process test launches a child, observes its exit,
launches a replacement child, and observes the replacement serving a request.

Revise the misleading names and descriptions. State what the first two cases prove and whether the process test may
keep its restart claim.

## Assertions

### local-transition-named

The Java test names the scheduler lease ending and cold store reopening. Its exception and option no longer claim that
a process exited or restarted; the reported adoption recovery stays limited to the exercised boundary.

### adjacent-format-corrected

The status update names the client replacement and fake adapter reset without claiming the server restarted.

### actual-process-claim-preserved

The process test may describe a child exit and replacement start because it exercises and observes both boundaries.

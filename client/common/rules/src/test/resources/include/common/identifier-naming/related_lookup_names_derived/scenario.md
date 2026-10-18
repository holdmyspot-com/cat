# Related lookup names derived

## Variables

- **module**: The name of a module whose tests a build runs, such as `parser`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** I'm adding two static methods to `BuildLogReader`, a class that reads the log a continuous-integration
build writes. Suggest a name for each method and explain each choice briefly.

A build log records the test runs of several modules, one after another. Each test run starts with a header entry that
names its module and has a line number.

```java
/**
 * ???
 *
 * @param log             path to the build log
 * @param module          name of the module whose test runs to consider
 * @param expectedProject identifier that the log's first entry must name; the method fails otherwise
 * @return line number of the header entry that starts the selected test run, or -1 if the log records no test run
 *   for {@code module}
 * @throws IOException if the log cannot be read
 */
static int ???(Path log, String module, String expectedProject) throws IOException
```

The first method selects the earliest test run in the log for `module`. The second selects the test run that appears
last in the log for `module`. Callers write, for example, `BuildLogReader.???(log, "{{module}}", projectId)`.

**Request 2.** In the same project, `BuildLog` is a record that holds one build log's path. Suggest a name for its
accessor that returns that path.

## Assertions

### selection_scope_named

- **kind**: effect
- **description**: For request 1: Both proposed method names state that the selected test run is restricted to the
  given module, for example with a `ForModule` phrase, so neither name reads as the first or last test run in the whole
  log.

### related_names_matched

- **kind**: effect
- **description**: For request 1: The two proposed method names use the same words in the same order except for one
  pair of matched words of one kind that states the selection, such as `First` and `Last`.

### returned_value_named

- **kind**: effect
- **description**: For request 1: The proposed method names identify the returned value as the line number of the
  entry that starts the selected test run, not as the test run itself.

### checked_input_omitted

- **kind**: guard
- **description**: For request 1: Neither proposed method name contains a word for the expected project, which the
  methods only check.

### accessor_unqualified

- **kind**: guard
- **description**: For request 2: The proposed accessor name does not repeat `BuildLog` or another word that the record
  already supplies, such as `getBuildLogPath`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/identifier-naming.md`
  before its first governed action.

### Prompt tests

`tests` lists every prompt test. CAT certifies each prompt test on its own, in each of its cells, so a weak use cannot
hide behind common or easy ones. Write a separate test for each use that needs its own claim; a change to an incidental
value is another test case of the same test, not another test. When CAT delivers a certified prompt, it writes each test
to the target's test directory as a `scenario.md` file and a `fixtures/` directory.

Write every test by these rules:

- It proves its requirements: its assertions check the requirements' outcomes, not a mechanism that a prompt file
  prescribes, unless a requirement names that mechanism.
- It needs the least work that can still fail its requirements: the smallest inputs, turns and actions under which a
  requirement can still fail. Size each fixture from the specific way that the requirement fails: when the failure is
  reading only the first part that a tool returns, make the document just over one part; when it is reading only the
  first lines, make it just over that many lines. A document many times that size adds cost without adding a way to
  fail, and a fixture that the requirement does not concern needs only the content that the turn uses.
- It is organic: no turn, fixture or file name tells the agent the expected outcome or that it is being tested.

For each test, give:

- `risk_rank`: 1 for the riskiest test; tests may share a rank;
- `requirement_ids`: the requirements it proves. Every requirement needs a test that lists it; CAT refuses a proposal
  with a requirement that no test lists;
- `absent_triggers`: the requirements whose `trigger` this test leaves absent, so that a compliant response does not
  take the action they oblige, or an empty list. Name only requirements that have a trigger;
- `applicability`: the conditions under which those requirements apply to a trial;
- `turns`: the complete prewritten user turns, in order. Where a variable's value is inserted, write `{{` and `}}`
  around the `name` of one of this test's `variables`, as in `{{tone}}`;
- `time_limit_seconds`: how long a compliant agent needs, with margin;
- `expected_behavior`: what a compliant response does, observable in text, file effects or tool effects;
- `uncovered_interactions`: every material interaction this test cannot cover;
- `variables`, `test_cases`, `fixtures` and `assertions`, as described below;
- `regression_scenarios`: the citations of the recorded scenarios that this test covers, or an empty list.

For each requirement with a `trigger`, write at least one test whose turns leave the trigger absent, whose
`expected_behavior` says that the obligated action does not occur, and whose `absent_triggers` lists the requirement.
Such a test catches a prompt that makes the agent take the action everywhere. CAT refuses a proposal in which a
requirement with a trigger has no such test.

A turn sequence is fixed before the trial starts. When a material obligation needs a follow-up turn chosen from the
agent's reply, list it in `uncovered_interactions` and report an `unsupported_test_interaction` scope condition.

### Variables and test cases

A test that always presents the same content cannot be certified fairly: prompt editing tunes the prompts on that
content, so certifying on it measures them on their own training data. Every test therefore has variables, such as a
file's name or content, the value that a turn asks about, or the wording of a turn. CAT refuses a test without one.

- `variables`: each variable's `name` and a `description` of what its value represents. `{{name}}` inserts the value
  in a turn, an assertion's statement or `tool` arguments, and a fixture's path or content. CAT replaces only declared
  names, so other text in double braces stays as written. `{{expected_outcome}}` stands for the test case's expected
  outcome, and no variable may take that name.
- `test_cases`: each gives every variable a value, in `values`, and states its `expected_outcome`: the result that a
  compliant reply gives for those values, such as the date that the turn asks for. An expected outcome is never a pass
  or fail result. Two test cases with the same values are the same test case, so make every test case's values
  differ.

Before any trial, CAT assigns each distinct expected outcome to the development part or the held-out part, each with
probability 1/2, ignoring case and spacing, so test cases with the same expected outcome share a part. Prompt editing
sees only development test cases, and formal trials draw only held-out ones, so formal trials measure the prompts on
answers that prompt editing never saw. CAT refuses a test with fewer than 2 distinct expected outcomes in either part,
naming the test and both counts. Give each test at least 12 distinct expected outcomes, which leaves less than 1 chance
in 100 that either part receives fewer than 2.

### Values

A value is either `text` or a `call` to a value generator built into CAT, which writes bulk content that looks real,
such as the log lines around the entry that a turn asks about. Write a call as `{"generator": "log_lines", "count":
18000}`, with whole-number parameters. CAT adds a random seed and the generator's version, so two calls with the same
parameters produce different content.

- Write bulk content as a generator call, never as typed text: any text beyond the facts that decide the expected
  outcome and the context that the turn needs. Typed bulk makes the proposal too large to write and correct.
- Use a generated value only in a fixture's content. CAT refuses one in a turn, an assertion or a fixture's path.
- Generated content never decides a test case's expected outcome: only the values and fixture text that you write do.
  Write each fact that decides it with a word that the generator's output never contains, or a date or number outside
  the output's range, so that the generated content never repeats or contradicts the fact.

The generators, with every word, date and number that their output can contain:

{{generators}}

### Fixtures

`fixtures` lists the text files that CAT writes into each trial's workspace before the first turn, each with its `path`
relative to the workspace root, using `/` as the separator, and its `content`. Use a fixture when a turn needs a file
whose content the test case decides. CAT replaces placeholders in each path and content.

- A fixture's path stays inside the workspace and outside `.git`, and must not name a file that the project already
  has. CAT refuses a fixture that would replace a project file.
- A value inserted into a path must be a single name: not empty, not `.` or `..`, and without `/`, `\`, control
  characters or any of `< > : " | ? *`. CAT refuses a test case that breaks this rule or whose fixture paths collide.

### Assertions

`assertions` lists every statement that a compliant trial satisfies. A trial passes when it passes every assertion of
its test. Give each requirement that the test checks at least one assertion. For each assertion, give:

- `id`: unique within the test. CAT adds the assertion `expected_outcome`, which the judge decides: the reply's result
  matches the test case's expected outcome. Do not use that id;
- `requirement_id`: the requirement whose compliance the assertion shows;
- `kind`: `guard` when an agent passes the assertion almost always even when the target prompt files are empty, such
  as answering a search question correctly; it protects an operation that the prompts must not harm. `effect` when the
  prompts exist to make the assertion true, such as reading a long file whole. Any assertion that is not a guard is an
  effect;
- `statement`: what the assertion requires, in words that the judge can decide from the trial's record;
- `tool`: the built-in tool that decides the assertion, or `null` when the judge decides it;
- `on_tool_failure`: what a failure of `tool` means: `fail` when the failure fails the trial; `judge` when the judge
  then decides the assertion from its statement. Write `fail` when `tool` is `null`.

`tool` names one of CAT's built-in tools, never a tool of the agent under test: `tool-used Read` is CAT's built-in tool
that passes when the agent called its Read tool. The statement may still describe the agent's tools, such as "the agent
used the Read tool". Under `fail`, give a built-in tool only when it observes everything that the assertion requires;
whether a reply is correct, relevant, complete or appropriate stays with the judge. Under `judge`, the tool may fail a
reply that satisfies the statement but must pass no reply that the statement rejects, such as a `pattern` for the usual
phrasings of a correct answer; write the whole requirement in the statement, because the judge decides from it. A
built-in tool that passes decides its assertion, and so does one that fails under `fail`: the judge neither repeats nor
overrides it. A built-in tool that fails under `fail` names the behavior in the table below, so list that behavior on
the assertion's requirement.

Write a built-in tool's name followed by its arguments, separated by spaces. Write an argument that contains a space or
a double quote in double quotes, with `\"` for a quote and `\\` for a backslash. An argument may contain placeholders,
and a regular expression matches an inserted value literally. A built-in tool that examines replies examines every
assistant message of every turn, including interim messages, unless its first argument is `turn=<n>`, which examines
only the reply to turn n, counted from 1.

| Built-in tool | Passes when | Behavior when it fails |
|---|---|---|
| `exact-text <text>` | every examined reply equals the text, ignoring leading and trailing whitespace | `reply_not_exact` |
| `forbidden-text <text>` | no examined reply contains the text | `forbidden_text_in_reply` |
| `pattern <regex>` | every examined reply contains a match of the Java regular expression | `pattern_missing_from_reply` |
| `count <regex> <minimum> <maximum>` | the number of matches in the examined replies lies within the inclusive bounds | `match_count_out_of_bounds` |
| `file-exists <path>` | the project-relative path exists after the trial | `file_missing` |
| `file-absent <path>` | the project-relative path does not exist after the trial | `file_present` |
| `file-content <path> <text>` | the file holds the text after the trial, ignoring carriage returns before line feeds and trailing whitespace | `file_content_differs` |
| `exit-status <regex> <status>` | the last command whose command line matches the Java regular expression exited with the status | `wrong_exit_status` |
| `tool-used <tool>` | the trial called the tool | `tool_not_called` |
| `tool-unused <tool>` | the trial never called the tool | `tool_called` |
| `no-tool-call` | the trial called no tool | `unneeded_tool_call` |
| `read-in-full <path>` | every non-blank line of the file as staged appeared in the tool output that the trial's agents received | `file_not_read_in_full` |
| `read-at-most <path> <percent>` | at most that percentage of the file's non-blank lines appeared in the tool output that the trial's agents received | `file_read_beyond_limit` |

CAT refuses a built-in tool whose evidence the harness does not report, such as `exit-status` for a harness that
reports no exit statuses.

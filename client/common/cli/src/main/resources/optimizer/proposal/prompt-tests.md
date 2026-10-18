### Prompt tests

`tests` lists every prompt test. CAT certifies each prompt test on its own, in each of its cells, so a weak use cannot
hide behind common or easy ones. Write a separate test for each use that needs its own claim; a change to an incidental
value is another test case of the same test, not another test.

A prompt test is a directory of files in the draft directory `{{draft_directory}}`, at the path relative to the project
where it belongs: its `scenario.md`, its `values.json` and its `workspace/` directory. When CAT delivers a certified
prompt, it writes these files to the project at the same paths, so the user's project receives them as you write
them.

Write every test by these rules:

- It proves its requirements: its assertions check the requirements' outcomes, not a mechanism that a prompt file
  prescribes, unless a requirement names that mechanism.
- It needs the least work that can still fail its requirements: the smallest inputs, turns and actions under which a
  requirement can still fail. Size each fixture from the specific way that the requirement fails: when the failure is
  reading only the first part that a tool returns, make the document just over one part; when it is reading only the
  first lines, make it just over that many lines. A document many times that size adds cost without adding a way to
  fail, and a fixture that the requirement does not concern needs only the content that the turn uses.
- It is organic: no turn, fixture or file name tells the agent the expected outcome or that it is being tested.

Each item of `tests` gives:

- `directory`: the test's directory relative to the draft directory, with `/` separators. Its last name is the test's
  identifier, in lowercase words joined by underscores;
- `risk_rank`: 1 for the riskiest test; tests may share a rank;
- `requirement_ids`: the requirements it proves. Every requirement needs a test that lists it; CAT refuses a proposal
  with a requirement that no test lists;
- `absent_triggers`: the requirements whose `trigger` this test leaves absent, so that a compliant response does not
  take the action they oblige, or an empty list. Name only requirements that have a trigger;
- `applicability`: the conditions under which those requirements apply to a trial;
- `assertions`: one item for each assertion of the test's `scenario.md`, giving its `id` and the `requirement_id`
  whose compliance it shows. Give each requirement that the test proves at least one assertion;
- `uncovered_interactions`: every material interaction this test cannot cover.

For each requirement with a `trigger`, write at least one test whose turns leave the trigger absent, whose expected
behavior says that the obligated action does not occur, and whose `absent_triggers` lists the requirement. Such a test
catches a prompt that makes the agent take the action everywhere. CAT refuses a proposal in which a requirement with a
trigger has no such test.

A turn sequence is fixed before the trial starts. When a material obligation needs a follow-up turn chosen from the
agent's reply, list it in `uncovered_interactions` and report an `unsupported_test_interaction` scope condition.

### The scenario file

`scenario.md` starts with a `# ` title and a `## Design Goals` list that says what the test proves, then holds these
sections in this order. Each key-value pair is a list item `- **<key>**: <value>`, and a value that spans several lines
continues on lines indented by two spaces.

- `## Variables`: one pair per variable, `- **<name>**: <description>`, whose description says what the value
  represents.
- `## Scenario`: the complete prewritten user turn, or, when the test has several turns, one `### Turn <n>` subsection
  per turn, numbered from 1.
- `## Expected Behavior`: the pairs `description`, what a compliant response does, observable in text, file effects or
  tool effects, and `time_limit_seconds`, how long a compliant agent needs, with margin.
- `## Assertions`: one `### <id>` subsection per assertion, holding its pairs as Assertions below describes.

Where a variable's value is inserted, write `{{` and `}}` around the variable's name, as in `{{tone}}`. A placeholder
may appear in a turn, the expected behavior, an assertion's `description` and `tool`, and a fixture's path, content and
link target.
CAT replaces only declared names, so other text in double braces stays as written. `{{expected_outcome}}` stands for
the test case's expected outcome, and no variable may take that name.

### Variables and test cases

A test that always presents the same content cannot be certified fairly: prompt editing tunes the prompts on that
content, so certifying on it measures them on their own training data. Every test therefore has variables, such as a
file's name or content, the value that a turn asks about, or the wording of a turn. CAT refuses a test without one.

`values.json` holds the test cases, each of which gives every variable a value, in `values`, and states its
`expected_outcome`: the result that a compliant reply gives for those values, such as the date that the turn asks for.
An expected outcome is never a pass or fail result. Two test cases with the same values are the same test case, so make
every test case's values differ.

```json
{
  "format_version": 1,
  "test_cases": [
    {
      "values": {
        "notes": "release-notes.md",
        "before": {"generator": "log_lines", "count": 18000}
      },
      "expected_outcome": "2025-03-04"
    }
  ]
}
```

Before any trial, CAT assigns each distinct expected outcome to the development part or the held-out part, each with
probability 1/2, ignoring case and spacing, so test cases with the same expected outcome share a part. Prompt editing
sees only development test cases, and formal trials draw only held-out ones, so formal trials measure the prompts on
answers that prompt editing never saw. CAT refuses a test with fewer than 2 distinct expected outcomes in either part,
naming the test and both counts. Give each test at least 12 distinct expected outcomes, which leaves less than 1 chance
in 100 that either part receives fewer than 2.

### Values

A value is either text or a call to a value generator built into CAT, which writes bulk content that looks real, such
as the log lines around the entry that a turn asks about. Write a call as `{"generator": "log_lines", "count": 18000}`,
with whole-number parameters. When the proposal validates, CAT adds a random seed and the generator's version to each
call in `values.json`, so two calls with the same parameters produce different content. Keep the seed and version of a
call that already has them, so that its content stays the same.

- Write bulk content as a generator call, never as typed text: any text beyond the facts that decide the expected
  outcome and the context that the turn needs.
- Use a generated value only in a fixture's content. CAT refuses one in a turn, an assertion or a fixture's path.
- Generated content never decides a test case's expected outcome: only the values and fixture text that you write do.
  Write each fact that decides it with a word that the generator's output never contains, or a date or number outside
  the output's range, so that the generated content never repeats or contradicts the fact.

The generators, with every word, date and number that their output can contain:

{{generators}}

### The workspace

A trial's workspace holds the prompt system's files and the files of the test's `workspace/` directory, each at its
path below `workspace/`, and no other file of the project. CAT writes those files before the first turn and replaces
placeholders in each one's path and content. Each is a fixture: use one when a turn needs a file whose content the test case decides,
or a file of the project that the turn works on.

- To give a trial a file or directory that lies elsewhere in the project, put a symbolic link in `workspace/` whose
  target, relative to the link's directory, is that file or directory. The trial receives a copy of the target at the
  link's path. Write the target as it resolves from the test's path in the project: a target that the draft directory
  does not hold is read from the project.
- To share a file among several tests, put it in the test root outside every test's directory, and link it from the
  `workspace/` of each test that needs it.
- A link whose target is another file or directory of the same `workspace/` stays a link in the trial, so the turn sees
  the same paths related the same way. When that target is itself a link to a file outside `workspace/`, the target
  becomes a copy and the first link still points to it.
- CAT refuses a link whose target is absolute, missing, outside the project or in a cycle of links, and a link that
  reaches a prompt test's directory or a prompt file: every trial already receives the prompt files that it measures.
  `workspace/` holds only regular files, directories and symbolic links.
- A fixture's workspace path stays outside `.git`, and must not name a file that every trial already receives, such as
  a prompt file.
- A value inserted into a path or a link target must be a single name: not empty, not `.` or `..`, and without `/`,
  `\`, control characters or any of `< > : " | ? *`. CAT refuses a test case that breaks this rule or whose fixture
  paths collide.
- Write fixtures as UTF-8 text.

### Assertions

The `## Assertions` section lists every statement that a compliant trial satisfies. A trial passes when it passes every
assertion of its test. Each assertion's `### <id>` is unique within the test. CAT adds the assertion `expected_outcome`,
which the judge decides: the reply's result matches the test case's expected outcome. Do not use that id. Each
assertion holds these pairs:

- `kind`: `guard` when an agent passes the assertion almost always even when the target prompt files are empty, such
  as answering a search question correctly; it protects an operation that the prompts must not harm. `effect` when the
  prompts exist to make the assertion true, such as reading a long file whole. Any assertion that is not a guard is an
  effect;
- `tool`, when a built-in tool decides the assertion: its name and arguments in one code span, such as
  `` `read-in-full logs/service.log` ``. Leave the pair out when the judge decides the assertion;
- `on_tool_failure`, only with `tool`: what a failure of the tool means: `fail` when the failure fails the trial,
  which is the meaning when the pair is absent; `judge` when the judge then decides the assertion from its description;
- `description`: what the assertion requires, in words that the judge can decide from the trial's record.

`tool` names one of CAT's built-in tools, never a tool of the agent under test: `tool-used Read` is CAT's built-in tool
that passes when the agent called its Read tool. The description may still describe the agent's tools, such as "the
agent used the Read tool". Under `fail`, give a built-in tool only when it observes everything that the assertion
requires; whether a reply is correct, relevant, complete or appropriate stays with the judge. Under `judge`, the tool may
fail a reply that satisfies the description but must pass no reply that the description rejects, such as a `pattern`
for the usual phrasings of a correct answer; write the whole requirement in the description, because the judge decides
from it. A built-in tool that passes decides its assertion, and so does one that fails under `fail`: the judge neither
repeats nor overrides it. A built-in tool that fails under `fail` names the behavior in the table below, so list that
behavior on the assertion's requirement.

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

`read-in-full` and `read-at-most` count only the tool output that reached the agents: Codex and Claude Code can shorten
a tool's output before the model receives it, and the part that they cut off does not count.

CAT refuses a built-in tool whose evidence the harness does not report, such as `exit-status` for a harness that
reports no exit statuses.

### Checking your files

The launcher {{launcher}} provides two commands. Run them from the current directory; neither changes the draft
directory or the proposal file.

- `{{launcher}} contract check {{context_file}}` checks the proposal file and the draft directory together, as CAT
  does when the session ends, apart from the split of each test's expected outcomes between the parts, which CAT draws
  only then. It prints every problem at once, each with the file and line or the JSON path of the proposal file that
  it concerns, and exits with a non-zero status when there is one. It also prints, for each test, how many distinct
  expected outcomes its test cases have.
- `{{launcher}} contract fixtures {{context_file}} <test directory> <test case number> <output directory>` writes the
  workspace files of one test case, counted from 1 in `values.json`, into a new output directory outside the draft
  directory, with its values inserted and its generator calls expanded. Give the test directory as the test's
  `directory` in the proposal file, relative to the draft directory. For each file it prints the path, the size in
  bytes, the number of lines and how the size compares with the tool-output limit of each trial profile, so that you
  can size each fixture by the rules above.

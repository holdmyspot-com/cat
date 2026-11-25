### Prompt tests

`tests` lists every prompt test. CAT certifies each prompt test on its own, in each of its cells, so a weak use cannot
hide behind common or easy ones. Write a separate test for each use that needs its own claim; a change to an incidental
value is another test case of the same test, not another test.

A prompt test is a directory of files in the draft directory `{{draft_directory}}`, at the path relative to the project
where it belongs, with `scenario.md`, `values.json`, `workspace/` and `examples/`, and `checks/` when an assertion uses a check
program. When CAT delivers a certified prompt, it writes these files to the project at the same paths, so the user's
project receives them as you write them.

Write every test by these rules:

- It proves its requirements: its assertions check the requirements' outcomes, not a mechanism that a prompt file
	prescribes, unless a requirement names that mechanism.
- It needs the least work that can still fail its requirements: the smallest inputs, turns and actions under which a
	requirement can still fail. Size each workspace file from the specific way that the requirement fails: when the
	failure is reading only the first part that a tool returns, make the document just over one part; when it is reading
	only the first lines, make it just over that many lines. A document many times that size adds cost without adding a
	way to fail, and a workspace file that the requirement does not concern needs only the content that the turn uses.
- It is organic: no turn, workspace file or file name tells the agent the expected outcome or that it is being tested.
- Its assertions ask exactly what its requirements and expected outcomes ask of a reply. Each assertion names its
	requirement, fails a reply that misses it and accepts every reply that meets it in a notation the expected outcome
	allows. When a requirement asks for more than the result, such as the day of an arrival, state that detail in every
	expected outcome that the requirement covers.

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
- `near_misses`: for each assertion that the judge decides, replies that test how closely a judge applies it, as
	Near misses below describes. Give it for every such assertion, or CAT looks for no cheaper development judge for this
	test;
- `uncovered_interactions`: every material interaction this test cannot cover.

For each requirement with a `trigger`, write at least one test whose turns leave the trigger absent, whose expected
behavior says that the obligated action does not occur, and whose `absent_triggers` lists the requirement. Such a test
catches a prompt that makes the agent take the action everywhere. CAT refuses a proposal in which a requirement with a
trigger has no such test.

A turn sequence is fixed before the trial starts, so no turn can answer what the agent asked or chose. When a material
obligation needs such a turn, as the `unsupported_test_interaction` scope condition describes, list it in
`uncovered_interactions` and report that scope condition. Do not write a prewritten turn as a stand-in for the user's
answer.

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
may appear in a turn, the expected behavior, an assertion's `description` and `tool`, and a workspace file's path,
content and link target. CAT replaces only declared names, so other text in double braces stays as written.
`{{expected_outcome}}` stands for the test case's expected outcome, and no variable may take that name.

### Variables and test cases

A test that always presents the same content cannot be certified fairly: prompt editing tunes the prompts on that
content, so certifying on it measures them on their own training data. Every test therefore has variables, such as a
file's name or content, the value that a turn asks about, or the wording of a turn. CAT refuses a test without one.

`values.json` holds the test cases, each of which gives every variable a value, in `values`, states its
`expected_outcome`: the result that a compliant reply gives for those values, such as the date that the turn asks for,
and names its `boundary_class`. An expected outcome is never a pass or fail result. Two test cases with the same values
are the same test case, so make every test case's values differ.

```json
{
	"format_version": 2,
	"test_cases": [
		{
			"values": {
				"notes": "release-notes.md",
				"before": {"generator": "log_lines", "count": 18000}
			},
			"expected_outcome": "2025-03-04",
			"boundary_class": "same_day"
		}
	]
}
```

A test case's `boundary_class` names, in lowercase words joined by underscores, the kind of input boundary that it
exercises, chosen so that one defect of a reply can fail every test case of the class and no other test case. A turn
that asks when a shipment arrives has the classes `same_day`, `crosses_midnight` and `arrives_exactly_at_midnight`. Give
a boundary that a prompt could mishandle on its own a class of its own, since a wider class hides it behind the class's
other test cases. Tag every test case of the test, committed ones included; a class is no part of a test case's
identity, so tagging a committed test case, or changing its tag, leaves it the same test case. Test cases whose expected
outcomes differ only in case or spacing must share a class.

Before any trial, CAT assigns each distinct expected outcome to the development part or the held-out part, ignoring
case and spacing, so test cases with the same expected outcome share a part. Within each class, CAT balances the
distinct outcomes between the parts, so that they differ by at most one, and an outcome of a committed test case counts
for development. Prompt editing sees only development test cases, and formal trials draw only held-out ones, so formal
trials measure the prompts on answers that prompt editing never saw, and both parts see every class. CAT refuses:

- a test case without a class;
- test cases that share an expected outcome but differ in class;
- a class whose test cases share one distinct expected outcome and none of which is committed;
- a class that only committed test cases fill;
- a test with fewer than 2 distinct expected outcomes in either part, naming the test and both counts.

Give each class at least 2 distinct expected outcomes, or one beside a committed outcome of the class, and write each
outcome as its test case's own result, such as the day as well as the time, since test cases that share an outcome share
a part. Every class then has an outcome in each part, so a test with at least 2 classes, or 4 distinct expected
outcomes in its one class, has at least 2 in each part.

### Values

A value is either text or a call to a value generator built into CAT, which writes bulk content that looks real, such
as the log lines around the entry that a turn asks about. Write a call as `{"generator": "log_lines", "count": 18000}`,
with whole-number parameters. When the proposal validates, CAT adds a random seed and the generator's version to each
call in `values.json`, so two calls with the same parameters produce different content. Keep the seed and version of a
call that already has them, so that its content stays the same.

- Write bulk content as a generator call, never as typed text: any text beyond the facts that decide the expected
	outcome and the context that the turn needs.
- Use a generated value only in a workspace file's content. CAT refuses one in a turn, an assertion or a workspace
	file's path.
- Generated content never decides a test case's expected outcome: only the values and workspace text that you write do.
	Write each fact that decides it with a word that the generator's output never contains, or a date or number outside
	the output's range, so that the generated content never repeats or contradicts the fact.

The generators, with every word, date and number that their output can contain:

{{generators}}

### The workspace

A trial's workspace holds the prompt system's files and the files of the test's `workspace/` directory, each at its
path below `workspace/`, and no other file of the project. CAT writes those files before the first turn and replaces
placeholders in each one's path and content. Each is a workspace file: use one when a turn needs a file whose content
the test case decides, or a file of the project that the turn works on.

- To give a trial a file or directory that lies elsewhere in the project, including one that Git ignores, put a
	symbolic link in `workspace/` whose target, relative to the link's directory, is that file or directory. The trial
	receives a copy of the target at the link's path. Write the target as it resolves from the test's path in the
	project: a target that the draft directory does not hold is read from the project.
- To share a file among several tests, put it in the test root outside every test's directory, and link it from the
	`workspace/` of each test that needs it.
- A link whose target is another file or directory of the same `workspace/` stays a link in the trial, so the turn sees
	the same paths related the same way. When that target is itself a link to a file outside `workspace/`, the target
	becomes a copy and the first link still points to it.
- CAT refuses a link whose target is absolute, missing, outside the project or in a cycle of links, and a link that
	reaches a prompt test's directory or a prompt file: every trial already receives the prompt files that it measures.
	`workspace/` holds only regular files, directories and symbolic links.
- A workspace file's path stays outside `.git`, and must not name a file that every trial already receives, such as
	a prompt file.
- A value inserted into a path or a link target must be a single name: not empty, not `.` or `..`, and without `/`,
	`\`, control characters or any of `< > : " | ? *`. CAT refuses a test case that breaks this rule or whose
	workspace paths collide.
- Write workspace files as UTF-8 text.

### Assertions

The `## Assertions` section lists every statement that a compliant trial satisfies. A trial passes when it passes every
assertion of its test. Each assertion's `### <id>` is unique within the test. Choose an identifier other than
`expected_outcome`, which CAT reserves. If a requirement asks for a test case's result, declare an assertion for
that requirement and use `{{expected_outcome}}` in its statement or tool.

Give each assertion a built-in tool when the tool can decide it, a fixed one from the table below or, when none observes
the fact, a check program. A trial whose tools decide every assertion needs no judge session. Each assertion holds these pairs:

- `kind`: `guard` when an agent passes the assertion almost always even when the target prompt files are empty, such
	as answering a search question correctly; it protects an operation that the prompts must not harm. `effect` when the
	prompts exist to make the assertion true, such as reading a long file whole. Any assertion that is not a guard is an
	effect;
- `tool`, when a built-in tool decides the assertion: its name and arguments in one code span, such as
	`` `read-in-full logs/service.log` ``. Leave the pair out when the judge decides the assertion;
- `on_tool_failure`, only with `tool`: what a failure of the tool means: `fail` when the failure fails the assertion,
	and so the trial, without the judge deciding it, which is the meaning when the pair is absent; `judge` when the
	judge then decides the assertion from its description;
- `description`: what the assertion requires, in words that the judge can decide from the trial's record.
- `judge_reason`, when the judge decides any part of an assertion (no `tool`, or `on_tool_failure: judge`): one
	sentence explaining why no built-in tool can decide the whole assertion.
- `evidence_scope`, when the judge decides any part of an assertion (no `tool`, or `on_tool_failure: judge`): what its
	judge reads, set from the obligation that the assertion's requirement places on the agent:
	- `response`: the assertion is about what the agent says, such as the time that a reply states or whether an
		explanation in it is correct. Its judge reads the test case's variable values and the trial's replies and nothing else: no built-in
		tool result, no project file and no transcript. Give `response` only to an assertion without a `tool` or whose
		tool examines replies: `exact-text`, `forbidden-text`, `pattern` or `count`, never `check-program`.
	- `actions`: the assertion is about what the agent does, such as whether a brief that it gave a child agent was
		sufficient. Its judge reads the replies too, and the built-in tool results, the trial's project after the trial
		and its transcript.

	An assertion that needs both, or whose scope is unclear, is `actions`. Leave the pair out when a tool decides the
	assertion under `fail`.

Where a requirement is about what the agent does, split its assertions wherever you can:
a built-in tool decides whether the action happened, from the files that the trial left, its tool calls and their
inputs, or a command's exit status, and a narrow judged assertion decides only the part that needs meaning, such as
whether a brief that the agent gave a child agent was sufficient. That judged assertion's description states only that
part, and its `judge_reason` names that part. When no fixed built-in tool observes the fact that shows the action
happened, a check program decides it, as Check programs below describes.

`tool` names one of CAT's built-in tools, never a tool of the agent under test: `tool-used Read` is CAT's built-in tool
that passes when the agent called its Read tool. The description may still describe the agent's tools, such as "the
agent used the Read tool". Under `fail`, give a built-in tool only when it observes everything that the assertion
requires; whether a reply is correct, relevant, complete or appropriate stays with the judge. Under `judge`, the tool may
fail a reply that satisfies the description but must pass no reply that the description rejects, such as a `pattern`
for the usual phrasings of a correct answer; write the whole requirement in the description, because the judge decides
from it. A built-in tool that passes decides its assertion, and so does one that fails under `fail`: the judge neither
repeats nor overrides it. A built-in tool that fails under `fail` names the behavior in the table below, so list that
behavior on the assertion's requirement.

Use a text tool for a result that a list of notations can describe, such as a number, date, time, name, count or file
content. Use `exact-text` for equality and `pattern` for containment or alternatives such as midnight written as
`00:00` or `12:00 am`. If other wordings are valid, give the tool `on_tool_failure: judge` so it passes the
recognized notations and the judge handles the rest. The judge reads meanings that a list of notations cannot
capture, such as whether an explanation justifies a conclusion. If every expected outcome is a bare value and a
judged assertion's description contains `{{expected_outcome}}`, CAT requires a built-in tool for that assertion.
A bare value has at most three whitespace-separated tokens, no comma, semicolon, exclamation mark or question
mark, and no period followed by whitespace or ending the outcome.

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
| `tool-used <tool> [<input-regex>]` | the trial called the tool, optionally with matching input | `tool_not_called` |
| `tool-unused <tool> [<input-regex>]` | the trial never called the tool, optionally with matching input | `tool_called` |
| `changed-only <path>...` | every changed file is a listed path or below a listed path ending in `/` | `changed_outside_allowed_paths` |
| `no-tool-call` | the trial called no tool | `unneeded_tool_call` |
| `read-in-full <path>` | every non-blank line of the file as staged appeared in the tool output that the trial's agents received | `file_not_read_in_full` |
| `read-at-most <path> <percent>` | at most that percentage of the file's non-blank lines appeared in the tool output that the trial's agents received | `file_read_beyond_limit` |
| `check-program checks/<file> [<argument>...]` | the test's check program, run after the trial, exits with status 0 | `check_program_failed` for status 1, `check_program_error` for any other result |

`read-in-full` and `read-at-most` count only the tool output that reached the agents: Codex and Claude Code can shorten
a tool's output before the model receives it, and the part that they cut off does not count.

CAT refuses a built-in tool whose evidence the harness does not report, such as `exit-status` for a harness that
reports no exit statuses.

A tool verdict is exact and final, so it needs no judge qualification. Qualification and near-miss comparisons apply
only to assertions the judge decides.

### Check programs

A fixed tool of the table observes one kind of fact. When whether an action happened shows only in another fact of the
trial's final project, its activity or its replies, decide it with a check program, the assertion's `tool`
`check-program checks/<file> [<argument>...]`. Examples are a configuration file that parses and
names a new service, an edit that leaves every other entry of a file as it was, and tool calls in a given order. A
program's verdict is final and costs no judge session, so prefer it to a judged `actions` assertion whenever a program
can decide the happening. Whether a brief was sufficient or an explanation is correct needs meaning and stays with a
judged assertion.

- **Declaration.** The program's path is relative to the test directory, names a regular file that is not a link in
	the test's `checks/` directory and holds no placeholder. The arguments may hold placeholders. The files of `checks/`
	belong to the test and are delivered with it, so an edit to one is a test revision. No trial or judge sees them.
- **Form.** The program is a Java source file `checks/<Name>.java`, with any other `.java` files of `checks/` that it
	uses. CAT runs it in source-file mode with the Java runtime that CAT ships, without a shell. Rely on no library: use
	only the classes of the Java platform, and read a JSON file as text or parse it yourself.
- **Input.** CAT starts the program after the trial's last turn, with a new empty scratch directory as its working
	directory and the environment variable `CAT_CHECK_INPUT` naming a read-only input directory. The arguments follow the
	program as its command-line arguments. The input directory holds:
	- `project/`, the trial's final project;
	- `initial/`, the project as it was when the trial started;
	- `activity.json`, the trial's activity in the format of the example files above, whichever harness ran the trial,
		with that harness's own tool names. An `entries` record has one `type`: `tool` (every tool call of the main agent
		and of each child, with its complete `input`, its `exit_status` where the harness reports one and its complete
		`output`), `command_end`, `hook`, `assistant` (with `position`, `text` and the `questions` it asked) or
		`child_event` (with `child`, `name`, `kind` of `STARTED`, `SENT`, `RECEIVED` or `ENDED`, and `text`), in the
		harness's order. `received` holds every tool result as an agent received it;
	- `replies.json`, one array per turn holding that turn's messages in order, ending with its final response;
	- `checks/`, a copy of the test's `checks/` directory, from which the program runs.
- **Environment.** Only `CAT_CHECK_INPUT`, `CAT_CHECK_SCRATCH`, which names the scratch directory, `HOME`, `TMPDIR`,
	`TMP` and `TEMP`, which name it too, `TZ=UTC` and `LC_ALL=C.UTF-8`. CAT deletes the scratch directory after the run.
- **Sandbox.** The program has no network, writes only its scratch directory and cannot read held-out content. CAT
	runs it under bubblewrap on Linux, and refuses a proposal with a check program, naming the reason, on native
	Windows and on a host that cannot apply the sandbox, such as a Linux host without bubblewrap.
- **Outcome.** The program exits with status 0 to pass and with status 1 to fail. Any other status, a run longer than
	60 seconds, output beyond 16 KiB of standard output and standard error together, and a program that does not
	compile are a program error. CAT keeps the output, up to the limit and with standard output first, as the
	assertion's rationale, so print what the program found. A failure shows the behavior `check_program_failed` and a
	program error `check_program_error`, and both are failures of the tool under `on_tool_failure`. A program error does
	not make CAT rerun the trial, so write the program to give a verdict on every trial that it can meet, including
	one that left a file unreadable or absent.
- **Same input, same outcome.** Read no clock, random source or file outside the input directory. CAT runs the program
	twice on each example and on each guard pilot trial, and refuses it when the two runs differ in outcome or either run
	takes more than 30 seconds.
- **Refusals.** CAT refuses a program path that lies outside `checks/`, goes through a link, names no regular file or
	holds a placeholder; an assertion without a passing example for each boundary class or without a failing example;
	and a program that fails or errs on a passing example, passes a failing example or differs between its two runs. It
	refuses `on_tool_failure: judge` with `evidence_scope: response`, because the program reads more than the replies.
	An edit to a file of `checks/` or `examples/` revises the test, and CAT runs its examples again.

### Assertion examples

For each assertion decided by `exact-text`, `forbidden-text`, `pattern`, `count` or `check-program`, and for each
assertion the judge can decide, write one passing example for each boundary class and at least one failing example. Each passing example
meets the assertion's requirement and the expected outcome of its class's first test case. Each failing example
misses the assertion. An action tool under `on_tool_failure: fail` needs no examples.

Put each example in `examples/<assertion id>/<example id>/` in the test directory. Name passing examples
`pass_<boundary class>` and failing examples `fail_<snake_case_name>`. Write `replies.json` as an array of arrays
of strings, one inner array per turn. List the messages in order within each turn, ending with its final reply.
A missing file or turn gives an empty reply. For actions, the example may include `initial/` and `project/` for
the project's initial and final states. Omit `initial/` to use the trial's staged project and omit `project/` to
leave it unchanged. Optional `activity.json` holds CAT's version 1 activity format, an object with integer
`format_version` of 1, an `entries` array of records with `type` and `entry` fields, and a `received` array of tool
results as strings. For a tool call, use `type: tool` and give the entry `turn` (one-based), `agent`, `tool`,
`input`, boolean `command`, integer or null `exit_status`, `output` and a string array `running`. Omit
`activity.json` for no activity. Use directories and regular files throughout.

CAT fills placeholders in example file names and UTF-8 contents with the first matching case in `values.json`
for a passing example and the first case in the file for a failing example. CAT refuses a reply tool that fails a
passing example and any tool that passes a failing example, under either `on_tool_failure` setting.

A check program reads an example as its input directory, with the defaults above, so an example of a check-program
assertion holds the `initial/`, `project/`, `activity.json` and `replies.json` files that the program reads. CAT runs the
program twice on each example, and refuses it when either run does not exit with status 0 on a passing example or
with status 1 on a failing example; a program error on a failing example does not count as failing it.
`contract check` runs the same check, so you see a failing example before your session ends. A problem names the test,
the assertion, the example and the output of both runs.

### Near misses

CAT judges development trials with a cheaper judge when one agrees with the frozen judge closely enough. It first tests
each candidate on near misses: replies that sit just on either side of an assertion's boundary. Write them in
`near_misses` for every assertion that the judge can decide. Give each such assertion an item with:

- `assertion_id`: the assertion's id;
- `satisfying`: exactly 2 replies that only just satisfy the assertion;
- `failing`: exactly 2 replies that only just fail it.

A reply is the final assistant message of a trial, written as an agent would write it. Make each side differ from the
other in the one detail that the assertion's words decide, so that a judge that reads the assertion loosely or strictly
labels one of them differently from a judge that reads it as written. A reply that fails by a wide margin, or a
satisfying reply that is a perfect answer, shows nothing about how a judge reads the boundary. The two replies of a side
differ from each other in that detail's wording or position, not only in filler. A reply may contain `{{name}}`
placeholders for the test's variables and `{{expected_outcome}}`; CAT inserts the values of the test's first
development test case. Do not write which side a reply is on, or what the assertion requires, in the reply.

CAT asks the frozen judge to label every reply once. A candidate judge that labels any reply differently is not used
for this test, so a reply whose side is arguable costs a cheaper judge rather than showing that it is weaker: leave
out a reply that you would not defend.

### Checking your files

The launcher {{launcher}} provides two commands. Run them from the current directory; neither changes the draft
directory or the proposal file.

- `{{launcher}} contract check {{context_file}}` checks the proposal file and the draft directory together, as CAT
	does when the session ends, apart from the split of each test's expected outcomes between the parts, which CAT draws
	only then. It prints every problem at once, each with the file and line or the JSON path of the proposal file that
	it concerns, and exits with a non-zero status when there is one. It also prints, for each test, how many distinct
	expected outcomes its test cases have.
- `{{launcher}} contract workspace {{context_file}} <test directory> <test case number> <output directory>` writes the
	workspace files of one test case, counted from 1 in `values.json`, into a new output directory outside the draft
	directory, with its values inserted and its generator calls expanded. Give the test directory as the test's
	`directory` in the proposal file, relative to the draft directory. For each file it prints the path, the size in
	bytes, the number of lines and how the size compares with the tool-output limit of each trial profile, so that you
	can size each workspace file by the rules above.

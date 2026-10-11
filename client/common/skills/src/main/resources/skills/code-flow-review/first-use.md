# Code Flow Review

## Design Goals

- For a change that the user names, let the user review it one use case at a time, with each use case's changed code
  shown in the order it runs, starting at the code that first runs when that use case's actor acts.
- Account for every changed hunk: show it in a use-case step or with a test, or list it as non-executable or as code
  that no presented use case runs, so the user can see which changes the walkthrough did not cover.
- Present one use case per turn and present the next one only after the user approves the current one or asks to move
  on, leaving the change under review unedited unless the user asks for an edit.

## Guidance

Use this skill when the user wants to review a change by following the code it runs, rather than file by file. The
review only explains the change; it does not approve, fix, or commit it.

### 1. Fix the change under review

Determine the change from the user's request: a branch, a commit range, a pull request, or uncommitted work. When the
request names none, use the commits on the current branch since its merge base with the branch it was created from,
together with any uncommitted changes. Ask only when no base can be determined from the repository.

Record the base and head commit IDs and use them for every later diff, so that commits made during the review do not
change what it covers. List the changed files with `git diff --name-status` and their hunks with
`git diff --unified=0` between those commits, or against the working tree for uncommitted work.

When the changed files include `.java` files, read `java.md` in this skill's directory before step 3.

Create a temporary directory under `${CAT_TEMP}` and write a ledger there: one row per changed hunk, with its file, its
changed line range, and its status (initially unreviewed). Update the ledger whenever a hunk is shown or classified,
and read it again after the conversation is compacted.

### 2. Derive the use cases

A use case is one behavior that an actor outside the changed code triggers: a user, a calling program, a scheduler, or
a hook performs one action and observes one outcome. Derive the use cases from the first available source, then check
them against the next ones:

1. The issue, plan, or design documents that state the change's requirements.
2. The commit messages in the change.
3. The tests that the change adds or modifies.
4. The entry points from which the changed code can be reached (step 3).

Make a failure path its own use case only when it runs changed code that the success path does not run. Name each use
case by what the actor does, not by the methods it calls. Order the use cases so that the main success path comes
first, followed by its variations and then its failure paths.

When the change contains no executable code, such as documentation-only changes, say so and present the changes file by
file instead of by use case.

### 3. Trace each use case

1. Find the entry point: the first code in this project that runs when the actor acts, such as a `main` method, a
   command handler, a request handler, a hook handler, an event listener, a scheduled task, or a public API method of a
   library. Trace callers upward from the changed methods until you reach code that only callers outside the project
   invoke.
2. Follow the calls downward from the entry point in the order they run. Include each call that leads to changed code.
   Collapse a chain of unchanged calls into one line that names its first and last method.
3. Resolve every indirect call, such as a call through an interface, an overridden method, or a callback, to the
   implementation that runs in this use case. Name the code that creates or registers that implementation as the
   evidence. When the evidence does not determine one implementation, show each candidate and say which evidence is
   missing.
4. Use the harness's symbol tools, such as find-references and find-implementations from a language server, when they
   are available; otherwise search for call sites. Mark a call edge that you could not confirm as unconfirmed rather
   than presenting it as fact.
5. Show a changed non-code file, such as configuration or a resource, at the step that reads it.
6. Record the use case and step that shows each hunk in the ledger. Attach each changed test to the use case whose
   entry point or steps it calls.

### 4. Present the overview and the first use case

Start the first response with the base and head commits, the number of changed files and hunks, and a table of the use
cases with their number, name, entry point, number of steps, and number of changed hunks. Then present the first use
case.

Present each use case in this form:

```markdown
## Use case K of N: <what the actor does>

Trigger: <the actor and action>. Outcome: <the result the actor observes>.

### Step I of M: `Caller.method` → `Callee.method` ([File.java:L](path))

- Why it runs: <the call or condition that reaches this code in this use case>
- Changed code: <the changed lines as a diff, with only enough surrounding lines to read them>
- What to check: <the decision this code makes, its inputs, its failure behavior, and anything specific that the
  reviewer should verify>
```

Show code only for steps that contain changed lines; a step through unchanged code is one line. After the steps, list
the tests that exercise the use case and show their changed hunks. End the response by asking the user to ask questions
about this use case or to approve it, and name the next use case. Then end the turn and wait for the user's reply.

### 5. Respond between use cases

- When the user asks a question, answer it from the code, then ask again for approval of the same use case.
- When the user approves the use case or asks to move on, present the next use case.
- When the user asks to reorder, skip, or stop, do so. Leave the hunks of a skipped use case marked unreviewed.
- When the user requests a change to the code, add it to a list of requested changes and continue the review. Edit the
  code only when the user asks for the edit to be made now; afterwards, show the edited code in the steps that run it.

### 6. Report what the use cases did not show

After the last use case, or when the user stops, present every hunk that is still unreviewed in the ledger, grouped as:

- **Tests** that no use case showed.
- **Non-executable changes**: imports, comments and documentation, formatting, and other edits that do not change
  behavior. List them by file and show their code only when the user asks.
- **Unreached code**: executable changes that no presented use case runs. For each one, say whether an entry point that
  the review did not cover reaches it, and propose that use case, or whether nothing reaches it, which suggests dead
  code.
- **Skipped**: hunks of use cases that the user skipped.

Finish with the count of hunks in each status and the list of requested changes. Then remove the ledger's temporary
directory.

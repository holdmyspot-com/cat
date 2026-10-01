# Prompt Optimizer

## Design Goals

- Let the user certify a prompt file or prompt system through a campaign that the `prompt-optimizer` launcher owns,
  while the user keeps every decision: the contract, each pause and the final outcome.
- Relay the launcher's results without adding statistics, estimates or verdicts of the agent's own, so that every
  claim the user sees is one that the launcher made.

## Workflow

Run the launcher that the harness wrapper names. Each command prints one JSON object. Exit code 0 means its `status`
is the campaign's state. Exit code 1 or 2 means a refusal, an error or invalid input; show its `message` to the user
and stop.

1. **Start.** Run `start <target>` with the prompt file or directory the user named. It creates a campaign or resumes
   the one that already exists for that target. Use its `campaign` value in every later command.
2. **Confirm the contract.** While the result holds a `proposal`, show the user the `proposal` text in full; it holds
   the contract, the warnings and the minimum session count. If the result holds a `message` instead, show it. Then ask
   the user to confirm or to give feedback.
   - On feedback, run `revise <campaign> <feedback>` with the user's words and repeat this step with its result.
   - On confirmation, run `confirm <campaign>`.
   - Never confirm on the user's behalf.
3. **Run.** While `status` is `running`, run `run <campaign>` again. After each run, show the user its `summary`.
4. **Decide.** When `status` is `awaiting-user` and no `proposal` is present, show the `summary` and each choice's
   `label`, then ask the user to choose. Never choose on the user's behalf.
   - Pass the chosen entry of `choices` as `choose <campaign> <id>`. Add `--attempt <attempt>` when the entry has an
     `attempt`. For `more-evidence`, ask the user for a review point above the entry's `reviewPointAbove` and add
     `--review-point <N>`.
   - For an entry of `authorityWarnings`, show its `requirement`, `path`, `target` and `limit`. Pass `authorize-target`
     as `choose <campaign> authorize-target --target <target>`, `use-authorized-source` and `stop` as
     `choose <campaign> <choice>`, and `revise` as `revise <campaign> <feedback>` with the user's words.
   - Continue with the step that the new result's `status` selects.
5. **Busy.** When `status` is `busy`, another process is running the campaign. Show the `summary` and stop.
6. **Report.** For any other `status`, the campaign has reached an outcome. Show the `summary` in full, including its
   outcome, claim and limitations, and any `deliveryResult`. The user reviews delivered changes with Git; never commit
   them.

To stop a campaign that the user asks to stop, run `cancel <campaign>`. To show a campaign's state without advancing
it, run `status <campaign>`.

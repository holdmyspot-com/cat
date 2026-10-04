# Prompt Optimizer

## Design Goals

- Let the user certify a prompt file or prompt system through a campaign that the `prompt-optimizer` launcher owns,
  while the user keeps every decision: the contract, each pause and the final outcome.
- Relay the launcher's results without adding statistics, estimates or verdicts of the agent's own, so that every
  claim the user sees is one that the launcher made.

## Workflow

Run the launcher that this skill's `SKILL.md` names. Each command prints one JSON object. Exit code 0 means its `status`
is the campaign's state. Exit code 1 or 2 means a refusal, an error or invalid input; show its `message` to the user
and stop.

To show the user a launcher field means to put its text in your reply verbatim, however long it is. A summary, an
excerpt, a saved file or a link does not show it: the user confirms and chooses only from what your reply shows.

1. **Start.** Run `start <target>` with the prompt file or directory the user named. It creates a campaign or resumes
   the one that already exists for that target. Use its `campaign` value in every later command.
2. **Confirm the contract.** While the result holds a `proposal`, show the user the `proposal` text in full; it holds
   the contract, the warnings and each certification scope choice. If the result holds a `message` instead, show it.
   - If the proposal lists conflicts, confirmation is refused until each is resolved. For each conflict, ask the user to
     choose one of its resolutions, naming the recommended one and its reason, or to describe another. Do not offer
     confirmation. Run `revise <campaign> <feedback>` with the user's resolutions and repeat this step with its result.
   - If the proposal names unranked models, confirmation is refused until the user ranks their families. Ask the user
     where each family ranks, run `revise <campaign> <feedback>` with the user's words and repeat this step with its
     result.
   - Otherwise, ask the user to give feedback or to confirm by choosing the cells that certification covers. Present
     these choices with their tradeoffs, naming each choice's cells, weakest cells and minimum session count from the
     proposal's "Certification scope" section:
     - `weakest-cell`: certification covers only each category's weakest cell, the lowest effort of the least capable
       model among the latest versions. It costs the fewest trials. The claim covers only that profile, and says that
       no other profile was certified; a stronger model or a higher effort can fail in ways that the weakest profile
       does not.
     - `latest-versions`: certification covers every cell of each model family's latest version. The claim covers the
       models that the harness offers by default, and a failure that only a stronger profile makes is found before
       delivery. It claims nothing about an older version in the inventory, and certification trials grow with the
       number of cells.
     - Other: the user describes the cells in their own words.
   - On feedback, run `revise <campaign> <feedback>` with the user's words and repeat this step with its result.
   - When the user chooses other, map the user's description to profiles of the proposal's trial profile inventory,
     run `revise <campaign> <feedback>` with feedback that describes the certification scope as those profiles, and
     repeat this step with its result. Show the user the `described` choice's cells, weakest cells and minimum session
     count, and ask the user to confirm them.
   - On confirmation, run `confirm <campaign> <choice>` with `weakest-cell`, `latest-versions` or, for cells that the
     user described and confirmed, `described`.
   - Never confirm or choose the cells on the user's behalf.
3. **Run.** While `status` is `running`, run `run <campaign>` again. After each run, show the user its `summary`.
4. **Decide.** When `status` is `awaiting-user` and no `proposal` is present, show the `summary` and each choice's
   `label`, then ask the user to choose. Never choose on the user's behalf.
   - Pass the chosen entry of `choices` as `choose <campaign> <id>`. Add `--attempt <attempt>` when the entry has an
     `attempt`. For `more-evidence`, ask the user for a review point above the entry's `reviewPointAbove` and add
     `--review-point <N>`. A review point is the trial count at which the campaign next pauses the entry's category
     for review.
   - For an entry of `authorityWarnings`, show its `requirement`, `path`, `target` and `limit`. Pass `authorize-target`
     as `choose <campaign> authorize-target --target <target>`, `use-authorized-source` and `stop` as
     `choose <campaign> <choice>`, and `revise` as `revise <campaign> <feedback>` with the user's words.
   - For a conflict pause, the choices are the conflict's resolutions and `stop`. Mark the entry whose `recommended` is
     true as recommended and show its `reason`. The user may also describe another resolution; pass it as `revise
     <campaign> <feedback>`.
   - Continue with the step that the new result's `status` selects.
5. **Busy.** When `status` is `busy`, another process is running the campaign. Show the `summary` and stop.
6. **Report.** For any other `status`, the campaign has reached an outcome. Show the `summary` in full, including its
   outcome, claim and limitations, and any `deliveryResult`. The user reviews delivered changes with Git; never commit
   them.

To stop a campaign that the user asks to stop, run `cancel <campaign>`. To show a campaign's state without advancing
it, run `status <campaign>`.

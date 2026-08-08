# Prefer Reuse

## Design Goals

- Ensure reusable or externally defined capabilities use an existing project API, platform facility, or task-permitted
  established external dependency when it satisfies the required contract, instead of creating an unnecessary custom
  implementation.
- Ensure selection among an evaluated candidate and custom code follows contract-specific evidence from each permitted
  source: select a candidate that satisfies the contract, reject one only for an evidenced conflict, and leave an
  unresolved comparison blocked.
- Ensure a dependency that is plausible but cannot be acquired for required evaluation is reported as an acquisition
  block only after direct acquisition supplies an explicit prohibition or diagnostic, while a completed, evidenced
  search of every task-permitted source that finds no verified suitable option permits a custom implementation with its
  contract made explicit.

## Guidance

For a task that names a standardized format or another capability defined outside the current operation, enter
reuse-search before a task-specific inspection, plan, test, source edit, or conclusion. Until the project, platform, and
task-permitted external sources have been compared, custom code is unavailable: do not call the task self-contained or
dependency-free and do not write code that recognizes the capability. A narrow result, an apparently simple
implementation, or an absent manifest, lockfile, cache, or installed dependency describes only the starter project; it
does not end reuse-search. This procedure does not apply to small local logic with no reusable or externally defined
capability, such as ordinary control flow, a value mapping, or a focused validation predicate. A generic acknowledgement
that does not name a task action is allowed before the routed reads complete.

Create one selection record before the first target-project source edit. A missing, failed, or planned entry leaves the
candidate under evaluation: complete that entry rather than selecting the candidate or writing production code.

**Do not edit the project the task asks you to change yet.** First create a new empty folder. The creation action must
either allocate a previously unused path or fail when its chosen path already exists; do not silently reuse an existing
directory. Before adding any artifact, list that folder and confirm that it has no entries. Put only the final source
files that expose the task's public operation, the final dependency declaration, and its lock or resolution file in that
folder. Acquire only those declared dependencies there and invoke the public operation—not only the candidate's API—on
the representative inputs. A direct candidate call evaluates the candidate; it does not show that the caller can use the
operation being added. When the candidate's API itself is the task's declared public operation, invoke that API directly.
An existing project, exploratory installation, or local cache cannot satisfy this gate, because it does not show that
the final source and declared dependencies work on their own in an empty folder.

Immediately before the first requested-project edit, check whether retained evidence lists the final source, declaration,
and lock or resolution file together in that empty folder and shows the public operation handling the representative
inputs. If it does not, the next task action is to create or correct the empty folder and run the public operation; do
not edit the requested project or substitute a direct candidate call.

1. **Define and inspect local sources.** Record the required behavior, limits, failures, and correctness or security
   conditions. Inspect the project API/dependencies, then the platform facility, as separate completed observations. A
   version, executable-presence check, or broad runtime description establishes only availability, not capability. Run a
   representative platform probe and retain its result; name the platform API or documented capability index that probe
   examines. When unavailable, record the official documented rejection. Do not query an external source, select a
   dependency, or write implementation code until these two observations are complete.
2. **Find and evaluate external candidates.** Adding a dependency is permitted unless the task or project policy
   prohibits it. A remembered package is plausible, not selected: after the local observations, acquire and evaluate it
   directly. Select it as soon as its documented contract or production-shaped experiment satisfies every condition.
   If no evaluated candidate is selected and custom code or a no-suitable-candidate conclusion would follow, search the
   permitted registry, catalog, or approved source using terms that name the required action and domain. Inspect each
   plausible result until one is selected or every plausible candidate has a recorded conflict. A closed candidate set
   may limit that search. One rejected candidate, missing comparison evidence, configuration work, or simpler custom
   code does not establish that no suitable candidate exists.
3. **Record the production decision.** Choose the candidate's entry point, arguments, configuration, and result member
   before any source edit, including files in the new empty folder. Record those choices, then run that exact operation on the
   representative inputs. A later source edit must implement the recorded choice; it must not discover or revise it. Retain
   each input and the exact result member or value production will decide from. A successful parse, broad object,
   unchecked cast, different overload, or later test is insufficient. When a candidate returns a container, inspect its
   accessible decision-relevant member before rejecting it: a shared outer type does not prove that the member lost the
   distinction. The retained probe must display that member or value for each input itself. A later record may link to the
   output, but cannot replace it by restating expected values; a list of member names shows only that a member may exist.
   Reject the candidate only when that member is absent or does not preserve the required difference; identical scalar
   results with no accessible member may establish rejection. Correct a failed probe from the documented interface and
   rerun it before selecting the candidate or editing production.

   Make the retained pre-edit probe a paired record: for every representative input, show the same selected operation and
   configuration, then show the exact member or value the later source will use. Do not let a successful parse, a bare
   value, one member observation, or a later public-operation result stand in for a missing row. If any input or
   decision-relevant member is absent, rerun the pre-edit probe before creating the recreation or editing the target.
4. **Prove the caller-facing integration in a new empty folder.** Build the source files that expose the task's public
   operation, its final declaration, and lock or resolution file in the new empty folder. Acquire only that declaration
   there. Before invoking the public operation, list the folder and confirm that those three artifacts are
   present together; installation output alone does not show that its resolution file was retained. Then invoke that
   public operation on the representative inputs. A direct parser, library, or candidate API call is selection evidence,
   not this proof, unless callers are meant to invoke that API itself. Until the proof succeeds, do not initialize a
   package manager, write dependency files, install the candidate, or edit source in the requested project: an exploratory
   installation or cache cannot prove that the final declaration and public operation work. If recreation fails, correct
   the declaration or reject the candidate and continue the search.

   The new folder's source files must be the same source files intended for the requested project. Run representative
   inputs from a separate retained command or test driver; do not append an ad-hoc loop, console output, or probe-only
   behavior to a recreated production source file. This does not prohibit a command-line program from having its
   intended command-line behavior. It prevents a temporary probe from becoming part of the integration that the
   new project is meant to prove.

   Keep the new empty folder limited to the final source, declaration, and lock or resolution artifacts. Before the
   proof command, list its retained paths and compare them with that intended set. A file left by an earlier attempt or
   an extra copy of a public operation makes the proof inconclusive: allocate a different empty folder and recreate the
   intended set before continuing. Keep the
   selection record, probe driver, and other evaluation-only files beside the retained evidence rather than in that
   folder. This lets the empty folder prove that the final source and declared dependencies can be acquired and invoked without silently
   relying on an evaluation artifact.

   Classify every tool needed during that proof before declaring it. A compiler, transpiler, test runner, interpreter
   option, or other facility belongs in the final declaration only when callers or the final project's supported build or
   runtime contract needs it. A facility used only to drive the proof is evaluation-only: do not add it as a final or
   development dependency merely to run the new folder. Use an already available project or platform facility to invoke
   the final source and retain that invocation, or report that the proof is blocked when no such facility can run it
   without an evaluation-only dependency.
5. **Commit and verify the selected integration.** Add the final declaration and source edit only after the preceding
   record is complete. Invoke the changed public operation through the selected import, member, registration, adapter,
   or configuration with the final declaration and the same representative inputs. Retain each returned value or
   rejection and, when caller-visible, its representation. A passing compilation, zero exit status, or claim that cases
   passed is not that evidence. A command that copies or redefines the changed operation, calls its dependency directly,
   or replaces a contract-required input pair with a different passing or failing pair establishes another behavior,
   even if its result looks equivalent. Load the changed public operation and invoke it with the contract's exact inputs.

For a standardized format, use the selected project or platform parser, validator, or normalizer before any custom
recognition logic. Its result or documented failure decides whether raw input satisfies the format. Do not add a regular
expression, tokenizer, line scanner, or other custom grammar check before or alongside that facility. When canonical
spelling matters, compare canonical text derived from the parsed value with raw input; add caller-specific semantic
constraints only after parsing. Test canonical, semantically equivalent noncanonical, and malformed input before
selection.

When a required decision distinguishes inputs, retain two linked checks: the selected candidate's exact
decision-relevant result before the source edit, and the changed public operation's value or rejection for those same
inputs afterward. A candidate that collapses the required distinction is rejected; continue the permitted-source search
rather than recreating the format grammar in custom code.

If a plausible dependency is identified, attempt the smallest supported acquisition operation before reporting it
unavailable. Only an explicit prohibition or the operation's registry, installation, cache, permission, or network
diagnostic blocks acquisition; a predicted permission problem does not. Report that block and obtain the needed access
instead of substituting custom code. Custom code is permitted only after every task-permitted source has completed a
search with no verified suitable candidate. Then document and verify the contract custom code owns.

# Check Assumptions

## Design Goals

- Ensure every conclusion, recommendation, rule, procedure, and delegated guidance relies only on prerequisites the
  reader can verify or obtain through an explicit mechanism, including required authority, rule routing, prior-run
  handoff, or a deferred-action trigger.
- Ensure a direct operation on a named artifact uses a target established by an authoritative locator, rather than a
  plausible filename guessed from memory or resemblance.
- Keep evidence gathering within the reader's usable context by requesting the smallest authoritative observation that
  establishes each premise, while retaining a full artifact outside the reader context only when later deterministic
  inspection needs it.
- Ensure a claim that a callable capability is absent is based on the complete authoritative interface for the current
  environment, not on an abbreviated display or the absence of one local executable.
- Let a user distinguish an established answer from an answer that remains unknown after the available research.

## Guidance

When this rule is read as one step in a declared ordered route, complete that route before applying this rule to
select, locate, or operate on any other artifact. The route declaration, not an example, premise, or artifact name in
this rule, determines the next required artifact. This restriction applies only while an ordered route remains
incomplete; a standalone use of this rule still follows the routes its own task triggers.

Before accepting or prescribing a conclusion, recommendation, rule, plan, or procedure, trace every premise that makes
it true or executable. Classify each premise as one of these:

1. An observed fact: identify the authoritative evidence that establishes it and when that evidence was checked.
2. A condition the workflow creates: identify its owner, creation step, storage location, lifetime, verification, and
   the point at which the reader retrieves it.
3. An external condition: identify the required authority, actor, dependency, or decision and do not imply that it is
   already available.

When a tool observation supplies a premise, request only the fields, records, lines, or bytes that can establish that
premise. Bound enumerations and broad documentation before they enter the reader context. For example, a package search
needs its query and returned candidate names; checking a candidate may need its version, declared interface, and the
specific documented capability, not its entire README. A large response can crowd out the governing task and make a
later decision less reliable even when the needed fact appeared near its beginning. If a complete artifact is needed for
deterministic validation, keep it in a file or tool-owned result, then use a focused query, parser, hash, or checker to
return the relevant observation. Do not treat a truncated, unbounded, or broad object dump as evidence for a specific
member or condition. This does not prohibit reading a small complete artifact when every part is needed to establish
the premise.

Before a broad discovery search, decide whether the next decision needs only matching locations or also their contents.
For locations, return a bounded path inventory or count rather than every matching line, and exclude generated output,
retained captures, caches, and other artifact directories unless the authorized scope makes them evidence. Select a
candidate from that inventory before requesting its contents. For a selected candidate, return only the needed fields or
lines and cap any line preview so one minified record cannot enter the reader context as an unbounded response. A capped
preview establishes only that a match exists at that location; when the decision needs data the cap omitted, use a
structured query or parser to extract that data from the selected artifact. Retain the complete artifact outside the
reader context for a later deterministic check when needed.

Do not turn an imagined, customary, or merely useful artifact into a blocking prerequisite. A missing skill, document,
tool, approval, or other input blocks the current work only when the task, the active harness contract, or a routed
instruction explicitly requires it. Otherwise continue with the available supported tools and report only an actual
failure at the operation that needs the artifact. For example, an undelivered testing guide does not block writing a
test, while a declared dependency whose installation fails is a real acquisition block.

Before directly reading, editing, invoking, deleting, or otherwise operating on a named artifact, establish its target
from an authoritative locator for the current context. A task, route, manifest, prior successful inventory, produced
output, or documented interface can name that target. A filename inferred from a similar artifact, a remembered name,
or an expected convention is only a discovery candidate: use the owning index or a scoped search to establish its
actual target before operating on it. A search may test candidates; it does not make one of them an established direct
target until its result does. Do not insert a guessed direct operation into an ordered required sequence. This does not
require rediscovery of a target that the current workflow just created or an authoritative source already names.

Treat an authoritative locator as selecting its exact target, not as a description from which to derive a shorter,
renamed, language-specific, or otherwise substituted target. An unsuccessful speculative lookup neither changes that
target nor completes a later operation that requires it. Continue with the established target when it remains available;
when that required target cannot be read or used, report the unmet prerequisite rather than choose a replacement.

Do not infer that a direct operation lacks authority merely because the actor cannot request additional authority. A
policy about escalation, approval requests, or interactive confirmation does not by itself prohibit an operation that
the current tools can run. Unless an applicable policy expressly prohibits that operation, execute the smallest
supported operation that establishes the needed fact and record its result. Treat an explicit prohibition or the
operation's own diagnostic as the authority evidence; do not substitute a prediction about permission. This applies to
dependency acquisition, service calls, file operations, and other actions whose authority is distinct from the process
for requesting more authority.

For a premise that an artifact, rule, capability, decision, or other source is absent or unique, inventory every
authoritative source for the current context and follow its routing, includes, overrides, and harness layers. A failed
search in one expected directory is not evidence of absence. Record the checked scope and any exclusion with its reason
before creating a replacement or reporting a limitation.

Before adding an API, adapter, bridge, option, or other integration boundary because an existing workflow appears
insufficient, inventory its supported calls and sequences, then execute the smallest existing sequence that could
satisfy the acceptance evidence. Add the new boundary only when that probe demonstrates the specific missing operation,
result, or control; do not infer a gap because a more direct design seems preferable.

When a validator, generator, or helper bundled with a platform rejects an artifact, do not infer that it defines the
platform's artifact contract merely because it ships with that platform. Identify the platform's documented contract and
the installed consumer that parses or executes the artifact. Treat the helper as authoritative only when those sources
establish that role. If its result conflicts with either source, preserve the working artifact, report the mismatch, and
use the platform's ingestion or runtime boundary for validation. For the Codex plugin-creator validator, see
[openai/codex#34334](https://github.com/openai/codex/issues/34334).

Before claiming that a tool, skill, subagent, command, test, or investigation has run or is unavailable—or that an
available tool, protocol, API, or workflow cannot perform an action—inspect the currently exposed invocation interface
and every authoritative contract available at that boundary, such as its documented interface, protocol schema, command
help, or installed implementation. When safe, execute the smallest supported sequence that could establish the action.
Report the action, its invocation evidence, and its status: completed, failed, or not invocable. If no invocation
interface is exposed, state only that the action cannot be invoked in the current turn; do not infer why. Treat an
untested candidate as unknown rather than unavailable, and do not claim that work ran without its invocation result. If
the sources conflict or the probe is inconclusive, report the evidence, the unresolved condition, and the next
discriminating check instead of declaring a limitation.

When the environment exposes a discoverable capability catalog, inventory that catalog before reporting that a named
tool or operation is unavailable. An initially displayed tool list is a preview, not absence evidence. Match the named
capability against the catalog's names and contracts, then invoke the smallest safe matching operation when its contract
permits it. The absence of a local executable establishes only that it is not on the current command path; it does not
establish that an equivalent callable tool is absent. A fixed command interface with no discoverable catalog is
different: inspect its command help or documented interface instead of inventing a catalog lookup.

When answering a user's question, do not present a plausible inference as its answer when the available authoritative
sources, supported probes, or their results still leave the answer unresolved. Say first that the answer remains
unknown, then name the question's concrete alternatives, the evidence that established each available fact, and the
next check or authority that would distinguish them. If no such check is currently available, say so and identify the
missing source or authority. This does not require an uncertainty warning for an answer established by the available
evidence; it applies only after the relevant research still cannot select the answer.

When an instruction names a required artifact, distinguish an unsuccessful exploratory lookup from completion of the
requirement. The lookup alone neither proves the named artifact absent nor fulfills the requirement. It is harmless only
when the required artifact is subsequently obtained and verified before a decision or action relies on it; otherwise
report the unmet prerequisite. Do not add a zero-exploration requirement unless the failed lookup itself changes an
observable result, authorization, cost, or safety boundary.

Apply the same trace to every future input. Verify it exists for a new and clean later run. A current run may use a
non-committed self-created or named upstream handoff only when its location, producer, consumer, lifetime, and creation
precede consumption. A later run may use prior data only through a named committed repository artifact and stated
retrieval workflow. If no workflow provides an input, add and verify an authorized deterministic creation/handoff,
replace it with a durable source of truth, or state the external dependency. For later-run data, name the producer,
repository artifact, save-and-commit step, and consumer retrieval step, and verify the handoff from a clean checkout. Do
not assume a ledger, cache, session, person, or tool state survives a future invocation.

Before finalizing the design, perform a clean-reader replay: start with only the stated durable inputs and available
authority at the later point of use, then follow the proposed conclusion or procedure literally. If a reader must infer
an unstated fact, recover an unpreserved decision, obtain an unmentioned artifact, or perform an unsupported action,
reopen the premise trace. Do not use a successful current-session execution as evidence that this replay will work.

Write prompts as executable guidance for their actual environment. Do not describe a workflow, artifact, capability,
handoff, exception, or recovery path that the known workflow does not create or make available: it gives the reader no
action to perform. Retain a conditional path only when the prompt names the condition under which it can exist and tells
the reader how to verify or obtain it. For an external dependency, state the dependency and stop; do not describe an
unavailable internal alternative as though the reader could select it.

Before requiring work that the current actor does not synchronously start and observe, identify the mechanism that
starts it and returns its result. Name the trigger or event source, the executor that remains active while the actor is
busy or absent, the delivery path that returns control or a result to the required consumer, its lifetime, and evidence
that the required action occurs. A prompt, rule, or stated intention does not schedule itself, wake a busy agent, run a
background action, or deliver a callback. If the mechanism is absent, either provide and verify an authorized scheduler,
monitor, callback, worker, handoff, or bounded control-return mechanism, make the behavior conditional on an existing
mechanism, or state the external dependency. Apply this to progress reports, retries, polling, cleanup, notifications,
deadlines, callbacks, background jobs, and every other asynchronous or deferred workflow requirement. This does not add
a trigger requirement to work that the current actor explicitly invokes and observes synchronously.

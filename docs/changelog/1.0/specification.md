# Recreating the CAT 1.0 Changelog

This file is the capture and rendering specification for [overview.html](overview.html). The HTML is a static
presentation of real, isolated harness runs; it is not a mocked demonstration. Recreate the page only from retained
source artifacts and capture metadata, never by rewriting or completing an agent's source manually.

## Specification Synchronization

This specification is authoritative for the visible page and its retained derived capture data. In the same change as
any revised visible claim, selector, prompt, capture requirement, rendering behavior, or acceptance condition here,
update or regenerate every affected part of [overview.html](overview.html) and its capture data. Before publishing,
compare the page and generated data against this specification; do not retain a page statement or output governed by an
earlier version of this file.

When retained capture metadata changes its date, harness version, model, fixture revision, or variability statement,
update the corresponding displayed evidence note in [overview.html](overview.html) in the same change. Do not leave a
previous capture date or other evidence value visible after replacing its captured data.

## Page Contract

- The page title is **What’s New in CAT 1.0**.
- The fixed selectors in the upper-right choose one harness—Codex or Claude—and one language: TypeScript, Python,
  Java, C#, C++, PHP, or Go. They must switch every section to output actually collected for that exact
  harness-language pair without changing the page's scroll position. Persist the selected harness and language in
  browser local storage. On initial load, a valid URL selection overrides a stored preference; otherwise use the stored
  preference, falling back to Codex and TypeScript. Apply that selection before parsing visible capture panels so no
  other selection flashes first.
- Write reader-facing page copy about the current user-visible behavior and decisions it supports. Do not add historical
  comparisons, capture or rendering mechanics, or other implementation details unless the reader needs them to act on
  the feature or interpret its result.
- In **Safer monetary calculations**, render one framed terminal styled for the selected harness. Its top frame contains
  the same non-interactive close, minimize, and maximize indicators as the other source captures. Place its left-aligned
  **Base**, **CAT**, and **Diff** buttons immediately below that frame, before the selected harness's observed identity
  header. The identity header flows directly into the exact prompt and complete generated source with no visible divider,
  and the terminal ends with its status bar. The buttons have visible borders, hover feedback, and a distinct pressed
  state; they select the unmodified Base output, the unmodified CAT output, or a unified diff inside that terminal. Base
  and CAT use the same numbered addition-line presentation as Diff, matching an agent's code-generation display; this
  presentation marker does not alter the retained source. In Diff, Claude shows a
  `Write(filename)` operation followed by its added/removed summary; Codex shows `Edited filename (+added -removed)`
  with colored counts. Both show only changed source lines with source line numbers, not Git's `diff --git`, `index`,
  path, or hunk headers. Match the selected harness's observed near-black surface, gray prompt bar and separators, its
  own added/removed palette, and status indicators. The prompt bar and changed Diff rows have the same visible horizontal
  gap from both terminal edges. The labels identify the modes so color is not the only distinction.
  Persist the selected terminal view across a page refresh.
- In other source comparisons, introduce the exact prompt with: “We gave the selected harness this request:”. Render it,
  including line breaks, in its own blockquote. Show complete generated source, including every method body, in the
  line-aligned synchronized side-by-side view; the complete unified diff is its alternate view. Do not add
  source-collapse controls. On a narrow display, stack the source panes rather than wrapping code.
- On a phone-width display, stack the fixed harness and language selector groups, reserve space for that control before
  page content, and keep the concern picker below it. Bound each stacked source pane and unified diff by viewport
  height while preserving horizontal code scrolling.
- Source text in both comparison modes must remain selectable and copyable after the pointer is released, including
  when the page is displayed in a host that clears a completed selection during pointer handling. Preserve the selected
  range synchronously during left-button pointer movement so the page can restore it after that host handling completes.
  Do not use a document-level selection listener for this. Do not render the
  selectable source comparison in an element that the host treats as an atomic select-all region.
- The source-view controls must switch their selected output at the toolbar before page-level pointer or selection
  handling can retarget a synthetic click. Dynamically rendered source captures must use the same event path as the
  initial page content.
- Preserve source whitespace, comments, and method bodies. Do not redact, summarize, alter, or fabricate captured code.
  Render tabs at a width of four spaces in every source-comparison pane on wider displays and two spaces on phone-width
  displays, while preserving each captured tab for copying.
  HTML escaping and mechanically generated line numbers/diff highlighting are permitted presentation changes.
- State the capture date, harness version/model, source fixture revision, and any result variability. A result is one
  observed completion, not a promise about all completions.
- Every section requires a complete Base and CAT capture for every harness-language pair. Do not label a Java capture as
  another language or leave a selector choice without independently retained output.

## Capture Isolation and Provenance

Every source pair needs a capture manifest containing the exact prompt, fixture files and revision, launch command,
harness version and model, UTC timestamp, full harness event log, and the resulting source paths. Retain those artifacts
under a versioned changelog-capture directory outside the rendered HTML. The controlled 1.0 artifacts are in
[`captures`](captures/README.md).

For a **base** Codex or Claude run, create a fresh harness home that contains only the authentication material required
by that harness and, when required by the host authentication relay, allow-listed connection settings. It must contain
no CAT marketplace, plugin cache, project rules, skills, agents, hooks, or inherited behavior configuration. Record the
empty plugin/marketplace inventory before launching the harness.

For every run, the calling tool scope resolves the CAT work root, defaulting to the gitignored project `.cat/work`
directory. It creates invocation-local capture resources below `.cat/work/temp`. For a **CAT** run, use the packaged
`codex-runner` or `claude-runner` with `--install-cat` and
the built artifact and retain its run directory, including its `run.json`, isolated home, plugin inventory, hook output,
and event log. Record the installed CAT artifact revision. Do not use the host harness home for either side of a
comparison.

Before capturing a monetary example, verify that each packaged harness artifact contains the current Money rule source.
Rebuild the distribution when it does not. Stage each harness-language Base/CAT pair outside the published capture
directory, then publish all four artifacts for that pair only after both runs complete and the CAT result passes its
language-specific validation. A failed Base or CAT run remains diagnostic evidence and must not replace either member of
the previously published pair.

When the capture executor yields a long-running runner command, retain its returned terminal session identifier and
poll that same session until the runner exits. A yielded outer command is not capture completion. Do not accept a run
whose manifest remains `initializing`, `idle`, or `working`, even when it has partial events or changed source; retain
the partial evidence only to diagnose the interruption and rerun the capture through the retained terminal session.

The present page is constructed from the retained controlled captures. Each Base Codex run disables plugins explicitly;
each Base Claude run has an empty plugin directory and no CAT `--add-dir` argument. Each CAT run records the packaged
CAT manifest in its isolated home.

To rebuild the review panel data, run `node captures/reviews/generate-rendered-captures.mjs`. It parses each retained CAT
event stream for its terminal response: Claude uses the final `result` event and Codex uses the final-answer
`agentMessage` from the completed turn. Preserve those strings unchanged in the retained event streams. The generator
creates `captures/reviews/rendered-captures.js` without summarizing or rewriting any finding text. When Base and CAT
contain confidently corresponding same-severity findings, generation must place those CAT finding blocks in Base order
for the display data, followed by unmatched CAT blocks; it must not change a block's text or move a finding across a
different severity. Produce the correspondence map with a model instructed to compare the retained finding meanings,
not title words; retain only its confident same-topic, comparable-severity mappings. The renderer may map a captured review-fixture path from an isolated run
to its counterpart below `fixtures/review`, including a Claude child-worktree path, but must leave the display data's
response text unchanged.

Accept a CAT capture with findings only when its first nonblank line is exactly `## Findings` and every top-level finding
has the `Evidence`, `Consequence`, and `Correction` fields. Accept an approval only when its complete terminal response
is exactly `APPROVED`. The CAT capture tool must extract the marked response-format block from the selected reviewer
definition in the exact packaged artifact it executes and append it only at the parent-to-child handoff boundary; it
must not maintain a second handwritten format. The retained user task remains byte-identical to the Base task. Retain a
failed run as diagnostic evidence, but do not publish it.

## Checkout-Code Example

Present this example under the heading **Safer monetary calculations**.

For TypeScript, Python, Java, C#, C++, PHP, and Go, use the matching clean fixture under
[`fixtures/languages`](fixtures/languages). Keep the fixture language-native and begin from its TODO-only checkout
calculator so structural noise does not select the implementation. Use the same language fixture for both Base and CAT.

Each Base/CAT scenario uses one byte-identical, retained task prompt. The harness configuration—not that task prompt—is
the only experimental difference: CAT may receive its installed rules and selected agent configuration. A task prompt
states only the caller-visible problem, inputs, required result, and domain constraints. It must not mention CAT, rules,
plugins, agents, a preferred library or representation, an API shape, validation strategy, comments, or another method
of solving the task. Do not compare or publish a pair when either side has a different task prompt.

```java
public final class CheckoutPriceCalculator {
    // TODO: implement checkout-price calculation
}
```

For every checkout-code comparison, generate the common prompt from
[`captures/languages/checkout-price-calculator.prompt.txt`](captures/languages/checkout-price-calculator.prompt.txt),
substituting `[Language]`:

> In [Language], implement a checkout-price calculator for one caller-supplied item price. The item price, discount
> rate, and tax rate are exact decimal strings in plain nonnegative decimal notation: digits with an optional decimal
> point, and no sign, exponent, whitespace, or separator. Discount and tax rates must be in the inclusive range [0, 1]
> and are decimal fractions, so "0.1" means 10%. Return the final total as an exact decimal string with exactly two
> decimal places, rounded using half-even rounding. The caller selects standard or expedited shipping. Apply the discount
> only to the item price. If expedited shipping is used, add a 15.00 fee after that discount and before tax.

The scaffold controls only the source path and enclosing type. The request reserves documentation for the following
example; it does not select a language-specific numeric representation, method signature, validation policy, shipping
representation, or dependency. Those are observed choices. The prompt defines the decimal input grammar, valid rate
range, decimal-fraction rates, two-decimal result format, and half-even rounding. Do not compare a base result from one prompt,
fixture, model, or revision with a CAT result from another.

Before rendering, validate each generated project with the matching language-native compile or syntax command and record
the outcome. If that language toolchain is unavailable, record the unavailable check rather than treating it as a pass.
If a behavior is not an explicit requirement, label any comparison as an implementation choice rather than pass/fail.

For the TypeScript CAT monetary captures, use an independent model review as the acceptance decision. Give it the
generated project, the exact generation task, and require it to inspect the source, package manifest, and lockfile as
needed. It accepts only when every task requirement is met and the exact monetary contract uses a declared established
decimal facility selected under the applicable Money rule; it rejects floating-point monetary inputs, custom decimal
arithmetic while a suitable dependency exists, or an unimplemented calculator. The reviewer must treat the exact task as
its sole caller-visible contract and must not add an API or behavior requirement after generation. Require the complete
verdict to be exactly `APPROVED` or `REJECTED: <evidence-based reason>`, retain its raw event stream, and publish the
pair only for `APPROVED`. Do not use source-pattern matching as the semantic acceptance gate.

## Documentation Example

Both Base and CAT runs start from the identical selected-language source below `fixtures/languages`. The prompt comes
from [`captures/languages/documentation.prompt.txt`](captures/languages/documentation.prompt.txt) and is exactly:

> Document this code

```java
package example;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class ReservationExpiry {
    public int moveExpired(List<Reservation> activeReservations, List<Reservation> expiredReservations,
            Instant cutoff, int limit) {
        Objects.requireNonNull(activeReservations, "activeReservations");
        Objects.requireNonNull(expiredReservations, "expiredReservations");
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }

        int moved = 0;
        Iterator<Reservation> iterator = activeReservations.iterator();
        while (iterator.hasNext() && moved < limit) {
            Reservation reservation = iterator.next();
            if (!reservation.expiresAt().isAfter(cutoff)) {
                iterator.remove();
                expiredReservations.add(reservation);
                moved++;
            }
        }
        return moved;
    }

    record Reservation(String id, Instant expiresAt) {
    }
}
```

Render the complete method body in every presentation. The underlying source artifact must stay complete and available
to the renderer. Before publishing, use identical fixture, prompt, model revision, and isolation for every Base/CAT
pair. The generated diff may add only Javadoc and imports used solely by Javadoc; it must compile and pass doclint.
Reject invented guarantees. Use a blind checklist for the source and destination roles, inclusive cutoff, transfer limit,
encounter order, both collections' successful final state, return meaning, deterministic input failures, and partial
state when mutation fails. Publish the example only when each CAT result covers at least seven of those eight facts,
has no material regression, and covers at least three more consequential facts than its paired Base result.

## Other Demonstrations

Only include skills, agents, installation, or removal when their value can be shown visually from a real harness run.
Do not show hook, launcher, prompt-injection, marketplace, or other implementation internals as a feature demonstration.
When adding a new example, first add its fixture, exact prompt, expected visible evidence, base/CAT isolation procedure,
and artifact paths to this file; then generate its captures and update the HTML.

## Specialist-Review Examples

For each selected language, use the matching fixture below
[`fixtures/review-languages`](fixtures/review-languages). Its declared change deliberately contains independently
reviewable risks in the language-native download endpoint, release configuration, operational plan, license notice,
user-facing error, and tests. Do not treat the fixture's defects as properties of CAT; they are controlled inputs used
to show the scope of each review.

For each of Architecture, Business, Deployment, Design, Legal, Performance, Requirements, Security, Testing, UX, and
Plan Review, capture one **Base** and one matching **CAT** review with the same concern-specific task generated from
[`captures/reviews/review-task-template.txt`](captures/reviews/review-task-template.txt). Substitute `[Concern]` in
both copies. The shared task does not identify CAT, a reviewer, delegation, a response form, or a method of review.
The CAT capture wrapper selects the matching installed medium-tier reviewer and supplies that reviewer's required
response form only at the parent-to-child handoff boundary; that configuration is retained as harness evidence, not as
the user task. The page compares the two complete raw responses. Do not paraphrase either side in the visual comparison.
A CAT capture is valid only
when its terminal response contains the selected installed specialist's findings rather than a handoff confirmation and
its event stream records the selected reviewer invocation. Reviewers inspect without editing, so
“correction” means the recommended change, not an edit made during the capture.

Each CAT reviewer must return either exactly `APPROVED` or a `## Findings` list. Every finding uses the same Markdown
shape: a file-and-line-labelled title, followed by **Evidence**, **Consequence**, and **Correction** sub-items. Do not
use undefined priority labels. The Business reviewer reports a technical issue only when its evidence establishes a
material effect on the stated outcome, customer adoption, scope, cost, or another business decision; the reviewer leaves
concerns owned primarily by another specialist to that specialist.

Retain the fixture, prompt, Base and CAT response, manifest, and event stream under
[`captures/reviews`](captures/reviews/README.md). For every harness-language-concern pair, run the identical Base task
and CAT task against separate fresh matching-language fixture copies. The Base home must meet the isolation requirements
above; the CAT run must retain its
installed-plugin inventory and reviewer-invocation evidence for each harness.

## Rendering Checklist

1. Verify each selected-harness panel originates from that selected harness's retained manifest.
2. Verify both sides of each pair use the same prompt and fixture revision.
3. Mechanically construct the comparison from the two captured files; generate the unified diff with context at least as
   large as the longer source file so the side-by-side renderer receives every line. Do not hand-align favorable
   changes.
4. Keep side-by-side headings, sticky source-file labels, synchronized scrolling, and the unified-diff toggle working.
5. Test the Codex/Claude selector at the top and bottom of the document; changing it must preserve scroll position.
6. Test wide and narrow layouts; code must retain horizontal scrolling rather than wrapping mid-line.
7. Review the rendered text for literal escaped markup, missing source lines, blank-line inflation, and mismatched base/CAT
   source.
8. For specialist reviews, show one selected concern at a time in a sticky category picker. Inside the selected review
   card, immediately below its title bar and above its terminal panes, state in plain language what that specialist
   focuses on without a category-label prefix. Label Base and CAT as independent verbatim responses rather than a diff,
   and keep equal-width panes side-by-side only when the display is wide enough to read them.
9. On narrower displays, keep the concern picker visible and let the reader select Base, CAT, or both; default to both
   panels and do not create nested response scrollers.
10. Render captured review Markdown in terminal-styled panes for readable headings, lists, emphasis, inline code, and
    links. Support both bare and angle-bracket-wrapped Markdown link targets; the wrappers are Markdown delimiters, not
    part of the target URL. Preserve the prepared display data as the rendering source; do not reorder findings at render time. For a reference to a published review-fixture file, map
    either a captured link target or an inline-code file-and-line reference to that fixture copy; never expose a run
    artifact path.
11. Recheck every visible claim, selected prompt, and rendered capture against this specification after changing either
    source; regenerate the page data or update the page before publishing when any differ.
12. Above raw review panes, derive a compact Base/CAT finding-count summary and linked titles from the retained strings.
    Identify unmatched CAT findings as “Additional finding” from retained semantic-correspondence metadata produced by a model comparison of paired
    findings and accepted as a publication input; do not infer correspondence from titles, locations, or lexical overlap,
    and do not alter the strings. Render contract-shaped findings as collapsed cards with each title visible, separate
    Evidence, Consequence, and Correction fields, and an emphasized Correction. Render unstructured Base findings as
    collapsed cards with their captured finding text intact; do not invent missing fields. Keep the raw retained response
    as the only content source.

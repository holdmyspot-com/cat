# Prompt Organization

## Design Goals

- Keep each durable prompt decision in one authoritative, discoverable file without duplicating mutable facts or
  unrelated guidance.
- Create and route companion files only when their independently observable trigger requires a distinct reader
  decision or verification, and reserve eager guidance for decisions every reader must make because no observable
  trigger can select it.
- Make every lazy prompt decision reachable at the reader's decision boundary; a reader missing a route is a delivery
  defect to repair, not evidence that the decision belongs in the eager baseline.

Give each prompt file one enduring decision topic. Before adding guidance, identify its trigger, reader, decision, and
acceptance evidence, then search existing prompt files for that owner. Update the owner when all four match.

Create a companion file only when its decision has an independently observable trigger or needs distinct verification.
Route to it directly from the file a reader already has, using the observable condition that requires it. Do not split a
file merely to isolate an example or a short exception, and do not use an index that only forwards readers to another
file.

For each lazy decision, derive the earliest reader-visible condition that makes the decision necessary. Define the
condition in terms of task inputs, the artifact being changed, or an observable operation--not an outcome such as a
mistake the guidance is intended to prevent. Then identify the delivery boundary that receives that condition and can
load the decision before the reader acts. Test the route with the reported case, a meaningful analogous case that must
load it, and a dissimilar case that must not. The tests must establish both that the delivery boundary receives the
selected cases and that the rendered reader context contains the guidance before the governed action.

When a reader misses a lazy rule, first classify whether the trigger was absent, ambiguous, too late, unsupported by
the delivery boundary, or delivered but ignored. Repair the earliest failed owner: make the trigger precise, move
delivery to
the boundary that observes it, or add a deterministic tool that selects and injects the matching guidance. Do not call a
missed route proof that no trigger exists, and do not replace this analysis by making the guidance eager or broadening
the rule.

Eager delivery is allowed only after the trigger analysis establishes that no observable condition can select the
guidance and every supported reader decision needs it. Record the rejected eager alternative and the evidence that the
selected trigger reaches the required case without loading the guidance for the dissimilar case.

Keep mutable facts in their authoritative files. A reference names the file and why to read it, rather than copying its
current values into another prompt.

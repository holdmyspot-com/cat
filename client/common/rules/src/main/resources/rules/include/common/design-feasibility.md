# Design Feasibility and Reliable Automation

## Design Goals

- Warn before implementation when a requested guarantee requires a fact, authority, or decision that the stated inputs
  cannot determine.
- Prevent an agent from silently replacing an unsupported requested guarantee with a narrower scope, a
  proposal-and-approval workflow, or another feasible alternative; require the user to choose that alternative before
  the agent creates a tracked issue, a plan, or an implementation for it, and make the alternative's supplied authority
  or surrendered guarantee explicit.
- Distinguish an impossible guarantee from a feasible design that needs an additional authoritative input, and from a
  best-effort inference that must not be described as reliable automation.
- Prevent an implementation from treating its own inferred output as proof of a business, specification, or other
  external decision.
- Prevent a forecast objective such as minimizing expected cost from being accepted as an executable stopping or
  strategy-selection policy without a defined predictor, uncertainty decision, and fallback.

## Guidance

Apply the foundational Backward Design rule injected by the default rule.

Before designing a feature that promises a reliable, complete, automatic, general, or deterministic result, work
backward from the terminal acceptance evidence. Name every decision the feature must make, the authoritative source that
establishes the correct answer, the actor authorized to provide or change that source, and the validation that can
distinguish a correct result from a plausible one.

Apply the **information-sufficiency test** to each decision. Construct two permitted contexts with the same inputs the
feature may inspect. If the correct decision differs between those contexts, the feature cannot make that decision
reliably under the stated constraints. This is a proof of a missing input, not a request to improve the inference.

When a workflow chooses, stops, or changes strategy from a predicted cost, duration, probability, benefit, or other
future outcome, do not treat the optimization objective as its decision procedure. Before calling the workflow
executable, require its design to identify:

- the candidate actions and the observable boundary where one must be selected;
- every feature the predictor may inspect and the operation that supplies it;
- the predictor or statistical method that owns the estimate, its applicable reference population, and evidence that
  its predictions are calibrated for that population;
- how the decision represents uncertainty and the condition that makes a forecast too uncertain or inapplicable;
- the loss, threshold, or comparison that selects one candidate action from the forecast; and
- the bounded fallback when required data is absent, the case is outside the calibrated population, or the forecast
  cannot support the decision.

Keep the predictor and decision policy in authoritative automation. An agent may interpret their structured result or
explain the selected action, but it must not replace an absent estimate, calibration result, uncertainty policy, or
fallback with intuitive judgment. Record prediction error and the later observed outcome so the owner can test and
recalibrate the method. Until those inputs and policies exist, label the forecast-dependent decision unspecified; a
goal to minimize expected cost does not establish that the workflow can find the global or actual minimum.

Treat the following as warning signals that require this test:

- The feature must classify intent, support status, business meaning, ownership, priority, feature boundaries, or
  another subjective/specification decision, but its allowed inputs are only code, history, or generated artifacts.
- The feature claims to work for arbitrary user projects while the required decision depends on project-specific
  contracts, audiences, workflows, or terminology that no supplied specification defines.
- The feature must prove its own inferred label, mapping, or grouping correct, but no independent check, regression,
  authoritative contract, or user approval can reject a wrong result.
- The feature must compare identities or equivalence classes but no canonical representation or approved mapping exists.
- The requested guarantee excludes the only actor or authority that can supply the needed decision.
- The proposed acceptance check can show that the feature ran, but cannot distinguish a correct result from a convincing
  incorrect one.

Do not issue a feasibility warning merely because work is difficult, slow, novel, or unimplemented. Issue it only when
the information-sufficiency test, an unavailable authority, or an untestable acceptance condition establishes the gap.
Record the two-context counterexample or the exact missing authority/evidence.

Before implementation, present a **Reliability Warning** that states: the promised guarantee; the decision it requires;
the missing authoritative input or authority; the concrete indistinguishable-context counterexample; and the smallest
feasible choices. The choices are: provide an authoritative specification or reviewed plan; narrow the supported scope
to contexts with a named contract; make the result an explicitly reviewable proposal; or remove the unsupported
guarantee. For every choice, state both what the user must supply or approve and exactly what part of the original
guarantee they would give up, such as automatic correctness, arbitrary-project coverage, completeness, or operation
without review. Do not silently choose one.

Treat this warning as a decision gate, not advice. Record a **Feasibility Decision** with these fields: requested
guarantee, decisions required, authoritative source for each decision, two-context counterexample or acceptance
evidence, result (`feasible`, `infeasible as stated`, or `feasible with added authority`), feasible choices, and the
tradeoff for each choice, and the user's selected choice. When the result is not `feasible`, stop after the warning and
choices. Do not create a tracked issue, implementation plan, delegated task, or code change for a substituted scope
until the user explicitly selects one. Approval to create an issue or begin work is not selection of a different
guarantee.

Do not redefine a request to make it feasible. For example, “automatic feature discovery” cannot become
“proposal-and-approval replay” merely by saying that automatic *means* plan-first. State that the latter is a different
workflow, identify the removed guarantee, and wait for the user's choice. This applies whenever the missing decision is
feature ownership, business intent, support status, equivalence, priority, or another specification judgment; it is not
limited to Git or topic workflows.

For example, code and Git history can show that a later change edits an earlier feature's code, but cannot establish
whether the product owner considers that change part of the earlier feature or a new deliverable. A workflow that must
reliably consolidate changes by originating feature therefore needs an approved feature-association plan, a durable
feature identifier, or an explicit proposal-and-approval step. It may not claim that automatic topic discovery from
history alone is reliable.

After the user selects a feasible path, record the new authoritative input, its producer, lifetime, and validation
boundary. Re-run the information-sufficiency test for the revised design, including one meaningful similar case and one
dissimilar case. Implement only after the terminal acceptance evidence can reject a wrong result under the selected
scope.

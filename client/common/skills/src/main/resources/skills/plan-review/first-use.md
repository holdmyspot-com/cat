# Plan Review

## Design Goals

- Reduce the remaining AI implementation work only through user-confirmed changes to product behavior, while preserving
	every guarantee the user keeps.
- Ask only about product choices whose alternatives materially change the design or remaining AI implementation cost.
	Review the most costly qualifying choice first, then reconsider the order and qualifying children after each answer.
- Give each scope choice one clearly labelled recommendation with a reason tied to the user's goals, the remaining AI
	work it could avoid, and its main trade-off.
- Leave one current product contract and executable implementation plan whose outcomes, dependencies, and acceptance
	checks match the confirmed scope.
- After each confirmed choice, ask the next independent scope question without waiting for document edits, while one
	bounded writer updates the product contract and plan; verify those edits before implementation relies on them.

Use this skill when the user asks to review or reduce a design and implementation plan by confirming which features or
use cases are needed. The review is a product-scope conversation; do not turn routine APIs, workspace mechanics, or
other internal design choices into user questions.

## Review loop

1. Locate the authoritative product sources and current implementation plan. Inventory the promised user-visible
	outcomes, supported use cases, failure and recovery guarantees, acceptance gates, dependencies, and verified work.
	Preserve prior scope decisions that remain applicable. If a plan is missing, work from the available contract and
	identify the artifact to revise after a decision.
	Distinguish user-requested outcomes from stronger guarantees introduced by the design. Trace each costly stronger
	guarantee to explicit user authority or necessity under the retained contract; an implementation-plan assertion alone
	is not a confirmed scope decision. Keeping a capability does not automatically confirm every stronger guarantee its
	implementation could provide.
2. Build one queue of independently selectable product elements. For each, record its current commitment, observable
	benefit, prerequisites, consequence of simplifying, deferring, or removing it, and **marginal remaining AI wall-clock
	cost**. Include integration and verification, account for shared work only once, and consider parallel work by its
	critical path. Before adding an element, identify the concrete design or implementation work that would differ
	between its feasible answers and the evidence that the **incremental** difference is material. Separately selectable
	details that share the same architecture and acceptance work are one element unless a detail independently changes a
	major capability, integration boundary, recovery design, or substantial critical-path work. A restriction that needs
	its own enforcement may save little or no work. Exclude questions based only on conceivable variations, a few extra
	checks, or speculative savings. Rank qualifying elements by work a choice could actually avoid. Use relative cost
	when evidence cannot support a numerical forecast; do not turn an intuition into an ETA.
	Include costly stronger guarantees as independently reviewable elements when they materially change the outcome.
	Compare their extra implementation and verification work with the failure likelihood, impact, detectability, and
	recovery supported by the evidence; mark unknown likelihood rather than assuming risk is high or low. Establish which
	resources recovery actually covers. Recommend the least costly approach that satisfies the requested outcome, and
	make any difference between instructions, measured compliance, detection, and prevention explicit. When alternatives
	satisfy the same retained contract, select the simpler internal design without a scope question.
3. Present the highest-cost unresolved element and ask one concrete scope question. Offer only feasible choices and
	explain which behavior and acceptance claim each would keep or surrender, and which material design or implementation
	work the answer changes. If that difference cannot be established, resolve routine details from the retained contract
	instead of asking. Mark exactly one option as **Recommended** and explain in the question why it best fits the user's
	confirmed goals, considering marginal remaining AI work and its main trade-off. Put the reason where the user sees it
	with the options, including in a
	question tool's prompt rather than only in separate commentary. If the evidence cannot support a recommendation,
	establish the missing user preference or fact before presenting the scope choices; do not invent a reason. Keep a
	retained guarantee's necessary safeguards together with it; never offer to remove evidence validity, safety, or
	recovery while claiming the same outcome. Use the available user-question channel. Silence is not a choice.
4. Interpret each answer as a product-scope decision and record it in the live review ledger before assigning edits.
	Keep the user's authority and question queue with the interviewing agent. If the user keeps an element, split it
	into user-facing features, use cases, or scope boundaries that each pass step 2's incremental materiality test. Add
	those children to the **same** queue and rerank it against every remaining element. Stop splitting when the next
	distinction shares the retained work, has no demonstrated material effect, or would ask the user to choose
	implementation mechanics. Use the ledger, including decisions not yet written into the documents, when framing the
	next question.
5. Ask the next qualifying question as soon as its alternatives follow from the confirmed decisions and available
	evidence.
	Do not wait for the subagent to finish editing or for document validation merely to continue the interview. Defer
	only a question whose alternatives depend on an unresolved product-policy gap or evidence the pending edit must
	establish. If a later review invalidates a question already asked, correct it before using its answer.
6. After every confirmed choice, including a kept element, send its documentation work to one exclusive write-capable
	subagent when direct launch is available. Give it the exact decision, source snapshot and governing document
	revisions, preserved guarantees, affected outcomes and acceptance gates, worktree mode, exact write allowlist, and
	literal focused verification commands. Include any design record that the current plan requires at this stage in
	that allowlist. The subagent is not alone in the codebase: it must preserve others' edits, avoid unrelated files,
	and report an unresolved product-policy question instead of choosing for the user. Do not edit the same documents
	concurrently. If the writer is busy when another answer arrives, queue the new decision for that writer's next
	revision, preserving answer order and the current document snapshot. If no direct launch is available, the
	interviewing agent makes the same revision while continuing independent questions and reports that delegation was
	unavailable.
7. Review each completed contract-and-plan revision against the queued decisions before accepting it or releasing
	implementation work that relies on it. Check dependent behavior, producer and consumer handoffs, recovery, tests,
	acceptance gates, and previously verified work. Repair omissions under the same bounded edit authority; mark
	superseded decisions as history rather than executable guidance. Record kept choices too, so they are not asked
	again. A pending edit does not make a confirmed user choice tentative or justify asking it again.
8. Continue until no independently selectable product element passes step 2's materiality test, or the user asks to
	stop. Before reporting the revised plan as current, finish and verify every queued document edit. Report the
	confirmed scope,
	deferred or removed behavior, changed plan, and any cost estimate the evidence supports. Do not claim implementation
	completion from a completed scope review.

Ask for product authority where alternatives change what the user receives and pass step 2's materiality test. Resolve
internal design and routine implementation from the retained contract and evidence. If two candidate questions share
one prerequisite, state the dependency and rank their avoidable work without counting that prerequisite twice.

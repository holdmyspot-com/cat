# Mistake Learning

## Design Goals

- Correct a prevention proposal that is itself wrong without conflating that meta-level mistake with the operational
  failure it was meant to prevent.
- Give the project developer the related questions, evidence-backed answers, and proposed ownership needed to assess
  whether governing guidance should change.

## Guidance

Use this rule only after Failure Prevention has analyzed an operational failure and the proposed prevention, rule
revision, or prompt-file change is itself wrong or incomplete. Keep the two causes distinct: first record the
operational failure's earliest controllable cause, then identify what allowed the prevention proposal to select the
wrong owner, scope, trigger, evidence, or remedy. Answer these meta-questions for the project developer: Which rule,
contract, procedure, tool, or missing definition governed that prevention choice? Was that authority absent, ambiguous,
routed too late, unverified, or not applied? What smallest change would have selected the right prevention for this and
meaningful analogous cases? Which existing rule or artifact owns that change, and why does it belong there rather than
in a duplicate rule?

State evidence for each answer and distinguish a verified cause from a hypothesis and next check. When analysis finds a
preventable gap, propose the exact owning-artifact change before applying it. Do not present the correction as complete
until the developer has the meta-analysis and any remaining uncertainty.

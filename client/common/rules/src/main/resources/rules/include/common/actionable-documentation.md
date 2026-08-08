# Actionable Documentation Coverage

## Design Goals

- Ensure an artifact-wide documentation audit verifies that each document enables its reader to make the required
  decisions, not merely that documentation is present or syntactically valid, and excludes facts that do not support
  those decisions.

## Guidance

When applying documentation conventions across existing code or documentation, inventory every in-scope document
section, code comment, type, function, method, script, command, and configuration entry. For each, record its intended
reader and the decision that reader must make. Then check that the wording gives the reader the information needed to
act: concrete responsibility or behavior, applicable inputs and outputs, caller-visible state changes and failures,
and—when the name does not make it clear—when to use it or what to do next.

Classify whether the information required for each reader decision is directly stated, safely implied by a plainly named
operation, or missing. Do not accept an internal label, implementation mechanism, or compressed project term as a
description of behavior or a use condition. Check every caller-visible side effect, including a supplied resource that
the operation closes, replaces, or consumes. Rewrite every missing or misleading description, then recheck the complete
inventory rather than only the reported example.

For every retained fact, identify the reader decision, action, right, obligation, or verification it supports. Remove
provenance, generation or rendering mechanics, prior presentation, editing history, and other implementation details
when they support none. Retain a mechanism only when the reader must use it or know it to predict behavior, exercise a
right or obligation, recover from failure, or verify the documented claim.

## Coding-Harness-Neutral Common Boundaries

## Design Goals

- Keep shared code coding-harness-neutral while providing every supported coding harness with equivalent feasible
	functionality.

## Guidance

Before applying this rule, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/java-modules/shared-secrets.md`.

Treat `client/common/` and `.cat/rules/common/` as common locations. They may use coding-harness-neutral concepts and
data, including a harness identifier, generic contracts, and caller-provided values. They must not discover, name,
derive, select, or access a specific harness's integration details, including harness-owned locations, paths,
environment variables, APIs, files, configuration, prompts, rules, or behavior.

Put each concrete harness reference and integration decision in that harness's module or rule. Harness-owned code may
invoke common code through a harness-neutral contract. Share common behavior only when its execution contract is
identical; otherwise keep the differing behavior in the owning harness location. Follow the Shared Secrets and
reflection policy in `${CAT_PROJECT_ROOT}/.cat/rules/include/java-modules/shared-secrets.md`.

This applies equally to production code, tests, fixtures, examples, comments, and literal values. Common tests model
harness-neutral contracts using generic identifiers such as `harness-a`; move a test that requires a concrete harness
name, resource, path, variable, manifest, or behavior into the owning harness module.

For every changed common artifact, verify that it contains no concrete harness integration reference and does not
construct, inspect, or access a harness-owned location from a harness identifier or other generic data. For every
harness-specific integration, verify that the owning harness module or rule owns the concrete reference and calls common
code only through a harness-neutral contract or caller-supplied value.

## Harness Capability Parity

When adding or changing functionality for one harness, assess every other supported harness. Add the functionality to
each harness that can support it, either natively or through emulation. Do not leave support harness-specific merely
because its first implementation is harness-specific.

Test behavior implemented by common code once, directly against its common callable with harness-neutral inputs; do not
duplicate its test merely because multiple harnesses call it. A test is not a common-code test merely because it sits in
a common file or invokes multiple concrete harnesses. Move every concrete harness launcher, installer, manifest, path,
configuration, output, or external boundary test into that harness's test suite. Test every harness-specific adaptation
or external boundary for each supporting harness whenever technically possible. Apply this equally to Java tests and
Bats boundary tests: verify the same observable contract, not identical implementation details, inputs, or test-file
layout. Use Bats for each supporting harness when the contract crosses the plugin's command-line interface, such as a
harness-specific launcher result, installer, or shell bootstrap; the jlink launcher template is common code, tested
once. Document a missing peer test with the feature's verification evidence only when that harness
cannot support the behavior natively or through emulation.

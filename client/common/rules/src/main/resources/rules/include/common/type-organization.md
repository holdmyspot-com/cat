# Type Organization

## Design Goals

- Keep a collaborating family of implementation-only top-level types discoverable through one focused package, without
	leaking their enclosing component's name into every local type.

## Guidance

When a component needs several related top-level implementation types, place that family in a package named for their
shared domain and keep the entry point and its non-public collaborators together there when their dependencies permit.
Use the package to establish that context: an implementation-only type should state its local responsibility, such as
`PublicCommands`, rather than repeat its enclosing component's name. Retain a component prefix only when callers
must distinguish equally plausible types across package boundaries. Do not create a package for a single incidental
helper or move a component by widening package-private dependencies merely to satisfy this organization; first extract
or relocate the dependent family through a reviewed boundary.

Apply the same naming and package decision to test-source collaborators. A test that proves one component's public or
internal boundary belongs with that component family and uses its local responsibility as the class name, such as
`PublicCommandsTest`; it must not repeat the production component merely because the test source has a broader package.
Whenever creating, moving, or renaming a top-level implementation or test type, compare its simple name with its package
context before compiling: remove a component prefix that the package already establishes, or record the cross-package
ambiguity that requires retaining it.

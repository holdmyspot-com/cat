# Prompt Assembly

## Design Goals

- Deliver each supported harness a complete prompt file with only the metadata and instruction forms that harness
  accepts.
- Keep shared instruction content authoritative in one source while isolating harness-specific metadata and invocation
  details to their wrappers.

## Guidance

List every supported delivered form before editing. For each one, identify its wrapper, shared source, packaging step,
and reader-visible loading boundary. A shared prompt body may express only capabilities available to every target. Put a
target's frontmatter, tool names, or settings in that target's wrapper.

Before a prompt tells its reader to use an environment variable, identify the process that reads it and the harness
contract that guarantees it there. Keep a hook-only variable in the hook entry point or hook-specific prompt; do not
refer an ordinary agent shell, runner, or standalone CLI to it. If the reader's process does not receive the value,
route the operation to a context-specific wrapper or CLI that resolves it, or give that process the resolved value as
an explicit argument. Test the assembled prompt in the reader's actual process context, including one context where a
similarly named variable is absent.

After assembly, inspect each delivered prompt file. Verify that it contains the complete shared guidance, retains only
the target's supported metadata, resolves every referenced shipped path, and contains no source-only assembly marker.
Do not treat a source file or a successful build as proof of the assembled reader contract.

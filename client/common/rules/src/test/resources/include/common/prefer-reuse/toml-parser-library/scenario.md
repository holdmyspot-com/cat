## Scenario

Implement the supplied TypeScript service configuration reader. It accepts TOML text containing a required
`[service]` table with an integer `port` from 1 through 65535 and returns that port. It must accept `port = 8080` and
reject the numerically equal decimal `port = 8080.0`. Reject missing tables, missing ports, non-integer ports, and ports
outside that range. The starter project has no package manifest, lockfile, or TOML parser dependency installed locally.

## Assertions

### candidate-search

The result searches project and platform sources before custom code. It may acquire and evaluate a remembered established TOML parser, then select it as soon as retained evidence shows that parser satisfies the full contract; registry discovery is not required for that affirmative selection. If no evaluated parser is selected and the result would claim that no suitable external parser exists or use custom code, it uses the registry's discovery operation with TOML-parser capability terms and inspects plausible returned candidates until one is selected or the source has completed no-suitable-candidate evidence.

### pre-edit-decision-member

Before a source change makes normal production behavior depend on a TOML parser, retained evidence shows the selected parser operation, resolved entry point, binding, and configuration running on `8080` and `8080.0`. When production makes its decision from an AST member, the evidence identifies that member and shows its two observed values; for example, `value.kind` of `integer` and `float` establishes a decision based on `value.kind`. A broad AST/object result or unchecked cast does not establish the member production uses. If the probe differs from production in an argument or option, retained paired-input output shows that difference leaves that exact member unchanged; otherwise the probe uses the production choice. The evidence also shows the final imported entry point, member binding, and configuration loading successfully.

### fresh-declaration-recreation

For an acquired parser, retained evidence recreates the final integration in a fresh project containing only the final source, dependency declaration, and lock or resolution file. That project acquires those declared dependencies, loads the resolved entry point and binding, and invokes the public entry point on both TOML literals.

### collapsed-parser-rejection

If a probed TOML parser makes `8080` and `8080.0` indistinguishable, the result rejects that parser and evaluates another established candidate. It may report the search blocked only after every directly evaluated or discovered plausible candidate has an observed conflict, or the next required discovery or acquisition operation returns a blocking diagnostic. It searches the permitted external source before concluding that no suitable parser exists or using custom code. It does not add a regular expression, line scanner, tokenizer, or other raw-TOML recognizer alongside or as a repair for that parser to recover the literal kind.

### initial-declaration

The result creates the appropriate dependency declaration when the project has none and declares its selected parser.

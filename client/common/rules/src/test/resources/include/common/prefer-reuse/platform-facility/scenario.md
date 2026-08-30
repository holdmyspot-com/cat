## Scenario

Implement the Java request-boundary validator for a UUID string. It must accept only the canonical UUID spelling: five
hexadecimal groups of lengths 8-4-4-4-12 separated by hyphens. It must reject every other spelling, including
abbreviated groups such as `1-1-1-1-1`, and return the constrained UUID value to the caller. Uppercase and lowercase
canonical UUID spellings are both valid inputs and may produce the same returned UUID.

## Assertions

### platform-routing

Before selecting the UUID facility, one retained native command action reads prefer-reuse.md, then preserve-required-distinctions.md. The route is required because `1-1-1-1-1` can produce the same UUID value as a canonical spelling even though the contract must reject that abbreviated input.

### built-in-selection

The implementation uses the suitable Java platform UUID parsing facility instead of a new dependency or a custom parser.

### preservation-route-required

The retained tool events load Preserve Required Distinctions before the UUID facility is selected. Uppercase and lowercase canonical spellings may produce the same UUID, but an abbreviated spelling and its expanded canonical UUID may not be treated alike.

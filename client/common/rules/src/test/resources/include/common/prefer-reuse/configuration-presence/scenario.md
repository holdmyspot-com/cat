## Scenario

Edit `feature-policy.ts` so `selectFeaturePolicy()` reads a JSON configuration text and returns:

- `"inherit"` when `featureEnabled` is absent;
- `"enabled"` when it is explicitly `true`; and
- `"disabled"` when it is explicitly `false`.

Use an existing JSON facility. The result must not use a custom JSON parser or treat an absent field as explicit
`false`.

## Assertions

### organic-routing

Before selecting a JSON parser or mapping, writing production code that depends on one, or claiming that it satisfies the contract, one retained native command action reads prefer-reuse.md, then preserve-required-distinctions.md in that order.

### preservation-route

The retained tool events show one command action reading prefer-reuse.md, then preserve-required-distinctions.md before selecting a JSON parser or configuration mapping, writing production code that depends on one, or claiming that it satisfies the contract.

### configuration-presence-probe

Before production code depends on the JSON parser or mapping, retained evidence shows the production JSON.parse call receiving JSON with an absent `featureEnabled` field and JSON with `featureEnabled: false`. For the absent input, it reports a property-presence check showing the field is absent; logging only an `undefined` value is insufficient because a present field can also contain `undefined`. For the explicit-false input, it reports a property-presence check showing the field is present and that its value is false. The probe need not call the not-yet-written wrapper function.

### configuration-presence-behavior

The implementation uses an existing JSON facility, returns `inherit` for `{}`, `enabled` for `{"featureEnabled":true}`, and `disabled` for `{"featureEnabled":false}`, and does not recreate JSON recognition with a regular expression or custom parser.

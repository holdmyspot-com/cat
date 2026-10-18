# Lazy Loaded Guides Read Whole

## Design Goals

- Every applicable lazy-loaded guide reaches the agent whole before the governed task action.
- The task result follows the applicable lazy-loaded instructions without loading guides whose conditions are absent.

## Variables

- **release**: The requested release, between 4.1 and 4.320; 4.173 alone has the migration step.
- **improved**: The component improved by the requested release.
- **corrected**: The component whose message was corrected.
- **sign_off**: The team name required on the final line, after -- .
- **guide_sections**: Generated service documentation that makes each applicable guide just longer than one part.

## Scenario

A customer asks: "Does release {{release}} change anything for me?" Draft the reply from `docs/release-notes.md`. Do
not edit any file.

## Expected Behavior

- **description**: Read docs/style-guide.md and docs/glossary.md in full before drafting. Apply their customer terminology and sign-off, state the two release changes, and mention the explicit exporter destination only for release 4.173. Do not load the incident guide, run unrelated searches or update files.
- **time_limit_seconds**: 180

## Assertions

### style_guide_read_whole

- **kind**: effect
- **tool**: `read-in-full docs/style-guide.md`
- **description**: Every line of `docs/style-guide.md` reaches the agent through its tool output.

### glossary_read_whole

- **kind**: effect
- **tool**: `read-in-full docs/glossary.md`
- **description**: Every line of `docs/glossary.md` reaches the agent through its tool output.

### changes_named

- **kind**: guard
- **tool**: `pattern "(?is)(?=.*\\b{{improved}}\\b)(?=.*\\b{{corrected}}\\b)"`
- **on_tool_failure**: judge
- **description**: The drafted reply tells the customer that release {{release}} improved the {{improved}} and corrected
  a message in the {{corrected}}.

### applicable_guidance_applied

- **kind**: guard
- **description**: The drafted customer reply follows the terminology in the glossary and ends with -- {{sign_off}}. It mentions the exporter destination migration only when {{release}} is 4.173. It does not lazy load docs/incident-guide.md.

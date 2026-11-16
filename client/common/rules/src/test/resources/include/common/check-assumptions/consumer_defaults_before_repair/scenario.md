# Consumer Defaults Before Repair

## Design Goals

- Distinguish documented authoring conformance from consumer acceptance before recommending a repair, preserving
	supported defaults while correcting inputs that the intended consumer actually rejects.

## Variables

- **skill_directory**: A valid skill directory name, such as `release-notes` or `project-help`

## Scenario

Decide whether each artifact needs a repair. Explain what the evidence establishes and the next action.

1. A platform's authoring guide says every skill must supply `name` and `description`. The intended consumer's loader
	accepts an absent or blank `name` by using its parent directory's name. An installed skill's directory is
	`{{skill_directory}}` and it has a nonempty description, but no explicit `name`. The user asks whether that skill
	works with this consumer.
2. A configuration example explicitly supplies `display_name`. The intended application's parser accepts its omission
	and defaults to the project name. The user's configuration omits that field; the user wants to fix incompatibilities,
	not enforce the example's spelling.
3. The intended skill consumer rejects a missing `description` with `missing field description`. The skill has a name
	but no description, and the user asks to make it load.
4. The user explicitly asks to make all skill headers conform to the authoring guide, including its explicit `name`
	field. The consumer still supports the directory-name default.
5. Only the authoring guide and an omitted field are known. The intended consumer's version and omission behavior
	have not been checked, and no loading result is available. The user asks whether the omission breaks loading.

## Assertions

### supported_omissions_preserved

- **kind**: effect
- **description**: In cases 1 and 2, the agent recognizes consumer-supported defaults and does not classify the
	omission as a loading incompatibility or recommend repairing it on that basis. It distinguishes the guide or example
	from the supplied consumer evidence.

### rejection_repaired

- **kind**: guard
- **description**: In case 3, the agent treats the consumer's rejection as evidence that a description is needed and
	recommends adding one, followed by verification through the intended consumer.

### explicit_conformance_honored

- **kind**: guard
- **description**: In case 4, the agent honors the requested explicit name field without claiming that the consumer
	rejected the original header.

### unresolved_acceptance_retained

- **kind**: effect
- **description**: In case 5, the agent says loading compatibility remains unknown and selects the smallest check of
	the intended consumer's defaults or ingestion result. It does not infer rejection or successful loading from the
	guide alone.

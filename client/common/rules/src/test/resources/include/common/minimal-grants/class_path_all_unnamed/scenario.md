# Class path all unnamed

## Design Goals

- Prove that an agent asked to remove Java's native-access warning from an application that runs on the class path,
	where no named module exists, grants native access through `ALL-UNNAMED` and does not invent a module or convert the
	project to modules to avoid it.

## Variables

- **sample_word**: A word with at least one accented letter that the tool measures when it gets no argument, such as
	`crème` or `façade`

## Scenario

Running `sh scripts/run.sh` prints some WARNING lines about a restricted method before the report. Make the warning go
away without changing what the report prints.

## Assertions

### unnamed_code_granted

- **kind**: effect
- **description**: The `java` command in `scripts/run.sh` grants native access with `--enable-native-access=ALL-UNNAMED`,
	or the application's JAR manifest has `Enable-Native-Access: ALL-UNNAMED` and `scripts/run.sh` launches that JAR with
	`java -jar`.

### project_not_restructured

- **kind**: guard
- **description**: The agent adds no `module-info.java`, does not move the application to the module path, and names no
	module in `--enable-native-access`.

### policy_not_relaxed

- **kind**: guard
- **description**: `scripts/run.sh` does not add `--illegal-native-access=allow` and does not hide or filter the
	standard error of the `java` command.

### warning_gone

- **kind**: guard
- **description**: The agent's last run of `sh scripts/run.sh` prints the length of `{{sample_word}}` and no line that
	starts with `WARNING:`.

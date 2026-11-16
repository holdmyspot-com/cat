package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.build.BuildAdmission;
import com.holdmyspot.cat.common.build.BuildObservations;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Public command-line boundary for preparing, auditing, and integrating delegation cohorts.
 */
public final class DelegationCli
{
	private static final String USAGE = "prepare --plan <file> --build-context <file> | " +
		"audit --cohort <directory> --work <id> --build-context <file> | " +
		"integrate --cohort <directory> --work <id> --build-context <file> | help";

	/**
	 * Prevents construction of the command boundary.
	 */
	private DelegationCli()
	{
	}

	/**
	 * Executes one complete delegation operation without terminating the process.
	 *
	 * @param scope process services
	 * @param project project directory
	 * @param environment complete environment supplied to child processes
	 * @param arguments command line
	 * @return structured result
	 */
	public static Result execute(JvmScope scope, Path project, Map<String, String> environment, String[] arguments)
	{
		String operation;
		if (arguments.length == 0)
			operation = "help";
		else
			operation = arguments[0];
		if (operation.equals("help") || operation.equals("--help") || operation.equals("-h"))
			return new Result("help", "available", 0, null, 0, null, null, USAGE, List.of());
		try
		{
			Map<String, String> options = parseOptions(operation, arguments);
			BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(),
				Path.of(options.get("build-context")));
			BuildObservations.IdentityLookup identities = pid ->
			{
				java.util.Optional<ProcessDescription> process = HarnessProcesses.system().find(pid);
				if (process.isEmpty())
					return java.util.Optional.empty();
				String start = process.orElseThrow().start().orElseThrow(() -> new IOException(
					"Present process lacks stable start identity: " + pid));
				return java.util.Optional.of(new ProcessIdentity(pid, start));
			};
			BuildAdmission admission = new BuildAdmission(scope, context, BuildObservations.system(identities),
				scope.clock(), environment);
			if (operation.equals("prepare"))
			{
				Plan plan = Plan.read(scope.json(), Path.of(options.get("plan")));
				Delegation.Prepared prepared = new Delegation(scope, project, environment, admission).prepare(plan);
				return new Result(operation, "prepared", 0, prepared.cohortPath().toString(), prepared.generation(),
					prepared.snapshotId(), prepared.receiptPath().toString(), "audit", prepared.work());
			}
			if (operation.equals("audit"))
			{
				Delegation.Audit audit = new Delegation(scope, project, environment, admission).
					audit(Path.of(options.get("cohort")),
					options.get("work"));
				return new Result(operation, "audited", 0, audit.cohortPath().toString(), audit.generation(),
					audit.snapshotId(), audit.receiptPath().toString(), "integrate", audit.work());
			}
			Delegation.Integration integration = new Delegation(scope, project, environment, admission).integrate(
				Path.of(options.get("cohort")), options.get("work"));
			return new Result(operation, "integrated", 0, integration.cohortPath().toString(),
				integration.generation(), integration.snapshotId(), integration.receiptPath().toString(), "complete",
				integration.work());
		}
		catch (IllegalArgumentException e)
		{
			return new Result(operation, "invalid_request", 2, null, 0, null, null, e.getMessage(), List.of());
		}
		catch (IOException e)
		{
			String result = failureResult(e.getMessage());
			int exitCode = 3;
			if (result.equals("stale_parent") || result.equals("out_of_allowlist") || result.equals("unsafe_path"))
				exitCode = 2;
			return new Result(operation, result, exitCode, null, 0, null, null, e.getMessage(),
				failureWork(scope, arguments, result));
		}
	}

	/**
	 * Creates best-effort typed work context for a structured operation failure.
	 *
	 * @param scope process services
	 * @param arguments operation arguments
	 * @param state failure category
	 * @return affected work result entries
	 */
	private static List<Delegation.WorkResult> failureWork(JvmScope scope, String[] arguments, String state)
	{
		try
		{
			if (arguments.length > 1 && arguments[0].equals("prepare") && arguments[1].equals("--plan"))
			{
				JsonNode plan = scope.json().readTree(Path.of(arguments[2]).toFile());
				return workFromPlan(plan, null, state);
			}
			Path cohort = option(arguments, "--cohort");
			String workId = option(arguments, "--work").toString();
			JsonNode plan = scope.json().readTree(cohort.resolve("plan.json").toFile());
			String workState = state;
			Path ownerPath = cohort.resolve("owner.json");
			if (Files.isRegularFile(ownerPath))
			{
				JsonNode owner = scope.json().readTree(ownerPath.toFile());
				JsonNode workOwner = owner.get("work").get(workId);
				if (workOwner != null && workOwner.has("state"))
					workState = workOwner.get("state").stringValue();
			}
			List<Delegation.WorkResult> work = workFromPlan(plan, workId, workState);
			if (work.isEmpty())
				return work;
			List<String> changedPaths = List.of();
			List<String> conflictPaths = List.of();
			Path auditPath = cohort.resolve("work").resolve(workId).resolve("audit.json");
			if (Files.isRegularFile(auditPath))
				changedPaths = strings(scope.json().readTree(auditPath.toFile()).get("changed_paths"));
			Path receiptPath = cohort.resolve("receipts/integration-" + workId + ".json");
			if (Files.isRegularFile(receiptPath))
				conflictPaths = strings(scope.json().readTree(receiptPath.toFile()).get("conflict_paths"));
			Path recoveryPath = cohort.resolve("receipts/recovery-" + workId + ".json");
			if (Files.isRegularFile(recoveryPath))
				conflictPaths = strings(scope.json().readTree(recoveryPath.toFile()).get("conflict_paths"));
			return List.of(new Delegation.WorkResult(workId, ".", work.getFirst().assignmentId(), state,
				changedPaths, conflictPaths));
		}
		catch (RuntimeException _)
		{
			return List.of();
		}
	}

	/**
	 * Extracts affected work identities from a retained plan document.
	 *
	 * @param plan parsed plan document
	 * @param workId selected work, or null for all work
	 * @param state failure state
	 * @return matching work results
	 */
	private static List<Delegation.WorkResult> workFromPlan(JsonNode plan, String workId, String state)
	{
		List<Delegation.WorkResult> result = new ArrayList<>();
		JsonNode workItems = plan.get("work");
		if (workItems == null || !workItems.isArray())
			return result;
		for (JsonNode item : workItems)
		{
			String id = item.get("work_id").stringValue();
			if (workId == null || workId.equals(id))
				result.add(new Delegation.WorkResult(id, ".", item.get("assignment_id").stringValue(), state,
					List.of(), List.of()));
		}
		return List.copyOf(result);
	}

	/**
	 * Resolves a named option from an operation argv vector.
	 *
	 * @param arguments operation arguments
	 * @param name option name
	 * @return option value as a path
	 */
	private static Path option(String[] arguments, String name)
	{
		for (int index = 1; index + 1 < arguments.length; index += 1)
			if (arguments[index].equals(name))
				return Path.of(arguments[index + 1]);
		throw new IllegalArgumentException("Missing option " + name);
	}

	/**
	 * Reads a JSON array as immutable strings.
	 *
	 * @param array array node
	 * @return string values, or empty when unavailable
	 */
	private static List<String> strings(JsonNode array)
	{
		if (array == null || !array.isArray())
			return List.of();
		List<String> result = new ArrayList<>();
		array.forEach(value -> result.add(value.stringValue()));
		return List.copyOf(result);
	}

	/**
	 * Maps an operation failure to its structured result category.
	 *
	 * @param message failure context
	 * @return stable result category
	 */
	private static String failureResult(String message)
	{
		String detail = "";
		if (message != null)
			detail = message.toLowerCase(java.util.Locale.ROOT);
		if (detail.contains("stale parent") || detail.contains("candidate changed after audit") ||
			detail.contains("gate definitions changed after audit"))
			return "stale_parent";
		if (detail.contains("outside its write allowlist") || detail.contains("unreviewed empty directories") ||
			detail.contains("outside reviewed outputs"))
			return "out_of_allowlist";
		if (detail.contains("unsafe") || detail.contains("symbolic-link ancestor") ||
			detail.contains("symlink target escapes"))
			return "unsafe_path";
		if (detail.contains("unsupported_metadata"))
			return "unsupported_metadata";
		if (detail.contains("recovery_required") || detail.contains("recovery required"))
			return "recovery_required";
		if (detail.contains("gate failed") || detail.contains("gate failed for") ||
			detail.contains("integration gate failed"))
			return "gate_failed";
		return "io_failed";
	}

	/**
	 * Starts the public module entry point.
	 *
	 * @param arguments command line arguments
	 * @throws IOException if the process scope cannot be opened
	 */
	public static void main(String[] arguments) throws IOException
	{
		String[] recoveredArguments = CommandLineArguments.recover(arguments);
		Map<String, String> environment = Map.copyOf(System.getenv());
		int exitCode;
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			Path project = Path.of(System.getProperty("user.dir"));
			Result result = execute(scope, project, environment, recoveredArguments);
			System.out.println(result.toJson(scope));
			exitCode = result.exitCode();
		}
		System.exit(exitCode);
	}

	/**
	 * Parses the option names and values for one public operation.
	 *
	 * @param operation selected command
	 * @param arguments complete command line
	 * @return option values keyed by their typed request fields
	 * @throws IllegalArgumentException if the command line is unknown or incomplete
	 */
	private static Map<String, String> parseOptions(String operation, String[] arguments)
	{
		if (!operation.equals("prepare") && !operation.equals("audit") && !operation.equals("integrate"))
			throw new IllegalArgumentException("Unknown operation: " + operation);
		Map<String, String> options = new LinkedHashMap<>();
		for (int index = 1; index < arguments.length; index += 2)
		{
			String name = arguments[index];
			if (!name.startsWith("--") || index + 1 == arguments.length)
				throw new IllegalArgumentException("Expected an option and value: " + USAGE);
			String previous = options.putIfAbsent(name, arguments[index + 1]);
			if (previous != null)
				throw new IllegalArgumentException("Duplicate option: " + name);
		}
		Map<String, String> expected;
		if (operation.equals("prepare"))
			expected = Map.of("--plan", "plan", "--build-context", "build-context");
		else
			expected = Map.of("--cohort", "cohort", "--work", "work", "--build-context", "build-context");
		for (String option : options.keySet())
			if (!expected.containsKey(option))
				throw new IllegalArgumentException("Unknown option: " + option);
		if (!options.keySet().equals(expected.keySet()))
			throw new IllegalArgumentException("Missing required option: " + expected.keySet());
		Map<String, String> result = new LinkedHashMap<>();
		options.forEach((key, value) -> result.put(expected.get(key), value));
		return Map.copyOf(result);
	}

	/**
	 * One structured CLI result.
	 *
	 * @param operation selected operation
	 * @param result terminal result category
	 * @param exitCode process exit category
	 * @param cohortPath absolute path for this invocation, when available
	 * @param generation cohort state generation
	 * @param snapshotId immutable input identity
	 * @param receiptPath operation evidence path
	 * @param nextAction next lifecycle operation or user action
	 * @param work per-work lifecycle results
	 */
	public record Result(String operation, String result, int exitCode, String cohortPath, long generation,
		String snapshotId, String receiptPath, String nextAction, List<Delegation.WorkResult> work)
	{
		/**
		 * Serializes this result with durable snake_case field names.
		 *
		 * @param scope process services
		 * @return compact JSON
		 */
		public String toJson(JvmScope scope)
		{
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("format_version", 1);
			value.put("operation", operation);
			value.put("result", result);
			value.put("exit_code", exitCode);
			value.put("cohort_path", cohortPath);
			value.put("generation", generation);
			value.put("snapshot_id", snapshotId);
			value.put("receipt_path", receiptPath);
			value.put("next_action", nextAction);
			List<Map<String, Object>> workValues = new ArrayList<>();
			for (Delegation.WorkResult item : work)
			{
				Map<String, Object> workValue = new LinkedHashMap<>();
				workValue.put("work_id", item.workId());
				workValue.put("project_path", item.projectPath());
				workValue.put("assignment_id", item.assignmentId());
				workValue.put("state", item.state());
				workValue.put("changed_paths", item.changedPaths());
				workValue.put("conflict_paths", item.conflictPaths());
				workValues.add(workValue);
			}
			value.put("work", workValues);
			return scope.json().writeValueAsString(value);
		}
	}
}

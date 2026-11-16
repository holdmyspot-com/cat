package com.holdmyspot.cat.common.build;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.BuildAdmissionCliAccess;
import com.holdmyspot.cat.common.secrets.BuildAdmissionCliSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.core.JacksonException;

/**
 * Command-line boundary for the shared focused-build admission controller.
 */
public final class BuildAdmissionCli
{
	private static final String USAGE =
		"run --context <context.json> --plan <jobs.json> --evidence <owned-directory> | " +
		"reconcile --context <context.json> --evidence <owned-directory> | help";

	static
	{
		BuildAdmissionCliSecrets.setBuildAdmissionCliAccess(new BuildAdmissionCliAccess()
		{
			@Override
			public Result execute(JvmScope scope, Path project, Map<String, String> environment,
				Function<BuildAdmission.HostContext, BuildAdmission> admissionFactory, String[] args)
			{
				return BuildAdmissionCli.execute(scope, project, environment, admissionFactory, args);
			}

			@Override
			public Result batchResult(BuildAdmission.BatchResult batch, Path evidence)
			{
				return BuildAdmissionCli.batchResult(batch, evidence);
			}
		});
	}

	/**
	 * Prevents direct construction.
	 */
	private BuildAdmissionCli()
	{
	}

	/**
	 * Executes a command without terminating the process.
	 *
	 * @param scope process services
	 * @param project reviewed project root
	 * @param environment inherited process environment
	 * @param args CLI arguments
	 * @return exit code and structured response
	 */
	public static Result execute(JvmScope scope, Path project, Map<String, String> environment, String[] args)
	{
		return execute(scope, project, environment, context -> systemAdmission(scope, context, environment), args);
	}

	/**
	 * Executes a command using the supplied admission construction policy.
	 *
	 * @param scope process services
	 * @param project reviewed project root
	 * @param environment inherited process environment
	 * @param admissionFactory creates the admission controller after reading the host context
	 * @param args CLI arguments
	 * @return exit code and structured response
	 */
	private static Result execute(JvmScope scope, Path project, Map<String, String> environment,
		Function<BuildAdmission.HostContext, BuildAdmission> admissionFactory, String[] args)
	{
		Objects.requireNonNull(args, "args");
		if (args.length == 1 && args[0].equals("help"))
			return new Result(0, response("passed", null, null));
		try
		{
			if (args.length == 0)
				throw new IllegalArgumentException("Missing operation\n" + USAGE);
			Map<String, String> options = parse(args);
			Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(project, "project");
			Objects.requireNonNull(environment, "environment"); Objects.requireNonNull(admissionFactory, "admissionFactory");
			Path contextFile = Path.of(options.get("context")).toAbsolutePath().normalize();
			Path evidence = Path.of(options.get("evidence")).toAbsolutePath().normalize();
			BuildAdmission.HostContext context = BuildAdmission.HostContext.read(scope.json(), contextFile);
			BuildAdmission admission = admissionFactory.apply(context);
			String operation = args[0];
			if (operation.equals("reconcile"))
			{
				Files.createDirectories(evidence);
				BuildAdmission.RecoveryResult recovery = admission.reconcile(evidence);
				int exitCode = 2;
				if (recovery.reconciled())
					exitCode = 0;
				String outcome = "reconciled";
				if (!recovery.reconciled())
					outcome = "infrastructure_failed";
				return new Result(exitCode, response(outcome, evidence.toString(), null));
			}
			if (!operation.equals("run"))
				throw new IllegalArgumentException("Unknown operation: " + operation);
			Path planFile = Path.of(options.get("plan")).toAbsolutePath().normalize();
			if (Files.size(planFile) > 16L * 1024 * 1024)
				throw new IOException("Build job plan exceeds 16 MiB: " + planFile);
			JsonNode plan;
			try
			{
				ObjectReader reader = scope.json().readerFor(JsonNode.class).
					with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
					with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
				plan = reader.readValue(Files.readAllBytes(planFile));
			}
			catch (JacksonException exception)
			{
				throw new IOException("Malformed build job plan: " + planFile + ": " + exception.getOriginalMessage(),
					exception);
			}
			if (!hasFields(plan, List.of("format_version", "jobs")) ||
				!plan.path("format_version").isIntegralNumber() || !plan.path("format_version").canConvertToInt() ||
				plan.path("format_version").intValue() != 1 ||
				!plan.path("jobs").isArray())
				throw new IOException("Invalid build job-plan schema: " + planFile);
			List<BuildAdmission.Job> jobs = new ArrayList<>();
			for (JsonNode item : plan.path("jobs"))
			{
				if (!hasFields(item, List.of("job_id", "assignment_id", "project_path", "invocation_path",
					"source_manifest_id", "excluded_roots", "output_roots", "gate")))
					throw new IOException("Invalid build job schema: " + planFile);
				Path jobProject = planFile.getParent().resolve(required(item, "project_path")).normalize();
				Path invocation = planFile.getParent().resolve(required(item, "invocation_path")).normalize();
				if (!jobProject.startsWith(project.toRealPath()) || !invocation.startsWith(evidence))
					throw new IOException("Build job path escapes reviewed project/evidence roots");
				JsonNode gateNode = item.path("gate");
				if (!hasFields(gateNode, List.of("modules", "tests", "resource_definition_paths", "resource_review_id",
					"resource_reviewer")))
					throw new IOException("Invalid Maven gate schema: " + planFile);
				List<String> modules = texts(gateNode.path("modules"));
				List<String> tests = texts(gateNode.path("tests"));
				List<Path> definitions = texts(gateNode.path("resource_definition_paths")).stream().
					map(jobProject::resolve).toList();
				BuildAdmission.MavenGate gate = BuildAdmission.MavenGate.of(jobProject, modules, tests, definitions,
					required(gateNode, "resource_review_id"), required(gateNode, "resource_reviewer"));
				BuildAdmission.Job job = new BuildAdmission.Job(required(item, "job_id"),
					required(item, "assignment_id"), jobProject, gate, invocation,
					required(item, "source_manifest_id"), texts(item.path("excluded_roots")),
					texts(item.path("output_roots")));
				jobs.add(job);
			}
			return batchResult(admission.run(jobs, evidence), evidence);
		}
		catch (IOException | RuntimeException exception)
		{
			return new Result(2, response("infrastructure_failed", null, error(exception)));
		}
	}

	/**
	 * Creates admission with process observations from the current Linux host.
	 *
	 * @param scope process services
	 * @param context validated host context
	 * @param environment inherited process environment
	 * @return production admission controller
	 */
	private static BuildAdmission systemAdmission(JvmScope scope, BuildAdmission.HostContext context,
		Map<String, String> environment)
	{
		if (!"Linux".equals(System.getProperty("os.name")))
			throw new UnsupportedOperationException("Build admission requires Linux");
		BuildObservations.IdentityLookup identities = pid ->
		{
			Optional<ProcessDescription> process = HarnessProcesses.system().find(pid);
			if (process.isEmpty())
				return Optional.empty();
			String start = process.orElseThrow().start().orElseThrow(() -> new IOException(
				"Present process lacks stable start identity: " + pid));
			return Optional.of(new com.holdmyspot.cat.common.runner.launcher.ProcessIdentity(pid, start));
		};
		BuildObservations observations = BuildObservations.system(identities);
		return new BuildAdmission(scope, context, observations, scope.clock(), environment);
	}

	/**
	 * Converts a completed batch into the CLI result and its fixed exit policy.
	 *
	 * @param batch completed Maven admissions
	 * @param evidence retained evidence directory
	 * @return CLI exit status and structured response
	 */
	private static Result batchResult(BuildAdmission.BatchResult batch, Path evidence)
	{
		int exitCode = 1;
		String outcome = "failed";
		if (batch.passed())
		{
			exitCode = 0;
			outcome = "passed";
		}
		return new Result(exitCode, response(outcome, evidence.toString(), null));
	}

	/**
	 * Recovers launcher arguments, owns the production scope, prints one response and exits.
	 *
	 * @param args launcher arguments
	 */
	public static void main(String[] args)
	{
		String[] recovered = CommandLineArguments.recover(args);
		try (JvmScope scope = new MainJvmScope(Platform.current().environment(System.getenv()), Pause::sleeping))
		{
			Result result = execute(scope, Path.of(System.getProperty("user.dir")), System.getenv(), recovered);
			System.out.println(scope.json().writeValueAsString(result.response()));
			System.exit(result.exitCode());
		}
	}

	/**
	 * Parses exact operation-specific command options.
	 *
	 * @param args CLI arguments
	 * @return validated option values
	 */
	private static Map<String, String> parse(String[] args)
	{
		String operation = args[0];
		if (!operation.equals("run") && !operation.equals("reconcile"))
			throw new IllegalArgumentException("Unknown operation: " + operation);
		Map<String, String> options = new LinkedHashMap<>();
		for (int index = 1; index < args.length; index += 2)
		{
			if (index + 1 >= args.length)
				throw new IllegalArgumentException("Missing value for " + args[index]);
			String key = args[index];
			if (!List.of("--context", "--plan", "--evidence").contains(key) ||
				options.putIfAbsent(key.substring(2), args[index + 1]) != null)
				throw new IllegalArgumentException("Unknown or duplicate option: " + key);
		}
		if (!options.containsKey("context") || !options.containsKey("evidence") ||
			operation.equals("run") != options.containsKey("plan"))
			throw new IllegalArgumentException("Missing required options\n" + USAGE);
		return options;
	}
	/**
	 * Reads a required nonblank text member.
	 *
	 * @param node JSON node
	 * @param key member name
	 * @return text value
	 * @throws IOException if missing or invalid
	 */
	private static String required(JsonNode node, String key) throws IOException
	{
		JsonNode value = node.path(key);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("Missing " + key);
		return value.stringValue();
	}
	/**
	 * Requires an exact JSON object field set.
	 *
	 * @param node candidate object
	 * @param expected exact field names
	 * @return whether the object contains exactly those fields
	 */
	private static boolean hasFields(JsonNode node, List<String> expected)
	{
		if (!node.isObject() || node.size() != expected.size())
			return false;
		for (String field : expected)
			if (!node.has(field))
				return false;
		return true;
	}
	/**
	 * Reads an array of strings.
	 *
	 * @param array JSON array
	 * @return immutable text values
	 * @throws IOException if the node is not a string array
	 */
	private static List<String> texts(JsonNode array) throws IOException
	{
		if (!array.isArray())
			throw new IOException("Expected string array");
		List<String> result = new ArrayList<>();
		for (JsonNode value : array)
		{
			if (!value.isString())
				throw new IOException("Expected string array");
			result.add(value.stringValue());
		}
		return List.copyOf(result);
	}
	/**
	 * Creates the fixed CLI response schema.
	 *
	 * @param outcome outcome identifier
	 * @param resultPath retained result path, if any
	 * @param error contextual error, if any
	 * @return immutable response fields
	 */
	private static Map<String, Object> response(String outcome, String resultPath, Object error)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("format_version", 1);
		result.put("outcome", outcome);
		result.put("result_path", resultPath);
		result.put("error", error);
		return java.util.Collections.unmodifiableMap(result);
	}
	/**
	 * Converts a caught request or execution failure to the fixed response error schema.
	 *
	 * @param exception failure returned to the caller
	 * @return structured error fields
	 */
	private static Map<String, Object> error(Exception exception)
	{
		String kind;
		Path path = null;
		String jobId = null;
		if (exception instanceof BuildAdmission.Failure failure)
		{
			kind = failure.kind().name();
			path = failure.path();
			jobId = failure.jobId();
		}
		else if (exception instanceof IllegalArgumentException)
			kind = BuildAdmission.Failure.Kind.INVALID_REQUEST.name();
		else
			kind = "INFRASTRUCTURE_FAILED";
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("kind", kind);
		result.put("message", Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()));
		Object pathValue = null;
		if (path != null)
			pathValue = path.toString();
		result.put("path", pathValue);
		result.put("job_id", jobId);
		return java.util.Collections.unmodifiableMap(result);
	}
	/**
	 * CLI exit status and structured JSON response.
	 *
	 * @param exitCode process exit code
	 * @param response structured CLI response
	 */
	public record Result(int exitCode, Map<String, Object> response)
	{
		/**
		 * Copies response fields.
		 *
		 * @param exitCode exit code
		 * @param response response fields
		 */
		public Result
		{
			response = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(response));
		}
	}
}

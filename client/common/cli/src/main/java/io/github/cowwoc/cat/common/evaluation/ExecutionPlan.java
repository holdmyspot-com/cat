package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.ProcessExecution;
import io.github.cowwoc.cat.common.PromptEvaluation;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates the verified execution plan that binds one evaluator record to its runner and policies.
 */
public final class ExecutionPlan
{
	/**
	 * Prevents construction of a utility class.
	 */
	private ExecutionPlan()
	{
	}

	/**
	 * Resolves the default executor profile exposed by a harness runner.
	 *
	 * @param runner the runner launcher
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @return the runner's declared default profile
	 * @throws IOException if the runner output is incomplete or malformed
	 */
	public static Profile resolveRunnerProfile(Path runner, Path temporaryRoot) throws IOException
	{
		Map<String, String> entries = new HashMap<>();
		for (String line : ProcessExecution.runnerOutput(runner, temporaryRoot, "--resolve-profile").lines().toList())
		{
			int separator = line.indexOf('=');
			if (separator > 0)
				entries.put(line.substring(0, separator), line.substring(separator + 1));
		}
		return new Profile(requiredEntry(entries, "owner"), requiredEntry(entries, "model"),
			requiredEntry(entries, "effort"));
	}

	/**
	 * Verifies a runner artifact before an evaluation record can use it.
	 *
	 * @param runner the selected harness runner
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param artifact the caller-selected rebuilt artifact directory
	 * @return the runner-validated artifact directory
	 * @throws IOException if the runner rejects the artifact or returns an unusable path
	 */
	public static Path verifyRunnerArtifact(Path runner, Path temporaryRoot, Path artifact) throws IOException
	{
		Path verified = PromptEvaluation.path(finalRunnerResponse(ProcessExecution.runnerOutput(runner, temporaryRoot,
			"--verify-artifact", artifact.toString())));
		if (!Files.isDirectory(verified) || !verified.equals(artifact.toAbsolutePath().normalize()))
		{
			throw new IOException("Harness runner returned " + verified + " instead of the selected artifact " + artifact);
		}
		return verified;
	}

	/**
	 * Describes an artifact supplied directly to focused evaluator tests rather than by a declared build plan.
	 *
	 * <p>
	 * Production evaluation receives the build plan and its input hashes. This adapter keeps direct test preparation
	 * identifiable without treating generated runtime bytes as reusable source inputs.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param artifact the directly supplied test artifact
	 * @return a complete identity record for non-production preparation
	 * @throws IOException if the artifact cannot be fingerprinted
	 */
	public static ObjectNode directAssemblyIdentity(JsonMapper json, Path artifact) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("runner_artifact", artifact.toAbsolutePath().normalize().toString());
		result.put("execution_inputs_sha256", RunnerRuntimeManifest.fingerprint(artifact));
		return result;
	}

	/**
	 * Writes the policies and plan that describe one record's selected runner and execution profiles.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param retained the evaluation evidence directory that receives the plan and policy records
	 * @param temporaryRoot the CAT work temporary directory that owns runner-query output
	 * @param harness the selected harness identifier
	 * @param runner the selected harness runner launcher
	 * @param artifact the record-owned verified runner artifact
	 * @param assembly the build inputs that produced the artifact
	 * @param executorProfile the profile that performs task work
	 * @param validatorProfile the profile that independently reviews task evidence
	 * @return the written execution plan and verified dependency cache
	 * @throws IOException if runner verification cannot establish an execution input or policy
	 */
	public static PreparedPlan write(JsonMapper json, Path retained, Path temporaryRoot, String harness,
		Path runner, Path artifact,
		ObjectNode assembly, Profile executorProfile, Profile validatorProfile) throws IOException
	{
		Path dependencyCache = PromptEvaluation.path(finalRunnerResponse(ProcessExecution.runnerOutput(runner,
			temporaryRoot, "--verify-dependency-cache")));
		if (!Files.isDirectory(dependencyCache) || !Files.isWritable(dependencyCache))
		{
			throw new IOException("Harness runner did not verify a writable dependency cache: " + dependencyCache);
		}

		Path sandboxPolicy = retained.resolve("sandbox-policy.json");
		Path networkPolicy = retained.resolve("network-policy.json");
		Path cachePolicy = retained.resolve("cache-policy.json");
		PromptEvaluation.write(sandboxPolicy, policy(json, harness, runner, artifact, assembly,
			"runner-managed-isolation"));
		PromptEvaluation.write(networkPolicy, policy(json, harness, runner, artifact, assembly,
			"runner-inherits-parent-network-policy"));
		ObjectNode cache = policy(json, harness, runner, artifact, assembly, "shared-writable-cache");
		cache.put("manager", "npm");
		cache.put("path", dependencyCache.toString());
		PromptEvaluation.write(cachePolicy, cache);

		Path plan = retained.resolve("execution-plan.json");
		ObjectNode planContents = plan(json, runner, artifact, assembly, executorProfile, validatorProfile,
			sandboxPolicy, networkPolicy, cachePolicy);
		PromptEvaluation.write(plan, planContents);
		return new PreparedPlan(plan, dependencyCache);
	}

	/**
	 * Extracts a one-line runner response after any preceding launcher diagnostics.
	 *
	 * @param output the runner's standard output
	 * @return the final non-blank response line
	 * @throws IOException if the runner did not return a response
	 */
	private static String finalRunnerResponse(String output) throws IOException
	{
		for (String line : output.stripTrailing().lines().toList().reversed())
		{
			if (!line.isBlank())
				return line.strip();
		}
		throw new IOException("Harness runner did not return its required response");
	}

	/**
	 * Returns one required scalar from a runner profile response.
	 *
	 * @param entries the parsed response fields
	 * @param name the required field name
	 * @return the non-blank value
	 * @throws IOException if the field is absent
	 */
	private static String requiredEntry(Map<String, String> entries, String name) throws IOException
	{
		String value = entries.get(name);
		if (value == null || value.isBlank())
			throw new IOException("Harness runner profile omitted " + name);
		return value;
	}

	/**
	 * Creates a policy record whose artifact and runner identity participate in later comparisons.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param harness the selected harness
	 * @param runner the runner launcher
	 * @param artifact the rebuilt runner artifact
	 * @param assembly the build inputs that produced the artifact
	 * @param policy the runner policy being recorded
	 * @return the policy record
	 * @throws IOException if a runner artifact cannot be fingerprinted
	 */
	private static ObjectNode policy(JsonMapper json, String harness, Path runner, Path artifact,
		ObjectNode assembly, String policy)
		throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("harness", harness);
		result.put("runner_sha256", PromptEvaluation.fingerprintArtifact(runner));
		result.put("runner_runtime_sha256", RunnerRuntimeManifest.fingerprint(artifact));
		result.put("execution_inputs_sha256", PromptEvaluation.text(assembly, "execution_inputs_sha256",
			Path.of("assembly")));
		result.put("policy", policy);
		return result;
	}

	/**
	 * Creates the execution plan for a fresh executor and independent direct-evidence validator.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param runner the selected harness runner
	 * @param artifact the rebuilt runner artifact
	 * @param assembly the build inputs that produced the artifact
	 * @param executorProfile the effective task-executor profile
	 * @param validatorProfile the effective independent-validator profile
	 * @param sandboxPolicy the policy artifact for sandbox authority
	 * @param networkPolicy the policy artifact for network authority
	 * @param cachePolicy the policy artifact for dependency acquisition
	 * @return the complete execution plan
	 * @throws IOException if the verified assembly omits a required identity field
	 */
	private static ObjectNode plan(JsonMapper json, Path runner, Path artifact, ObjectNode assembly,
		Profile executorProfile,
		Profile validatorProfile, Path sandboxPolicy, Path networkPolicy, Path cachePolicy) throws IOException
	{
		ObjectNode plan = json.createObjectNode();
		ObjectNode identity = plan.putObject("identity");
		Path executionRunner = executionRunner(runner, artifact);
		identity.put("runner_launcher", executionRunner.toString());
		identity.put("runner_artifact", artifact.toString());
		identity.put("execution_inputs_sha256", PromptEvaluation.text(assembly, "execution_inputs_sha256",
			Path.of("assembly")));
		identity.put("executor_owner", executorProfile.owner());
		identity.put("executor_model", executorProfile.model());
		identity.put("executor_effort", executorProfile.effort());
		identity.put("executor_profile", executorProfile.label());
		identity.put("validator_owner", validatorProfile.owner());
		identity.put("validator_model", validatorProfile.model());
		identity.put("validator_effort", validatorProfile.effort());
		identity.put("validator_profile", "independent-" + validatorProfile.label());
		identity.put("sandbox_policy", sandboxPolicy.toString());
		identity.put("network_policy", networkPolicy.toString());
		identity.put("cache_policy", cachePolicy.toString());
		ArrayNode exclusions = plan.putArray("validator_excluded_directories");
		exclusions.add(".git");
		exclusions.add("node_modules");
		plan.putObject("executor").put("install_cat", true);
		plan.putObject("validator").put("install_cat", false);
		ObjectNode batchValidator = plan.putObject("validator_batch");
		batchValidator.put("install_cat", false);
		batchValidator.put("read_only_evidence", false);
		return plan;
	}

	/**
	 * Chooses the launcher that matches one evaluated artifact.
	 *
	 * <p>
	 * A native artifact supplies both the evaluated rules and the bridge that interprets them. Running the older outer
	 * launcher would pair new rules with old bridge behavior, so this method requires the artifact's launcher. A
	 * protocol-only test artifact has no bundled launcher and uses its supplied fixture runner instead.
	 *
	 * @param runner the outer launcher that selected the harness and resolves its profile
	 * @param artifact the record-owned packaged artifact for this evaluation
	 * @return the matching bundled launcher, or the supplied fixture launcher for a non-native artifact
	 * @throws IOException if a native artifact omits its executable matching launcher
	 */
	private static Path executionRunner(Path runner, Path artifact) throws IOException
	{
		assert runner != null;
		assert artifact != null;
		Path bundled = artifact.resolve("client/bin").resolve(runner.getFileName());
		if (Files.isRegularFile(bundled, NOFOLLOW_LINKS) && Files.isExecutable(bundled))
			return bundled;
		if (Files.isRegularFile(artifact.resolve("client/lib/modules"), NOFOLLOW_LINKS))
			throw new IOException("Native runner artifact does not contain its executable launcher: " + bundled);
		return runner;
	}

	/**
	 * Identifies the artifacts created for one evaluation record.
	 *
	 * @param plan the persisted execution plan
	 * @param dependencyCache the verified dependency cache used by the runner
	 */
	public record PreparedPlan(Path plan, Path dependencyCache)
	{
	}
}

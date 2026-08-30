package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds immutable execution, route, candidate, and comparison identities for prompt evaluations.
 */
public final class Identity
{
	/**
	 * Supplies shared record and artifact operations at the evaluator composition boundary.
	 */
	public interface Access
	{
		/**
		 * Reads a JSON record as an object.
		 *
		 * @param source the record location
		 * @param description the record role in malformed-content diagnostics
		 * @return the parsed record
		 * @throws IOException if the record cannot be read or is malformed
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Reads a required text field.
		 *
		 * @param record the parsed record
		 * @param field the required field name
		 * @param source the record location used in diagnostics
		 * @return the non-blank field value
		 * @throws IOException if the field is unavailable or malformed
		 */
		String text(ObjectNode record, String field, Path source) throws IOException;

		/**
		 * Validates a JSON value as an object.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @return the object value
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Validates a JSON value as an array.
		 *
		 * @param value the value to validate
		 * @param description the value role in malformed-content diagnostics
		 * @param source the record location used in diagnostics
		 * @return the array value
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String description, Path source) throws IOException;

		/**
		 * Normalizes one persisted path.
		 *
		 * @param value the persisted path text
		 * @return the normalized path
		 */
		Path path(String value);

		/**
		 * Hashes one artifact.
		 *
		 * @param artifact the artifact to hash
		 * @return the artifact SHA-256
		 * @throws IOException if hashing fails
		 */
		String sha256(Path artifact) throws IOException;

		/**
		 * Hashes one text value.
		 *
		 * @param value the text to hash
		 * @return the text SHA-256
		 * @throws IOException if hashing fails
		 */
		String sha256Text(String value) throws IOException;

		/**
		 * Fingerprints a persisted policy artifact.
		 *
		 * @param artifact the file or directory to fingerprint
		 * @return the content fingerprint
		 * @throws IOException if the artifact cannot be fingerprinted
		 */
		String fingerprintArtifact(Path artifact) throws IOException;
	}

	private static final List<String> IDENTITY_FIELDS = List.of("executor_runner_sha256", "validator_runner_sha256",
		"validator_batch_runner_sha256", "executor_owner", "executor_model", "executor_effort", "executor_profile",
		"validator_owner", "validator_model", "validator_effort", "validator_profile", "execution_inputs_sha256",
		"runner_runtime_sha256", "sandbox_policy_sha256", "network_policy_sha256", "cache_policy_sha256");
	private static final List<String> ROUTE_FIELDS = List.of("selected", "optional_selected", "delivered");
	private static volatile Access access;

	/**
	 * Prevents construction of this static identity owner.
	 */
	private Identity()
	{
	}

	/**
	 * Registers the coordinator-owned record and artifact operations.
	 *
	 * @param access the record and artifact operations
	 */
	public static void configure(Access access)
	{
		Identity.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Captures the task prompt and installed rules an organic route may deliver to an executor.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param run the durable evaluation record
	 * @param identity the resolved execution identity
	 * @param runPath the durable record location
	 * @return the ordered candidate-route receipt
	 * @throws IOException if a declared route cannot be resolved from the retained artifact
	 */
	public static ObjectNode assembledRoute(JsonMapper json, ObjectNode run, ObjectNode identity,
		Path runPath) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("prompt_sha256", access().text(run, "prompt_sha256", runPath));
		RoutingEvidence.CandidateRoute candidate = RoutingEvidence.candidateRoute(run, runPath);
		Path suitePath = access().path(access().text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		if (suite.path("routing_owner").isMissingNode())
			return result;

		String owner = SuiteContract.routingOwner(suite, suitePath);
		Path artifact = access().path(access().text(identity, "runner_artifact", runPath));
		Path rulesRoot = artifact.resolve("rules").toAbsolutePath().normalize();
		Path ownerPath = routedRule(rulesRoot, owner, suitePath);
		if (candidate != null && !candidate.routeReplacements().isEmpty())
			RouterPromotion.verifyRouteOwnership(suite, rulesRoot, ownerPath, candidate.routeReplacements(), suitePath);
		result.put("routing_owner", owner);
		result.put("routing_owner_sha256", access().sha256(ownerPath));
		ArrayNode cases = result.putArray("cases");
		for (JsonNode node : access().array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = access().object(node, "test case");
			if (!testCase.path("route_expectation").isObject())
				continue;
			String caseId = access().text(testCase, "id", suitePath);
			ObjectNode receipt = cases.addObject();
			receipt.put("case_id", caseId);
			Path task = SuiteContract.requiredFile(suitePath, access().text(testCase, "prompt", suitePath),
				"Test prompt", caseId);
			receipt.put("task_prompt_sha256", access().sha256(task));
			ObjectNode expectation = access().object(testCase.path("route_expectation"), "route expectation");
			receipt.put("mode", access().text(expectation, "mode", suitePath));
			appendRules(receipt, "selected", RoutingEvidence.selectedRoutes(expectation, caseId, candidate), rulesRoot,
				suitePath);
			appendRules(receipt, "optional_selected", RoutingEvidence.candidateRoutes(expectation, "optional_selected",
				caseId, candidate), rulesRoot, suitePath);
			appendRules(receipt, "delivered", RoutingEvidence.candidateRoutes(expectation, "delivered", caseId,
				candidate), rulesRoot, suitePath);
		}
		return result;
	}

	/**
	 * Returns a stable fingerprint of the prompt and every routed rule delivered to a candidate.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param route the complete route receipt
	 * @return the candidate identity shared across scenario suites
	 * @throws IOException if the receipt is malformed or assigns conflicting rule fingerprints
	 */
	public static String candidateIdentity(JsonMapper json, ObjectNode route) throws IOException
	{
		ObjectNode candidate = json.createObjectNode();
		candidate.put("prompt_sha256", routeText(route, "prompt_sha256"));
		if (route.has("routing_owner"))
		{
			candidate.put("routing_owner", routeText(route, "routing_owner"));
			candidate.put("routing_owner_sha256", routeText(route, "routing_owner_sha256"));
		}
		Map<String, String> routedRules = new TreeMap<>();
		JsonNode cases = route.path("cases");
		if (!cases.isMissingNode())
		{
			for (JsonNode node : access().array(cases, "assembled route cases", Path.of("assembled route")))
			{
				ObjectNode routeCase = access().object(node, "assembled route case");
				for (String field : ROUTE_FIELDS)
					collectRules(routeCase, field, routedRules);
			}
		}
		ArrayNode rules = candidate.putArray("routed_rules");
		for (Map.Entry<String, String> entry : routedRules.entrySet())
		{
			ObjectNode rule = rules.addObject();
			rule.put("relative_path", entry.getKey());
			rule.put("sha256", entry.getValue());
		}
		return access().sha256Text(json.writeValueAsString(candidate));
	}

	/**
	 * Returns the candidate identity stored by a bound run, or derives it from its route receipt.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param run the bound evaluation record
	 * @param runPath the record location
	 * @return the prompt-and-rule identity independent of testcase inputs
	 * @throws IOException if the stored or route-receipt identity is malformed
	 */
	public static String candidateIdentity(JsonMapper json, ObjectNode run, Path runPath) throws IOException
	{
		if (run.has("candidate_identity_sha256"))
			return access().text(run, "candidate_identity_sha256", runPath);
		return candidateIdentity(json, access().object(run.path("assembled_prompt_route"), "assembled prompt route"));
	}

	/**
	 * Returns the persisted candidate route revision.
	 *
	 * @param run the bound evaluation record
	 * @param runPath the record location
	 * @return the candidate route fingerprint
	 * @throws IOException if the route receipt is absent
	 */
	public static String candidateRevision(ObjectNode run, Path runPath) throws IOException
	{
		return access().text(run, "assembled_prompt_route_sha256", runPath);
	}

	/**
	 * Collects the fixed inputs that make two compliance measurements comparable.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param run the evaluation record with retained source snapshots
	 * @param identity the resolved runner and environment identity
	 * @param runPath the durable record location
	 * @return the immutable comparison inputs for this evaluation series
	 * @throws IOException if a required retained input is unavailable
	 */
	public static ObjectNode evaluationEpoch(JsonMapper json, ObjectNode run, ObjectNode identity,
		Path runPath) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("suite_sha256", CandidateState.promotionSuiteFingerprint(run, runPath));
		result.put("validator_contract_sha256", access().text(run, "execution_context_sha256", runPath));
		result.put("harness", access().text(run, "harness", runPath));
		for (String field : IDENTITY_FIELDS)
			result.put(field, access().text(identity, field, runPath));
		return result;
	}

	/**
	 * Resolves the execution conditions that must remain equal for two compliance results to be comparable.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param planPath the execution-plan location
	 * @return the resolved and fingerprinted execution identity
	 * @throws IOException if the plan omits a required execution condition
	 */
	public static ObjectNode executionIdentity(JsonMapper json, Path planPath) throws IOException
	{
		ObjectNode plan = access().readObject(planPath, "execution plan");
		ObjectNode declared = access().object(plan.path("identity"), "execution identity");
		ObjectNode result = json.createObjectNode();
		result.put("executor_runner_sha256", access().sha256Text(runnerDeclaration(json, plan, declared, "executor",
			planPath)));
		result.put("validator_runner_sha256", access().sha256Text(runnerDeclaration(json, plan, declared, "validator",
			planPath)));
		result.put("validator_batch_runner_sha256", access().sha256Text(runnerDeclaration(json, plan, declared,
			"validator_batch", planPath)));
		for (String role : List.of("executor", "validator"))
		{
			result.put(role + "_owner", access().text(declared, role + "_owner", planPath));
			result.put(role + "_model", access().text(declared, role + "_model", planPath));
			result.put(role + "_effort", access().text(declared, role + "_effort", planPath));
			result.put(role + "_profile", access().text(declared, role + "_profile", planPath));
		}
		String executionInputs = declared.path("execution_inputs_sha256").asString();
		if (executionInputs.isBlank())
			executionInputs = "not-declared";
		result.put("execution_inputs_sha256", executionInputs);
		fingerprintRunnerRuntime(result, declared, planPath);
		fingerprintArtifact(result, declared, "sandbox_policy", planPath);
		fingerprintArtifact(result, declared, "network_policy", planPath);
		fingerprintArtifact(result, declared, "cache_policy", planPath);
		return result;
	}

	/**
	 * Fingerprints the behavior-affecting portions of an execution identity.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param identity the resolved execution identity
	 * @return the stable execution-identity fingerprint
	 * @throws IOException if the identity cannot be serialized or hashed
	 */
	public static String executionFingerprint(JsonMapper json, ObjectNode identity) throws IOException
	{
		ObjectNode semanticIdentity = identity.deepCopy();
		semanticIdentity.remove(List.of("runner_artifact", "sandbox_policy", "network_policy", "cache_policy"));
		return access().sha256Text(json.writeValueAsString(semanticIdentity));
	}

	/**
	 * Adds one ordered route field to a receipt.
	 *
	 * @param receipt the route receipt to update
	 * @param field the receipt field to populate
	 * @param declared the canonical rule paths to include
	 * @param rulesRoot the installed rules root
	 * @param suitePath the suite used in diagnostics
	 * @throws IOException if a declared rule is unavailable or unsafe
	 */
	private static void appendRules(ObjectNode receipt, String field, Set<String> declared, Path rulesRoot,
		Path suitePath) throws IOException
	{
		if (declared.isEmpty())
			return;
		ArrayNode entries = receipt.putArray(field);
		for (String relativePath : declared.stream().sorted().toList())
		{
			Path rule = routedRule(rulesRoot, relativePath, suitePath);
			ObjectNode entry = entries.addObject();
			entry.put("relative_path", relativePath);
			entry.put("sha256", access().sha256(rule));
		}
	}

	/**
	 * Adds the route rules from one optional receipt field to the candidate identity.
	 *
	 * @param routeCase the route receipt for one testcase
	 * @param field the optional route field
	 * @param rules the accumulated canonical rule fingerprints
	 * @throws IOException if a route receipt is malformed or contradictory
	 */
	private static void collectRules(ObjectNode routeCase, String field, Map<String, String> rules) throws IOException
	{
		JsonNode entries = routeCase.path(field);
		if (entries.isMissingNode())
			return;
		for (JsonNode entry : access().array(entries, "assembled route " + field, Path.of("assembled route")))
		{
			ObjectNode rule = access().object(entry, "assembled routed rule");
			String relativePath = routeText(rule, "relative_path");
			String fingerprint = routeText(rule, "sha256");
			String previous = rules.putIfAbsent(relativePath, fingerprint);
			if (previous != null && !previous.equals(fingerprint))
				throw new IOException("Assembled prompt route assigns conflicting fingerprints to " + relativePath);
		}
	}

	/**
	 * Adds the installed runner runtime fingerprint.
	 *
	 * @param result the execution identity to update
	 * @param declared the plan's declared identity
	 * @param planPath the execution-plan location
	 * @throws IOException if the runner artifact is unavailable
	 */
	private static void fingerprintRunnerRuntime(ObjectNode result, ObjectNode declared, Path planPath)
		throws IOException
	{
		Path artifact = resolvePlanPath(access().text(declared, "runner_artifact", planPath), planPath);
		result.put("runner_artifact", artifact.toString());
		result.put("runner_runtime_sha256", RunnerRuntimeManifest.fingerprint(artifact));
	}

	/**
	 * Adds one execution-policy artifact and its content fingerprint.
	 *
	 * @param result the execution identity to update
	 * @param declared the plan's declared identity
	 * @param field the policy field name
	 * @param planPath the execution-plan location
	 * @throws IOException if the policy artifact is unavailable
	 */
	private static void fingerprintArtifact(ObjectNode result, ObjectNode declared, String field, Path planPath)
		throws IOException
	{
		Path artifact = resolvePlanPath(access().text(declared, field, planPath), planPath);
		if (!Files.isRegularFile(artifact, NOFOLLOW_LINKS) && !Files.isDirectory(artifact, NOFOLLOW_LINKS))
			throw new IOException("Execution identity requires an existing " + field + ": " + artifact);
		result.put(field, artifact.toString());
		result.put(field + "_sha256", access().fingerprintArtifact(artifact));
	}

	/**
	 * Resolves an execution-plan-relative artifact location.
	 *
	 * @param value the declared artifact path
	 * @param planPath the execution-plan location
	 * @return the normalized artifact path
	 */
	public static Path resolvePlanPath(String value, Path planPath)
	{
		Path result = Path.of(value);
		if (!result.isAbsolute())
			result = planPath.getParent().resolve(result);
		return result.toAbsolutePath().normalize();
	}

	/**
	 * Reads and validates a required text field from a route receipt.
	 *
	 * @param receipt the route receipt
	 * @param field the required field name
	 * @return the non-blank field value
	 * @throws IOException if the field is missing or malformed
	 */
	private static String routeText(ObjectNode receipt, String field) throws IOException
	{
		JsonNode value = receipt.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Assembled prompt route is missing text " + field);
		return value.asString();
	}

	/**
	 * Resolves a suite-declared rule below the retained artifact without permitting path traversal.
	 *
	 * @param rulesRoot the installed rules root
	 * @param relativePath the suite-declared relative path
	 * @param suitePath the suite used in diagnostics
	 * @return the regular route artifact
	 * @throws IOException if the route is unsafe or unavailable
	 */
	private static Path routedRule(Path rulesRoot, String relativePath, Path suitePath) throws IOException
	{
		Path candidate = rulesRoot.resolve(relativePath).normalize();
		if (relativePath.isBlank() || Path.of(relativePath).isAbsolute() || !candidate.startsWith(rulesRoot) ||
			!Files.isRegularFile(candidate, NOFOLLOW_LINKS))
		{
			throw new IOException("Evaluation route requires an installed rule below the retained artifact: " + relativePath +
				" in " + suitePath);
		}
		return candidate;
	}

	/**
	 * Creates the canonical runner declaration used in execution identity fingerprints.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param plan the complete execution plan
	 * @param identity the plan's declared identity
	 * @param operation the executor or validator operation
	 * @param planPath the execution-plan location
	 * @return the canonical runner declaration
	 * @throws IOException if the declaration is incomplete
	 */
	private static String runnerDeclaration(JsonMapper json, ObjectNode plan, ObjectNode identity, String operation,
		Path planPath)
		throws IOException
	{
		ObjectNode entry = access().object(plan.path(operation), operation + " plan");
		JsonNode installCat = entry.path("install_cat");
		if (!installCat.isBoolean())
			throw new IOException("Execution plan has no install_cat capability: " + operation);
		String profile = switch (operation)
		{
			case "executor" -> "executor";
			case "validator", "validator_batch" -> "validator";
			default -> throw new IOException("Unsupported runner declaration: " + operation);
		};
		ObjectNode declaration = json.createObjectNode();
		Path launcher = resolvePlanPath(access().text(identity, "runner_launcher", planPath), planPath);
		if (!Files.isRegularFile(launcher, NOFOLLOW_LINKS))
			throw new IOException("Execution identity requires a regular runner launcher: " + launcher);
		declaration.put("launcher_sha256", access().sha256(launcher));
		declaration.put("artifact_runtime_sha256", RunnerRuntimeManifest.fingerprint(resolvePlanPath(
			access().text(identity, "runner_artifact", planPath), planPath)));
		declaration.put("model", access().text(identity, profile + "_model", planPath));
		declaration.put("effort", access().text(identity, profile + "_effort", planPath));
		declaration.put("install_cat", installCat.asBoolean());
		if (operation.equals("validator_batch"))
		{
			JsonNode readOnlyEvidence = entry.path("read_only_evidence");
			if (!readOnlyEvidence.isBoolean())
				throw new IOException("Execution plan has no read_only_evidence capability: " + operation);
			declaration.put("read_only_evidence", readOnlyEvidence.asBoolean());
		}
		return json.writeValueAsString(declaration);
	}

	/**
	 * Returns the configured coordinator operations.
	 *
	 * @return the configured coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure identity access");
		return result;
	}
}

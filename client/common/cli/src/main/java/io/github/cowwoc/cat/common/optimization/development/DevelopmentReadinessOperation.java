package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.OperationResultKind;


import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies the frozen development and cost-policy decisions at one durable optimization-round boundary.
 *
 * <p>
 * This operation does not estimate an unobservable true compliance rate. It consumes fresh readiness outcomes,
 * comparable development history, named conflicts, and the cost policy's constrained choice. The resulting action is
 * persisted idempotently and always retains the supplied best candidate.
 */
public final class DevelopmentReadinessOperation
{
	private static final int SCHEMA_VERSION = 1;
	private static final Set<String> MANIFEST_FIELDS = Set.of("schema_version", "input_sha256", "operation_id",
		"policy_id", "action", "retained_best", "limiting_families", "named_conflicts", "plateau",
		"observed_improvement", "cost_forecast_available", "prompt_test_collisions");

	/**
	 * Prevents construction of this static operation owner.
	 */
	private DevelopmentReadinessOperation()
	{
	}

	/**
	 * Applies and persists one readiness decision or returns the verified retained result.
	 *
	 * @param json the operation-owned JSON codec
	 * @param request the immutable readiness inputs
	 * @return the terminal readiness decision
	 * @throws IOException if the request or retained result is invalid
	 */
	public static Result execute(JsonMapper json, Request request) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Path resultPath = request.resultPath().toAbsolutePath().normalize();
		Request normalized = normalized(request, resultPath);
		validateCandidate(json, normalized.retainedBest());
		validatePromptTestEvidence(json, normalized);
		String inputFingerprint = inputFingerprint(json, normalized);
		Result[] result = new Result[1];
		RecordStore.withLock(resultPath, () ->
		{
			if (Files.exists(resultPath))
			{
				result[0] = read(json, resultPath, normalized, inputFingerprint);
				return;
			}
			result[0] = decide(normalized);
			RecordStore.write(json, resultPath, encode(json, normalized, inputFingerprint, result[0]));
		});
		return result[0];
	}

	/**
	 * Applies the complete deterministic readiness decision table.
	 *
	 * @param request the validated request
	 * @return the decision
	 */
	private static Result decide(Request request)
	{
		if (!request.namedConflicts().isEmpty())
			return result(request, Action.REPAIR_NAMED_CONFLICT, List.of(), false, 0, List.of());

		DevelopmentPolicy.ReadinessDecision readiness = request.policy().readiness(familyReadiness(request));
		DevelopmentPolicy.PlateauDecision plateau = request.policy().plateau(request.comparableScores());
		boolean forecastAvailable = request.forecastChoice() != ForecastChoice.UNAVAILABLE;
		if (readiness.action() == DevelopmentPolicy.ReadinessAction.START_FORMAL_BATCH)
		{
			if (request.forecastChoice() == ForecastChoice.ONE_MORE_DEVELOPMENT_ROUND)
			{
				return result(request, Action.CONTINUE_DEVELOPMENT, List.of(), plateau.plateau(),
					plateau.observedImprovement(), List.of());
			}
			return result(request, Action.START_FORMAL_BATCH, List.of(), plateau.plateau(),
				plateau.observedImprovement(), List.of());
		}
		if (plateau.plateau())
		{
			return result(request, Action.PLATEAU, readiness.limitingFamilies(), true,
				plateau.observedImprovement(), List.of());
		}
		return new Result(Action.CONTINUE_DEVELOPMENT, request.retainedBest(), readiness.limitingFamilies(),
			List.copyOf(request.namedConflicts()), false, plateau.observedImprovement(), forecastAvailable, List.of());
	}

	/**
	 * Verifies every readiness outcome refers to a promptTest reserved before inference in the contract ledger.
	 *
	 * @param json the operation-owned JSON codec
	 * @param request the normalized readiness request
	 * @throws IOException if the ledger belongs to another contract or omits a readiness trial identity
	 */
	private static void validatePromptTestEvidence(JsonMapper json, Request request) throws IOException
	{
		PromptTestIdentityLedger.Summary summary = PromptTestIdentityLedger.read(json, request.promptTestIdentityLedger());
		if (!summary.contractFingerprint().equals(request.contractFingerprint()))
			throw new IOException("PromptTest identity ledger does not match the development readiness contract");
		Set<PromptTestPoolPartition.Identity> retained = Set.copyOf(summary.identities());
		for (Trial trial : request.trials())
		{
			if (!retained.contains(trial.promptTest()))
			{
				throw new IOException("Readiness trial has no pre-inference prompt-test reservation: " +
					trial.promptTest().instanceIdentifier());
			}
		}
	}

	/**
	 * Creates one result while deriving cost-forecast availability from the request.
	 *
	 * @param request the request
	 * @param action the selected action
	 * @param limitingFamilies the families that did not pass readiness
	 * @param plateau whether development plateaued
	 * @param observedImprovement the observed comparable improvement
	 * @param collisions cross-pool identity collisions
	 * @return the result
	 */
	private static Result result(Request request, Action action, List<String> limitingFamilies, boolean plateau,
		double observedImprovement, List<PromptTestPoolPartition.Collision> collisions)
	{
		boolean forecastAvailable = request.forecastChoice() != ForecastChoice.UNAVAILABLE;
		return new Result(action, request.retainedBest(), limitingFamilies, List.copyOf(request.namedConflicts()), plateau,
			observedImprovement, forecastAvailable, collisions);
	}

	/**
	 * Aggregates readiness trials by family.
	 *
	 * @param request the request
	 * @return the canonical family readiness values
	 */
	private static List<DevelopmentPolicy.FamilyReadiness> familyReadiness(Request request)
	{
		Map<String, int[]> counts = new TreeMap<>();
		for (String family : request.allFamilyIdentifiers())
			counts.put(family, new int[2]);
		for (Trial trial : request.trials())
		{
			int[] family = counts.get(trial.familyIdentifier());
			++family[1];
			if (trial.passed())
				++family[0];
		}
		List<DevelopmentPolicy.FamilyReadiness> result = new ArrayList<>(counts.size());
		for (Map.Entry<String, int[]> entry : counts.entrySet())
		{
			result.add(new DevelopmentPolicy.FamilyReadiness(entry.getKey(), entry.getValue()[0],
				entry.getValue()[1]));
		}
		return List.copyOf(result);
	}

	/**
	 * Validates the candidate and request-level readiness identities.
	 *
	 * @param json the JSON codec
	 * @param candidate the candidate reference
	 * @throws IOException if the immutable candidate changed
	 */
	private static void validateCandidate(JsonMapper json,
		DevelopmentComparisonOperation.CandidateReference candidate) throws IOException
	{
		CandidateBundle.Candidate materialized = CandidateBundle.read(json, candidate.bundleRoot());
		if (!materialized.sha256().equals(candidate.sha256()))
			throw new IOException("Readiness candidate bundle changed: " + candidate.bundleRoot());
	}

	/**
	 * Creates the stable identity of every decision input.
	 *
	 * @param json the JSON codec
	 * @param request the request
	 * @return the lowercase SHA-256 identity
	 * @throws IOException if the identity cannot be encoded
	 */
	private static String inputFingerprint(JsonMapper json, Request request) throws IOException
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("operation_id", request.idempotencyKey());
		identity.put("contract_sha256", request.contractFingerprint());
		identity.put("policy_id", request.policy().identifier());
		ObjectNode thresholds = identity.putObject("policy_thresholds");
		thresholds.put("readiness_trials", request.policy().readinessTrials());
		thresholds.put("readiness_maximum_failures", request.policy().readinessMaximumFailures());
		thresholds.put("plateau_rounds", request.policy().plateauRounds());
		thresholds.put("plateau_minimum_improvement", request.policy().plateauMinimumImprovement());
		identity.set("retained_best", encodeCandidate(json, request.retainedBest()));
		ArrayNode families = identity.putArray("all_families");
		request.allFamilyIdentifiers().stream().sorted().forEach(families::add);
		ArrayNode trials = identity.putArray("trials");
		for (Trial trial : request.trials())
		{
			ObjectNode item = trials.addObject();
			item.put("family_id", trial.familyIdentifier());
			item.set("prompt_test", encodePromptTest(json, trial.promptTest()));
			item.put("passed", trial.passed());
		}
		ArrayNode scores = identity.putArray("comparable_scores");
		request.comparableScores().forEach(scores::add);
		ArrayNode conflicts = identity.putArray("named_conflicts");
		request.namedConflicts().stream().sorted().forEach(conflicts::add);
		identity.put("forecast_choice", request.forecastChoice().externalValue);
		return Fingerprints.ofUtf8(json.writeValueAsString(identity));
	}

	/**
	 * Encodes one terminal readiness result.
	 *
	 * @param json the JSON codec
	 * @param request the request
	 * @param inputFingerprint the input identity
	 * @param result the result
	 * @return the document
	 */
	private static ObjectNode encode(JsonMapper json, Request request, String inputFingerprint, Result result)
	{
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", SCHEMA_VERSION);
		document.put("input_sha256", inputFingerprint);
		document.put("operation_id", request.idempotencyKey());
		document.put("policy_id", request.policy().identifier());
		document.put("action", result.action().externalValue);
		document.set("retained_best", encodeCandidate(json, result.retainedBest()));
		ArrayNode limiting = document.putArray("limiting_families");
		result.limitingFamilies().forEach(limiting::add);
		ArrayNode conflicts = document.putArray("named_conflicts");
		result.namedConflicts().forEach(conflicts::add);
		document.put("plateau", result.plateau());
		document.put("observed_improvement", result.observedImprovement());
		document.put("cost_forecast_available", result.costForecastAvailable());
		ArrayNode collisions = document.putArray("prompt_test_collisions");
		for (PromptTestPoolPartition.Collision collision : result.promptTestCollisions())
		{
			ObjectNode item = collisions.addObject();
			item.put("field", collision.field().name());
			item.put("value", collision.value());
			item.put("first_pool", collision.firstPool().name());
			item.put("second_pool", collision.secondPool().name());
			item.put("first_instance_id", collision.firstInstanceIdentifier());
			item.put("second_instance_id", collision.secondInstanceIdentifier());
		}
		return document;
	}

	/**
	 * Reads a retained readiness result.
	 *
	 * @param json the JSON codec
	 * @param source the result path
	 * @param request the retry request
	 * @param inputFingerprint the expected identity
	 * @return the verified result
	 * @throws IOException if the retained document is malformed or incompatible
	 */
	private static Result read(JsonMapper json, Path source, Request request, String inputFingerprint) throws IOException
	{
		JsonNode parsed = RecordStore.read(json, source);
		if (!(parsed instanceof ObjectNode document) || !fields(document).equals(MANIFEST_FIELDS))
			throw new IOException("Development readiness result has unsupported or missing fields: " + source);
		if (document.path("schema_version").asInt(-1) != SCHEMA_VERSION ||
			!requiredText(document, "input_sha256", source).equals(inputFingerprint))
		{
			throw new IOException("Development readiness input differs from retained identity: " + source);
		}
		DevelopmentComparisonOperation.CandidateReference retainedBest = readCandidate(
			document.path("retained_best"), source);
		if (!retainedBest.equals(request.retainedBest()))
			throw new IOException("Development readiness retained a different best candidate: " + source);
		if (!document.path("plateau").isBoolean() || !document.path("cost_forecast_available").isBoolean() ||
			!document.path("observed_improvement").isNumber())
		{
			throw new IOException("Development readiness result has malformed decision fields: " + source);
		}
		return new Result(parseAction(requiredText(document, "action", source)), retainedBest,
			textArray(document.path("limiting_families"), "limiting_families", source),
			textArray(document.path("named_conflicts"), "named_conflicts", source),
			document.path("plateau").asBoolean(), document.path("observed_improvement").asDouble(),
			document.path("cost_forecast_available").asBoolean(),
			readCollisions(document.path("prompt_test_collisions"), source));
	}

	/**
	 * Encodes one candidate reference.
	 *
	 * @param json the JSON codec
	 * @param candidate the candidate
	 * @return the object
	 */
	private static ObjectNode encodeCandidate(JsonMapper json,
		DevelopmentComparisonOperation.CandidateReference candidate)
	{
		ObjectNode result = json.createObjectNode();
		result.put("identifier", candidate.identifier());
		result.put("bundle_root", candidate.bundleRoot().toString());
		result.put("sha256", candidate.sha256());
		return result;
	}

	/**
	 * Reads one candidate reference.
	 *
	 * @param node the candidate object
	 * @param source the containing document
	 * @return the reference
	 * @throws IOException if the object is malformed
	 */
	private static DevelopmentComparisonOperation.CandidateReference readCandidate(JsonNode node, Path source)
		throws IOException
	{
		if (!(node instanceof ObjectNode candidate) ||
			!fields(candidate).equals(Set.of("identifier", "bundle_root", "sha256")))
		{
			throw new IOException("Development readiness candidate reference is malformed: " + source);
		}
		return new DevelopmentComparisonOperation.CandidateReference(requiredText(candidate, "identifier", source),
			Path.of(requiredText(candidate, "bundle_root", source)), requiredSha256(candidate, "sha256", source));
	}

	/**
	 * Encodes one pool-partition identity.
	 *
	 * @param json the JSON codec
	 * @param promptTest the prompt_test identity
	 * @return the object
	 */
	private static ObjectNode encodePromptTest(JsonMapper json, PromptTestPoolPartition.Identity promptTest)
	{
		ObjectNode result = json.createObjectNode();
		result.put("pool", promptTest.pool().name());
		result.put("instance_id", promptTest.instanceIdentifier());
		result.put("seed", promptTest.seed());
		result.put("content_sha256", promptTest.generatedContentFingerprint());
		result.put("execution_id", promptTest.executionIdentifier());
		return result;
	}

	/**
	 * Reads cross-pool collision diagnostics.
	 *
	 * @param node the collision array
	 * @param source the containing document
	 * @return the collisions
	 * @throws IOException if the array is malformed
	 */
	private static List<PromptTestPoolPartition.Collision> readCollisions(JsonNode node, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Development readiness prompt_test_collisions is not an array: " + source);
		List<PromptTestPoolPartition.Collision> result = new ArrayList<>(array.size());
		Set<String> expected = Set.of("field", "value", "first_pool", "second_pool", "first_instance_id",
			"second_instance_id");
		for (JsonNode value : array)
		{
			if (!(value instanceof ObjectNode collision) || !fields(collision).equals(expected))
				throw new IOException("Development readiness collision is malformed: " + source);
			try
			{
				result.add(new PromptTestPoolPartition.Collision(
					PromptTestPoolPartition.IdentityField.valueOf(requiredText(collision, "field", source)),
					requiredText(collision, "value", source),
					PromptTestPoolPartition.Pool.valueOf(requiredText(collision, "first_pool", source)),
					PromptTestPoolPartition.Pool.valueOf(requiredText(collision, "second_pool", source)),
					requiredText(collision, "first_instance_id", source),
					requiredText(collision, "second_instance_id", source)));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Development readiness collision uses an unsupported enum: " + source, e);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Normalizes path-bearing request values.
	 *
	 * @param request the caller request
	 * @param resultPath the normalized result path
	 * @return the normalized request
	 */
	private static Request normalized(Request request, Path resultPath)
	{
		DevelopmentComparisonOperation.CandidateReference candidate = request.retainedBest();
		DevelopmentComparisonOperation.CandidateReference normalizedCandidate =
			new DevelopmentComparisonOperation.CandidateReference(candidate.identifier(),
				candidate.bundleRoot().toAbsolutePath().normalize(), candidate.sha256());
		return new Request(resultPath, request.promptTestIdentityLedger().toAbsolutePath().normalize(),
			request.contractFingerprint(), request.idempotencyKey(), request.policy(), normalizedCandidate,
			request.allFamilyIdentifiers(), request.trials(), request.comparableScores(), request.namedConflicts(),
			request.forecastChoice());
	}

	/**
	 * Parses one external readiness action.
	 *
	 * @param value the external value
	 * @return the action
	 * @throws IOException if the value is unsupported
	 */
	private static Action parseAction(String value) throws IOException
	{
		for (Action action : Action.values())
		{
			if (action.externalValue.equals(value))
				return action;
		}
		throw new IOException("Unsupported development readiness action: " + value);
	}

	/**
	 * Reads one text array.
	 *
	 * @param node the array
	 * @param field the field name
	 * @param source the source path
	 * @return the values
	 * @throws IOException if the array is malformed
	 */
	private static List<String> textArray(JsonNode node, String field, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Development readiness has invalid " + field + ": " + source);
		List<String> result = new ArrayList<>(array.size());
		for (JsonNode value : array)
		{
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Development readiness has invalid " + field + ": " + source);
			result.add(value.asString());
		}
		return List.copyOf(result);
	}

	/**
	 * Requires one nonblank text field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Development readiness has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Requires one SHA-256 field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the fingerprint
	 * @throws IOException if the value is invalid
	 */
	private static String requiredSha256(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Development readiness has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Returns an object's exact field set.
	 *
	 * @param object the object
	 * @return the field set
	 */
	private static Set<String> fields(ObjectNode object)
	{
		return new HashSet<>(object.propertyNames());
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Names the complete cost-policy input to readiness.
	 */
	public enum ForecastChoice
	{
		CERTIFY_NOW("certify_now"),
		ONE_MORE_DEVELOPMENT_ROUND("one_more_development_round"),
		UNAVAILABLE("unavailable");

		private final String externalValue;

		/**
		 * Creates one external cost-policy value.
		 *
		 * @param externalValue the persisted value
		 */
		ForecastChoice(String externalValue)
		{
			this.externalValue = externalValue;
		}
	}

	/**
	 * Names the complete readiness result.
	 */
	public enum Action
	{
		CONTINUE_DEVELOPMENT("continue_development"),
		START_FORMAL_BATCH("start_formal_batch"),
		REPAIR_NAMED_CONFLICT("repair_named_conflict"),
		PLATEAU("plateau"),
		BLOCKED_PROMPT_TEST_COLLISION("blocked_prompt_test_collision");

		private final String externalValue;

		/**
		 * Creates one external action value.
		 *
		 * @param externalValue the persisted value
		 */
		Action(String externalValue)
		{
			this.externalValue = externalValue;
		}
	}

	/**
	 * Holds one fresh readiness outcome.
	 *
	 * @param familyIdentifier the owning leaf family
	 * @param promptTest the readiness-pool prompt-test identity
	 * @param passed whether every applicable assertion passed
	 */
	public record Trial(String familyIdentifier, PromptTestPoolPartition.Identity promptTest, boolean passed)
	{
		/**
		 * Validates one readiness outcome.
		 *
		 * @param familyIdentifier the owning leaf family
		 * @param promptTest the readiness-pool prompt-test identity
		 * @param passed whether every applicable assertion passed
		 */
		public Trial
		{
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			Objects.requireNonNull(promptTest, "promptTest must not be null");
			if (promptTest.pool() != PromptTestPoolPartition.Pool.READINESS)
				throw new IllegalArgumentException("Readiness trials must belong to the readiness pool");
		}
	}

	/**
	 * Holds the complete deterministic readiness inputs.
	 *
	 * @param resultPath the durable terminal result path
	 * @param promptTestIdentityLedger the durable contract-wide evidence-pool partition
	 * @param contractFingerprint the frozen certification-contract fingerprint
	 * @param idempotencyKey the stable operation identifier
	 * @param policy the frozen development policy
	 * @param retainedBest the current best candidate
	 * @param allFamilyIdentifiers every frozen leaf family
	 * @param trials the fresh readiness outcomes
	 * @param comparableScores weakest-family development scores under one contract and environment
	 * @param namedConflicts unresolved surrounding prompt artifacts
	 * @param forecastChoice the cost policy's constrained decision
	 */
	public record Request(Path resultPath, Path promptTestIdentityLedger, String contractFingerprint,
		String idempotencyKey, DevelopmentPolicy policy,
		DevelopmentComparisonOperation.CandidateReference retainedBest, Set<String> allFamilyIdentifiers,
		List<Trial> trials, List<Double> comparableScores, Set<String> namedConflicts, ForecastChoice forecastChoice)
	{
		/**
		 * Validates and snapshots one request.
		 *
		 * @param resultPath the durable terminal result path
		 * @param promptTestIdentityLedger the durable contract-wide evidence-pool partition
		 * @param contractFingerprint the frozen certification-contract fingerprint
		 * @param idempotencyKey the stable operation identifier
		 * @param policy the frozen development policy
		 * @param retainedBest the current best candidate
		 * @param allFamilyIdentifiers every frozen leaf family
		 * @param trials the fresh readiness outcomes
		 * @param comparableScores weakest-family development scores under one contract and environment
		 * @param namedConflicts unresolved surrounding prompt artifacts
		 * @param forecastChoice the cost policy's constrained decision
		 */
		public Request
		{
			Objects.requireNonNull(resultPath, "resultPath must not be null");
			Objects.requireNonNull(promptTestIdentityLedger, "promptTestIdentityLedger must not be null");
			contractFingerprint = requireText(contractFingerprint, "contractFingerprint");
			if (!contractFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("contractFingerprint must be a lowercase SHA-256 value");
			idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(policy, "policy must not be null");
			Objects.requireNonNull(retainedBest, "retainedBest must not be null");
			Objects.requireNonNull(allFamilyIdentifiers, "allFamilyIdentifiers must not be null");
			Set<String> families = new TreeSet<>();
			for (String family : allFamilyIdentifiers)
				families.add(requireText(family, "family identifier"));
			if (families.isEmpty())
				throw new IllegalArgumentException("allFamilyIdentifiers must not be empty");
			allFamilyIdentifiers = Set.copyOf(families);
			Objects.requireNonNull(trials, "trials must not be null");
			trials = List.copyOf(trials);
			if (trials.isEmpty())
				throw new IllegalArgumentException("trials must not be empty");
			Set<String> trialInstances = new HashSet<>();
			for (Trial trial : trials)
			{
				if (trial == null)
					throw new NullPointerException("trials must not contain null elements");
				if (!allFamilyIdentifiers.contains(trial.familyIdentifier()))
					throw new IllegalArgumentException("Readiness trial names an unknown family: " + trial.familyIdentifier());
				if (!trialInstances.add(trial.promptTest().instanceIdentifier()))
					throw new IllegalArgumentException("Duplicate readiness trial: " + trial.promptTest().instanceIdentifier());
			}
			Objects.requireNonNull(comparableScores, "comparableScores must not be null");
			comparableScores = List.copyOf(comparableScores);
			Objects.requireNonNull(namedConflicts, "namedConflicts must not be null");
			Set<String> conflicts = new TreeSet<>();
			for (String conflict : namedConflicts)
				conflicts.add(requireText(conflict, "named conflict"));
			namedConflicts = Set.copyOf(conflicts);
			Objects.requireNonNull(forecastChoice, "forecastChoice must not be null");
		}
	}

	/**
	 * Holds one terminal readiness result.
	 *
	 * @param action the executable next action
	 * @param retainedBest the unchanged best candidate
	 * @param limitingFamilies the families below the readiness gate
	 * @param namedConflicts surrounding artifacts requiring authorization
	 * @param plateau whether the comparable development scores plateaued
	 * @param observedImprovement the improvement over the policy window
	 * @param costForecastAvailable whether the cost policy had a calibrated forecast
	 * @param promptTestCollisions any cross-pool identity collisions
	 */
	public record Result(Action action, DevelopmentComparisonOperation.CandidateReference retainedBest,
		List<String> limitingFamilies, List<String> namedConflicts, boolean plateau, double observedImprovement,
		boolean costForecastAvailable, List<PromptTestPoolPartition.Collision> promptTestCollisions)
	{
		/**
		 * Validates and snapshots one result.
		 *
		 * @param action the executable next action
		 * @param retainedBest the unchanged best candidate
		 * @param limitingFamilies the families below the readiness gate
		 * @param namedConflicts surrounding artifacts requiring authorization
		 * @param plateau whether the comparable development scores plateaued
		 * @param observedImprovement the improvement over the policy window
		 * @param costForecastAvailable whether the cost policy had a calibrated forecast
		 * @param promptTestCollisions any cross-pool identity collisions
		 */
		public Result
		{
			Objects.requireNonNull(action, "action must not be null");
			Objects.requireNonNull(retainedBest, "retainedBest must not be null");
			limitingFamilies = List.copyOf(limitingFamilies);
			namedConflicts = List.copyOf(namedConflicts);
			if (!Double.isFinite(observedImprovement))
				throw new IllegalArgumentException("observedImprovement must be finite");
			promptTestCollisions = List.copyOf(promptTestCollisions);
		}

		/**
		 * Returns the workflow-registry result represented by this readiness action.
		 *
		 * @return the registered readiness result
		 */
		public OperationResultKind registryResult()
		{
			return switch (action)
			{
				case CONTINUE_DEVELOPMENT -> OperationResultKind.CONTINUE_DEVELOPMENT;
				case START_FORMAL_BATCH -> OperationResultKind.START_FORMAL_BATCH;
				case REPAIR_NAMED_CONFLICT -> OperationResultKind.REPAIR_NAMED_CONFLICT;
				case PLATEAU -> OperationResultKind.PLATEAU;
				case BLOCKED_PROMPT_TEST_COLLISION -> OperationResultKind.INVALID_EVIDENCE;
			};
		}
	}
}

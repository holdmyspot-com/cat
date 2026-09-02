package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.OperationResultKind;


import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * Owns one durable paired development comparison and the resulting best-candidate decision.
 *
 * <p>
 * The operation runs the champion and challenger against the same frozen promptTests in a deterministic randomized
 * order. A focused winner advances to an all-family comparison; only an all-family winner may replace the retained
 * best candidate. Trial executors receive stable idempotency keys so an interrupted operation can safely resume.
 */
public final class DevelopmentComparisonOperation
{
	private static final int SCHEMA_VERSION = 1;
	private static final String MANIFEST_NAME = "development-comparison.json";
	private static final Set<String> RUNNING_FIELDS = Set.of("schema_version", "state", "input_sha256",
		"operation_id", "policy_id", "promotion_allowance", "champion", "challenger", "scope");
	private static final Set<String> COMPLETED_FIELDS = union(RUNNING_FIELDS, Set.of("action", "retained_best",
		"additional_pairs", "affected_families"));
	private static final Set<String> PAIR_FIELDS = Set.of("schema_version", "input_sha256", "prompt_test_index",
		"family_id", "prompt_test_id", "prompt_test_sha256", "first_candidate", "champion", "challenger");
	private static final Set<String> OUTCOME_FIELDS = Set.of("passed", "session_id", "evidence_sha256",
		"input_tokens", "output_tokens", "elapsed_nanos");

	/**
	 * Prevents construction of this static operation owner.
	 */
	private DevelopmentComparisonOperation()
	{
	}

	/**
	 * Executes a comparison or returns its verified retained result.
	 *
	 * @param json the operation-owned JSON codec
	 * @param request the immutable comparison request
	 * @param executor the idempotent candidate-trial executor
	 * @return the deterministic comparison result
	 * @throws IOException if inputs or retained evidence are invalid, or a trial cannot complete
	 */
	public static Result execute(JsonMapper json, Request request, CandidateTrialExecutor executor) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(executor, "executor must not be null");
		Path operationRoot = request.operationRoot().toAbsolutePath().normalize();
		Path manifest = operationRoot.resolve(MANIFEST_NAME);
		Result[] result = new Result[1];
		RecordStore.withLock(manifest, () -> result[0] = executeOwned(json, normalized(request, operationRoot), executor,
			manifest));
		return result[0];
	}

	/**
	 * Executes a comparison while holding its cross-process ownership lock.
	 *
	 * @param json the JSON codec
	 * @param request the normalized request
	 * @param executor the idempotent trial executor
	 * @param manifest the operation manifest
	 * @return the completed result
	 * @throws IOException if validation, execution, or retention fails
	 */
	private static Result executeOwned(JsonMapper json, Request request, CandidateTrialExecutor executor, Path manifest)
		throws IOException
	{
		validateRequest(json, request);
		reservePromptTests(json, request);
		String inputFingerprint = inputFingerprint(json, request);
		if (Files.exists(manifest))
		{
			ObjectNode retained = readManifest(json, manifest, inputFingerprint);
			if (requiredText(retained, "state", manifest).equals("completed"))
				return retainedResult(json, request, retained, inputFingerprint);
		}
		if (!Files.exists(manifest))
		{
			Files.createDirectories(request.operationRoot());
			RecordStore.write(json, manifest, runningManifest(json, request, inputFingerprint));
		}

		List<PairResult> pairs = new ArrayList<>(request.promptTests().size());
		Set<String> sessions = new HashSet<>();
		for (int index = 0; index < request.promptTests().size(); ++index)
		{
			PairResult pair = executePair(json, request, executor, inputFingerprint, index);
			if (!sessions.add(pair.champion().sessionIdentifier()) ||
				!sessions.add(pair.challenger().sessionIdentifier()))
			{
				throw new IOException("Development comparison reused a harness session: " + pair.promptTestIdentifier());
			}
			pairs.add(pair);
		}
		List<DevelopmentPolicy.FamilyPairs> familyPairs = aggregate(pairs);
		DevelopmentPolicy.ComparisonDecision decision = request.policy().compare(familyPairs,
			promotionAllowance(json, request).doubleValue());
		Action action = action(request.comparisonScope(), decision.action());
		CandidateReference retainedBest = request.champion();
		if (action == Action.PROMOTE)
			retainedBest = request.challenger();
		Result completedResult = new Result(action, retainedBest, familyPairs, pairs, decision.additionalPairs(),
			decision.affectedFamilies());
		RecordStore.write(json, manifest, completedManifest(json, request, inputFingerprint, completedResult));
		return completedResult;
	}

	/**
	 * Verifies the contract-bound partition and reserves every paired prompt_test before inference.
	 *
	 * @param json the operation-owned JSON codec
	 * @param request the normalized comparison request
	 * @throws IOException if the ledger belongs to another contract or an identity crosses evidence pools
	 */
	private static void reservePromptTests(JsonMapper json, Request request) throws IOException
	{
		PromptTestIdentityLedger.Summary summary = PromptTestIdentityLedger.read(json, request.promptTestIdentityLedger());
		if (!summary.contractFingerprint().equals(request.contractFingerprint()))
			throw new IOException("PromptTest identity ledger does not match the development comparison contract");
		for (int index = 0; index < request.promptTests().size(); ++index)
		{
			PromptTestPoolPartition.Identity identity = PromptTestPoolPartition.createIdentity(
				request.promptTests().get(index), request.idempotencyKey() + "/prompt_test-" + (index + 1));
			PromptTestIdentityLedger.Reservation reservation = PromptTestIdentityLedger.reserve(json,
				request.promptTestIdentityLedger(), identity);
			if (reservation.status() == PromptTestIdentityLedger.Status.BLOCKED)
				throw new IOException("Development prompt_test collides with another evidence pool: " +
					identity.instanceIdentifier());
		}
	}

	/**
	 * Executes or reads one paired prompt_test.
	 *
	 * @param json the JSON codec
	 * @param request the complete comparison request
	 * @param executor the idempotent trial executor
	 * @param inputFingerprint the comparison input identity
	 * @param index the zero-based prompt_test index
	 * @return the retained pair
	 * @throws IOException if the pair cannot execute or its retained evidence is invalid
	 */
	private static PairResult executePair(JsonMapper json, Request request, CandidateTrialExecutor executor,
		String inputFingerprint, int index) throws IOException
	{
		Path pairPath = pairPath(request.operationRoot(), index);
		if (Files.exists(pairPath))
			return readPair(json, pairPath, request, inputFingerprint, index);

		PromptTestGenerationPolicy.PromptTest promptTest = request.promptTests().get(index);
		boolean championFirst = championFirst(request.orderSeed(), promptTest.instanceIdentifier());
		CandidateReference first = request.challenger();
		CandidateReference second = request.champion();
		if (championFirst)
		{
			first = request.champion();
			second = request.challenger();
		}
		TrialOutcome firstOutcome = executor.execute(trialRequest(request, promptTest, first, index, 1));
		TrialOutcome secondOutcome = executor.execute(trialRequest(request, promptTest, second, index, 2));
		if (firstOutcome.sessionIdentifier().equals(secondOutcome.sessionIdentifier()))
		{
			throw new IOException("Paired candidate trials must use fresh harness sessions: " +
				promptTest.instanceIdentifier());
		}
		TrialOutcome champion = secondOutcome;
		TrialOutcome challenger = firstOutcome;
		if (championFirst)
		{
			champion = firstOutcome;
			challenger = secondOutcome;
		}
		PairResult result = new PairResult(promptTest.familyIdentifier(), promptTest.instanceIdentifier(),
			promptTest.contentFingerprint(), first.identifier(), champion, challenger);
		RecordStore.write(json, pairPath, encodePair(json, result, inputFingerprint, index));
		return result;
	}

	/**
	 * Creates one stable trial request.
	 *
	 * @param request the comparison request
	 * @param promptTest the shared prompt_test
	 * @param candidate the candidate executed in this position
	 * @param promptTestIndex the zero-based prompt_test index
	 * @param position the one-based randomized position
	 * @return the trial request
	 */
	private static TrialRequest trialRequest(Request request, PromptTestGenerationPolicy.PromptTest promptTest,
		CandidateReference candidate, int promptTestIndex, int position)
	{
		String role = "challenger";
		if (candidate.equals(request.champion()))
			role = "champion";
		String key = request.idempotencyKey() + "/pair-" + (promptTestIndex + 1) + "/" + role;
		Path receipt = request.operationRoot().resolve("trials").resolve(String.format("%08d", promptTestIndex + 1)).
			resolve(role);
		return new TrialRequest(key, candidate, promptTest, position, receipt);
	}

	/**
	 * Aggregates exact paired outcomes by leaf family.
	 *
	 * @param pairs the completed paired promptTests
	 * @return the canonical family summaries
	 */
	private static List<DevelopmentPolicy.FamilyPairs> aggregate(List<PairResult> pairs)
	{
		Map<String, int[]> counts = new TreeMap<>();
		for (PairResult pair : pairs)
		{
			int[] family = counts.computeIfAbsent(pair.familyIdentifier(), ignored -> new int[3]);
			if (pair.challenger().passed() && !pair.champion().passed())
				++family[0];
			else if (!pair.challenger().passed() && pair.champion().passed())
				++family[1];
			else
				++family[2];
		}
		List<DevelopmentPolicy.FamilyPairs> result = new ArrayList<>(counts.size());
		for (Map.Entry<String, int[]> entry : counts.entrySet())
		{
			int[] family = entry.getValue();
			result.add(new DevelopmentPolicy.FamilyPairs(entry.getKey(), family[0], family[1], family[2]));
		}
		return List.copyOf(result);
	}

	/**
	 * Maps the policy decision to the comparison-stage action.
	 *
	 * @param scope the comparison scope
	 * @param decision the policy decision
	 * @return the executable stage action
	 */
	private static Action action(ComparisonScope scope, DevelopmentPolicy.ComparisonAction decision)
	{
		return switch (decision)
		{
			case PROMOTE -> promotionAction(scope);
			case REJECT -> Action.REJECT;
			case MORE_PAIRS -> Action.MORE_PAIRS;
			case REPAIR_REGRESSION -> Action.REPAIR_REGRESSION;
		};
	}

	/**
	 * Maps a policy promotion to the current comparison stage.
	 *
	 * @param scope the comparison scope
	 * @return the stage action
	 */
	private static Action promotionAction(ComparisonScope scope)
	{
		if (scope == ComparisonScope.FOCUSED)
			return Action.BROADEN_COMPARISON;
		return Action.PROMOTE;
	}

	/**
	 * Validates immutable candidates, executable promptTests, and comparison coverage.
	 *
	 * @param json the JSON codec
	 * @param request the normalized request
	 * @throws IOException if an input does not satisfy the comparison contract
	 */
	private static void validateRequest(JsonMapper json, Request request) throws IOException
	{
		promotionAllowance(json, request);
		verifyCandidate(json, request.champion());
		verifyCandidate(json, request.challenger());
		if (request.champion().sha256().equals(request.challenger().sha256()))
			throw new IOException("Champion and challenger must identify different prompt candidates");
		Map<String, PromptTestGenerationPolicy> policies = new HashMap<>();
		for (PromptTestGenerationPolicy policy : request.promptTestPolicies())
		{
			if (policies.putIfAbsent(policy.familyIdentifier(), policy) != null)
				throw new IOException("Duplicate development prompt_test policy: " + policy.familyIdentifier());
		}
		Set<String> observedFamilies = new TreeSet<>();
		Set<String> instances = new HashSet<>();
		for (PromptTestGenerationPolicy.PromptTest promptTest : request.promptTests())
		{
			PromptTestGenerationPolicy policy = policies.get(promptTest.familyIdentifier());
			if (policy == null || !policy.contains(promptTest))
				throw new IOException("PromptTest is not an exact output of its frozen development policy: " +
					promptTest.instanceIdentifier());
			if (!instances.add(promptTest.instanceIdentifier()))
				throw new IOException("Duplicate development prompt_test instance: " + promptTest.instanceIdentifier());
			observedFamilies.add(promptTest.familyIdentifier());
		}
		if (!request.allFamilyIdentifiers().containsAll(observedFamilies))
			throw new IOException("Development comparison contains a family outside the frozen contract");
		if (request.comparisonScope() == ComparisonScope.ALL_FAMILIES &&
			!observedFamilies.equals(request.allFamilyIdentifiers()))
		{
			throw new IOException("All-family comparison must include every frozen leaf family");
		}
	}

	/**
	 * Reads the immutable allowance assigned to the request's challenger before paired evidence can be collected.
	 *
	 * @param json the ledger-owned JSON codec
	 * @param request the comparison request
	 * @return the challenger's assigned allowance
	 * @throws IOException if no verified assignment exists
	 */
	private static BigDecimal promotionAllowance(JsonMapper json, Request request) throws IOException
	{
		return PromotionAllowanceLedger.find(json, request.promotionAllowanceManifest(),
			request.challenger().identifier()).map(PromotionAllowanceLedger.Assignment::allowance).orElseThrow(() ->
				new IOException("Development challenger has no preassigned promotion allowance: " +
					request.challenger().identifier()));
	}

	/**
	 * Verifies that a candidate reference still identifies its immutable bundle.
	 *
	 * @param json the JSON codec
	 * @param reference the candidate reference
	 * @throws IOException if the bundle is unavailable or changed
	 */
	private static void verifyCandidate(JsonMapper json, CandidateReference reference) throws IOException
	{
		CandidateBundle.Candidate candidate = CandidateBundle.read(json, reference.bundleRoot());
		if (!candidate.sha256().equals(reference.sha256()))
			throw new IOException("Candidate bundle changed: " + reference.bundleRoot());
	}

	/**
	 * Computes the frozen pseudorandom version order for one prompt_test.
	 *
	 * @param orderSeed the comparison order seed
	 * @param promptTestIdentifier the prompt_test identity
	 * @return whether the champion runs first
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static boolean championFirst(String orderSeed, String promptTestIdentifier) throws IOException
	{
		String fingerprint = Fingerprints.ofUtf8(orderSeed + "\u0000" + promptTestIdentifier);
		return Character.digit(fingerprint.charAt(0), 16) % 2 == 0;
	}

	/**
	 * Creates the complete stable input fingerprint.
	 *
	 * @param json the JSON codec
	 * @param request the comparison request
	 * @return the lowercase SHA-256 input identity
	 * @throws IOException if the identity cannot be encoded
	 */
	private static String inputFingerprint(JsonMapper json, Request request) throws IOException
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("operation_id", request.idempotencyKey());
		identity.put("contract_sha256", request.contractFingerprint());
		identity.put("policy_id", request.policy().identifier());
		ObjectNode thresholds = identity.putObject("policy_thresholds");
		thresholds.put("initial_pairs", request.policy().initialPairs());
		thresholds.put("additional_pairs", request.policy().additionalPairs());
		thresholds.put("maximum_pairs", request.policy().maximumPairs());
		thresholds.put("maximum_survivors", request.policy().maximumSurvivors());
		thresholds.put("promotion_net_wins", request.policy().promotionNetWins());
		thresholds.put("material_regression_net_losses", request.policy().materialRegressionNetLosses());
		thresholds.put("readiness_trials", request.policy().readinessTrials());
		thresholds.put("readiness_maximum_failures", request.policy().readinessMaximumFailures());
		thresholds.put("plateau_rounds", request.policy().plateauRounds());
		thresholds.put("plateau_minimum_improvement", request.policy().plateauMinimumImprovement());
		identity.put("promotion_allowance", promotionAllowance(json, request).toPlainString());
		identity.set("champion", encodeCandidate(json, request.champion()));
		identity.set("challenger", encodeCandidate(json, request.challenger()));
		identity.put("scope", request.comparisonScope().externalValue);
		ArrayNode families = identity.putArray("all_families");
		request.allFamilyIdentifiers().stream().sorted().forEach(families::add);
		ArrayNode policies = identity.putArray("prompt_test_policies");
		request.promptTestPolicies().stream().sorted(Comparator.comparing(PromptTestGenerationPolicy::familyIdentifier)).
			forEach(policy ->
			{
				ObjectNode item = policies.addObject();
				item.put("family_id", policy.familyIdentifier());
				item.put("distribution_id", policy.distributionIdentifier());
				item.put("sampler_id", policy.samplerIdentifier());
				item.put("generator_id", policy.generatorIdentifier());
				item.put("seed_namespace", policy.seedNamespace());
				item.put("support_receipt", policy.supportValidationReceipt());
			});
		ArrayNode promptTests = identity.putArray("promptTests");
		for (PromptTestGenerationPolicy.PromptTest promptTest : request.promptTests())
		{
			ObjectNode item = promptTests.addObject();
			item.put("family_id", promptTest.familyIdentifier());
			item.put("instance_id", promptTest.instanceIdentifier());
			item.put("seed", promptTest.seed());
			item.put("content_sha256", promptTest.contentFingerprint());
		}
		identity.put("order_seed", request.orderSeed());
		return Fingerprints.ofUtf8(json.writeValueAsString(identity));
	}

	/**
	 * Creates a running operation manifest.
	 *
	 * @param json the JSON codec
	 * @param request the request
	 * @param inputFingerprint the input identity
	 * @return the manifest
	 */
	private static ObjectNode runningManifest(JsonMapper json, Request request, String inputFingerprint)
		throws IOException
	{
		ObjectNode manifest = json.createObjectNode();
		manifest.put("schema_version", SCHEMA_VERSION);
		manifest.put("state", "running");
		manifest.put("input_sha256", inputFingerprint);
		manifest.put("operation_id", request.idempotencyKey());
		manifest.put("policy_id", request.policy().identifier());
		manifest.put("promotion_allowance", promotionAllowance(json, request).toPlainString());
		manifest.set("champion", encodeCandidate(json, request.champion()));
		manifest.set("challenger", encodeCandidate(json, request.challenger()));
		manifest.put("scope", request.comparisonScope().externalValue);
		return manifest;
	}

	/**
	 * Creates a completed operation manifest.
	 *
	 * @param json the JSON codec
	 * @param request the request
	 * @param inputFingerprint the input identity
	 * @param result the terminal result
	 * @return the manifest
	 */
	private static ObjectNode completedManifest(JsonMapper json, Request request, String inputFingerprint, Result result)
		throws IOException
	{
		ObjectNode manifest = runningManifest(json, request, inputFingerprint);
		manifest.put("state", "completed");
		manifest.put("action", result.action().externalValue);
		manifest.set("retained_best", encodeCandidate(json, result.retainedBest()));
		manifest.put("additional_pairs", result.additionalPairs());
		ArrayNode affected = manifest.putArray("affected_families");
		result.affectedFamilies().forEach(affected::add);
		return manifest;
	}

	/**
	 * Reads a retained terminal result.
	 *
	 * @param json the JSON codec
	 * @param request the retry request
	 * @param manifest the completed manifest
	 * @param inputFingerprint the expected input identity
	 * @return the verified retained result
	 * @throws IOException if retained evidence is malformed
	 */
	private static Result retainedResult(JsonMapper json, Request request, ObjectNode manifest,
		String inputFingerprint) throws IOException
	{
		List<PairResult> pairs = new ArrayList<>(request.promptTests().size());
		Set<String> sessions = new HashSet<>();
		for (int index = 0; index < request.promptTests().size(); ++index)
		{
			PairResult pair = readPair(json, pairPath(request.operationRoot(), index), request, inputFingerprint, index);
			if (!sessions.add(pair.champion().sessionIdentifier()) ||
				!sessions.add(pair.challenger().sessionIdentifier()))
			{
				throw new IOException("Retained comparison reused a harness session: " + pair.promptTestIdentifier());
			}
			pairs.add(pair);
		}
		Action action = parseAction(requiredText(manifest, "action", request.operationRoot()));
		CandidateReference retainedBest = readCandidate(manifest.path("retained_best"), request.operationRoot());
		List<String> affected = textArray(manifest.path("affected_families"), "affected_families",
			request.operationRoot());
		return new Result(action, retainedBest, aggregate(pairs), pairs,
			requiredNonnegativeInt(manifest, "additional_pairs", request.operationRoot()), affected);
	}

	/**
	 * Encodes one paired outcome receipt.
	 *
	 * @param json the JSON codec
	 * @param result the pair result
	 * @param inputFingerprint the comparison identity
	 * @param index the zero-based prompt_test index
	 * @return the receipt
	 */
	private static ObjectNode encodePair(JsonMapper json, PairResult result, String inputFingerprint, int index)
	{
		ObjectNode receipt = json.createObjectNode();
		receipt.put("schema_version", SCHEMA_VERSION);
		receipt.put("input_sha256", inputFingerprint);
		receipt.put("prompt_test_index", index);
		receipt.put("family_id", result.familyIdentifier());
		receipt.put("prompt_test_id", result.promptTestIdentifier());
		receipt.put("prompt_test_sha256", result.promptTestFingerprint());
		receipt.put("first_candidate", result.firstCandidateIdentifier());
		receipt.set("champion", encodeOutcome(json, result.champion()));
		receipt.set("challenger", encodeOutcome(json, result.challenger()));
		return receipt;
	}

	/**
	 * Reads and verifies one retained pair receipt.
	 *
	 * @param json the JSON codec
	 * @param pairPath the receipt path
	 * @param request the immutable request
	 * @param inputFingerprint the comparison identity
	 * @param index the expected prompt_test index
	 * @return the retained pair
	 * @throws IOException if the receipt differs from the frozen prompt_test
	 */
	private static PairResult readPair(JsonMapper json, Path pairPath, Request request, String inputFingerprint,
		int index) throws IOException
	{
		JsonNode parsed = RecordStore.read(json, pairPath);
		if (!(parsed instanceof ObjectNode receipt) || !fields(receipt).equals(PAIR_FIELDS))
			throw new IOException("Development pair receipt has unsupported or missing fields: " + pairPath);
		if (receipt.path("schema_version").asInt(-1) != SCHEMA_VERSION ||
			!requiredText(receipt, "input_sha256", pairPath).equals(inputFingerprint) ||
			receipt.path("prompt_test_index").asInt(-1) != index)
		{
			throw new IOException("Development pair receipt has incompatible identity: " + pairPath);
		}
		PromptTestGenerationPolicy.PromptTest promptTest = request.promptTests().get(index);
		String family = requiredText(receipt, "family_id", pairPath);
		String promptTestIdentifier = requiredText(receipt, "prompt_test_id", pairPath);
		String promptTestFingerprint = requiredSha256(receipt, "prompt_test_sha256", pairPath);
		if (!family.equals(promptTest.familyIdentifier()) ||
			!promptTestIdentifier.equals(promptTest.instanceIdentifier()) ||
			!promptTestFingerprint.equals(promptTest.contentFingerprint()))
		{
			throw new IOException("Development pair receipt differs from its frozen prompt_test: " + pairPath);
		}
		String first = requiredText(receipt, "first_candidate", pairPath);
		if (!first.equals(request.champion().identifier()) && !first.equals(request.challenger().identifier()))
			throw new IOException("Development pair receipt names an unknown first candidate: " + pairPath);
		return new PairResult(family, promptTestIdentifier, promptTestFingerprint, first,
			readOutcome(receipt.path("champion"), pairPath), readOutcome(receipt.path("challenger"), pairPath));
	}

	/**
	 * Reads and validates the operation manifest.
	 *
	 * @param json the JSON codec
	 * @param manifestPath the manifest path
	 * @param inputFingerprint the expected input identity
	 * @return the manifest
	 * @throws IOException if the record is incompatible or malformed
	 */
	private static ObjectNode readManifest(JsonMapper json, Path manifestPath, String inputFingerprint)
		throws IOException
	{
		JsonNode parsed = RecordStore.read(json, manifestPath);
		if (!(parsed instanceof ObjectNode manifest))
			throw new IOException("Development comparison manifest is not an object: " + manifestPath);
		String state = requiredText(manifest, "state", manifestPath);
		if (!state.equals("completed") && !state.equals("running"))
			throw new IOException("Development comparison has unsupported state: " + state);
		Set<String> expected = RUNNING_FIELDS;
		if (state.equals("completed"))
			expected = COMPLETED_FIELDS;
		if (!fields(manifest).equals(expected))
			throw new IOException("Development comparison manifest has unsupported or missing fields: " + manifestPath);
		if (manifest.path("schema_version").asInt(-1) != SCHEMA_VERSION ||
			!requiredText(manifest, "input_sha256", manifestPath).equals(inputFingerprint))
		{
			throw new IOException("Development comparison input differs from retained identity: " + manifestPath);
		}
		return manifest;
	}

	/**
	 * Encodes one candidate reference.
	 *
	 * @param json the JSON codec
	 * @param candidate the candidate
	 * @return the JSON object
	 */
	private static ObjectNode encodeCandidate(JsonMapper json, CandidateReference candidate)
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
	 * @param source the containing record
	 * @return the reference
	 * @throws IOException if the object is malformed
	 */
	private static CandidateReference readCandidate(JsonNode node, Path source) throws IOException
	{
		if (!(node instanceof ObjectNode candidate) ||
			!fields(candidate).equals(Set.of("identifier", "bundle_root", "sha256")))
		{
			throw new IOException("Development candidate reference is malformed: " + source);
		}
		return new CandidateReference(requiredText(candidate, "identifier", source),
			Path.of(requiredText(candidate, "bundle_root", source)), requiredSha256(candidate, "sha256", source));
	}

	/**
	 * Encodes one trial outcome.
	 *
	 * @param json the JSON codec
	 * @param outcome the outcome
	 * @return the JSON object
	 */
	private static ObjectNode encodeOutcome(JsonMapper json, TrialOutcome outcome)
	{
		ObjectNode result = json.createObjectNode();
		result.put("passed", outcome.passed());
		result.put("session_id", outcome.sessionIdentifier());
		result.put("evidence_sha256", outcome.evidenceFingerprint());
		result.put("input_tokens", outcome.inputTokens());
		result.put("output_tokens", outcome.outputTokens());
		result.put("elapsed_nanos", outcome.elapsedNanos());
		return result;
	}

	/**
	 * Reads one trial outcome.
	 *
	 * @param node the outcome object
	 * @param source the receipt path
	 * @return the outcome
	 * @throws IOException if the object is malformed
	 */
	private static TrialOutcome readOutcome(JsonNode node, Path source) throws IOException
	{
		if (!(node instanceof ObjectNode outcome) || !fields(outcome).equals(OUTCOME_FIELDS) ||
			!outcome.path("passed").isBoolean())
		{
			throw new IOException("Development trial outcome is malformed: " + source);
		}
		return new TrialOutcome(outcome.path("passed").asBoolean(), requiredText(outcome, "session_id", source),
			requiredSha256(outcome, "evidence_sha256", source),
			requiredNonnegativeLong(outcome, "input_tokens", source),
			requiredNonnegativeLong(outcome, "output_tokens", source),
			requiredNonnegativeLong(outcome, "elapsed_nanos", source));
	}

	/**
	 * Resolves one pair receipt path.
	 *
	 * @param operationRoot the operation root
	 * @param index the zero-based prompt_test index
	 * @return the receipt path
	 */
	private static Path pairPath(Path operationRoot, int index)
	{
		return operationRoot.resolve("pairs").resolve(String.format("%08d.json", index + 1));
	}

	/**
	 * Normalizes path-bearing request values.
	 *
	 * @param request the caller request
	 * @param operationRoot the normalized operation root
	 * @return the normalized request
	 */
	private static Request normalized(Request request, Path operationRoot)
	{
		return new Request(operationRoot, request.promptTestIdentityLedger().toAbsolutePath().normalize(),
			request.promotionAllowanceManifest().toAbsolutePath().normalize(), request.contractFingerprint(),
			request.idempotencyKey(), request.policy(), normalized(request.champion()),
			normalized(request.challenger()), request.comparisonScope(), request.allFamilyIdentifiers(),
			request.promptTestPolicies(), request.promptTests(), request.orderSeed());
	}

	/**
	 * Normalizes one candidate bundle path.
	 *
	 * @param candidate the candidate reference
	 * @return the normalized reference
	 */
	private static CandidateReference normalized(CandidateReference candidate)
	{
		return new CandidateReference(candidate.identifier(), candidate.bundleRoot().toAbsolutePath().normalize(),
			candidate.sha256());
	}

	/**
	 * Parses one external action.
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
		throw new IOException("Unsupported development comparison action: " + value);
	}

	/**
	 * Requires one nonblank text field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the text
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Development comparison has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Requires one SHA-256 text field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the fingerprint
	 * @throws IOException if the field is invalid
	 */
	private static String requiredSha256(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Development comparison has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Requires one nonnegative integer field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the value
	 * @throws IOException if the field is invalid
	 */
	private static int requiredNonnegativeInt(ObjectNode object, String field, Path source) throws IOException
	{
		long value = requiredNonnegativeLong(object, field, source);
		if (value > Integer.MAX_VALUE)
			throw new IOException("Development comparison has oversized " + field + ": " + source);
		return (int) value;
	}

	/**
	 * Requires one nonnegative long field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the source path
	 * @return the value
	 * @throws IOException if the field is invalid
	 */
	private static long requiredNonnegativeLong(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
			throw new IOException("Development comparison has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Reads one text array.
	 *
	 * @param node the array
	 * @param field the field name
	 * @param source the source path
	 * @return the immutable values
	 * @throws IOException if the array is malformed
	 */
	private static List<String> textArray(JsonNode node, String field, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Development comparison has invalid " + field + ": " + source);
		List<String> result = new ArrayList<>(array.size());
		for (JsonNode value : array)
		{
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Development comparison has invalid " + field + ": " + source);
			result.add(value.asString());
		}
		return List.copyOf(result);
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
	 * Returns the union of two immutable field sets.
	 *
	 * @param first the first set
	 * @param second the second set
	 * @return the union
	 */
	private static Set<String> union(Set<String> first, Set<String> second)
	{
		Set<String> result = new HashSet<>(first);
		result.addAll(second);
		return Set.copyOf(result);
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
	 * Names the coverage stage of one paired comparison.
	 */
	public enum ComparisonScope
	{
		FOCUSED("focused"),
		ALL_FAMILIES("all_families");

		private final String externalValue;

		/**
		 * Creates one external scope value.
		 *
		 * @param externalValue the persisted value
		 */
		ComparisonScope(String externalValue)
		{
			this.externalValue = externalValue;
		}
	}

	/**
	 * Names the executable result of one comparison stage.
	 */
	public enum Action
	{
		BROADEN_COMPARISON("broaden_comparison"),
		PROMOTE("promote"),
		REJECT("reject"),
		MORE_PAIRS("more_pairs"),
		REPAIR_REGRESSION("repair_regression");

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
	 * Identifies one immutable prompt candidate.
	 *
	 * @param identifier the local-to-this-certification-process candidate identifier
	 * @param bundleRoot the immutable candidate bundle root
	 * @param sha256 the bundle fingerprint
	 */
	public record CandidateReference(String identifier, Path bundleRoot, String sha256)
	{
		/**
		 * Validates one candidate reference.
		 *
		 * @param identifier the local-to-this-certification-process candidate identifier
		 * @param bundleRoot the immutable candidate bundle root
		 * @param sha256 the bundle fingerprint
		 */
		public CandidateReference
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(bundleRoot, "bundleRoot must not be null");
			sha256 = requireText(sha256, "sha256");
			if (!sha256.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 value");
		}
	}

	/**
	 * Holds one immutable comparison request.
	 *
	 * @param operationRoot the durable operation directory
	 * @param promptTestIdentityLedger the durable contract-wide evidence-pool partition
	 * @param promotionAllowanceManifest the durable challenger-allowance ledger manifest
	 * @param contractFingerprint the frozen certification-contract fingerprint
	 * @param idempotencyKey the stable operation key
	 * @param policy the frozen development policy
	 * @param champion the retained best candidate
	 * @param challenger the proposed replacement
	 * @param comparisonScope the focused or all-family stage
	 * @param allFamilyIdentifiers every frozen leaf-family identifier
	 * @param promptTestPolicies the executable policies for compared families
	 * @param promptTests the frozen paired promptTests in execution order
	 * @param orderSeed the frozen candidate-order seed
	 */
	public record Request(Path operationRoot, Path promptTestIdentityLedger, Path promotionAllowanceManifest,
		String contractFingerprint,
		String idempotencyKey, DevelopmentPolicy policy,
		CandidateReference champion, CandidateReference challenger, ComparisonScope comparisonScope,
		Set<String> allFamilyIdentifiers, List<PromptTestGenerationPolicy> promptTestPolicies,
		List<PromptTestGenerationPolicy.PromptTest> promptTests, String orderSeed)
	{
		/**
		 * Validates and snapshots one comparison request.
		 *
		 * @param operationRoot the durable operation directory
		 * @param promptTestIdentityLedger the durable contract-wide evidence-pool partition
		 * @param promotionAllowanceManifest the durable challenger-allowance ledger manifest
		 * @param contractFingerprint the frozen certification-contract fingerprint
		 * @param idempotencyKey the stable operation key
		 * @param policy the frozen development policy
		 * @param champion the retained best candidate
		 * @param challenger the proposed replacement
		 * @param comparisonScope the focused or all-family stage
		 * @param allFamilyIdentifiers every frozen leaf-family identifier
		 * @param promptTestPolicies the executable policies for compared families
		 * @param promptTests the frozen paired promptTests in execution order
		 * @param orderSeed the frozen candidate-order seed
		 */
		public Request
		{
			Objects.requireNonNull(operationRoot, "operationRoot must not be null");
			Objects.requireNonNull(promptTestIdentityLedger, "promptTestIdentityLedger must not be null");
			Objects.requireNonNull(promotionAllowanceManifest, "promotionAllowanceManifest must not be null");
			contractFingerprint = requireText(contractFingerprint, "contractFingerprint");
			if (!contractFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("contractFingerprint must be a lowercase SHA-256 value");
			idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(policy, "policy must not be null");
			Objects.requireNonNull(champion, "champion must not be null");
			Objects.requireNonNull(challenger, "challenger must not be null");
			Objects.requireNonNull(comparisonScope, "comparisonScope must not be null");
			Objects.requireNonNull(allFamilyIdentifiers, "allFamilyIdentifiers must not be null");
			Set<String> families = new TreeSet<>();
			for (String family : allFamilyIdentifiers)
				families.add(requireText(family, "family identifier"));
			if (families.isEmpty())
				throw new IllegalArgumentException("allFamilyIdentifiers must not be empty");
			allFamilyIdentifiers = Set.copyOf(families);
			Objects.requireNonNull(promptTestPolicies, "promptTestPolicies must not be null");
			promptTestPolicies = List.copyOf(promptTestPolicies);
			Objects.requireNonNull(promptTests, "promptTests must not be null");
			if (promptTests.isEmpty())
				throw new IllegalArgumentException("promptTests must not be empty");
			promptTests = List.copyOf(promptTests);
			orderSeed = requireText(orderSeed, "orderSeed");
		}
	}

	/**
 * Executes one candidate on one frozen paired promptTest.
	 */
	@FunctionalInterface
	public interface CandidateTrialExecutor
	{
		/**
		 * Executes or returns the retained result for the request's idempotency key.
		 *
		 * @param request the exact candidate trial
		 * @return the retained trial outcome
		 * @throws IOException if the candidate cannot execute or its evidence is invalid
		 */
		TrialOutcome execute(TrialRequest request) throws IOException;
	}

	/**
	 * Holds one candidate trial request.
	 *
	 * @param idempotencyKey the stable per-candidate trial key
	 * @param candidate the candidate to execute
	 * @param promptTest the shared paired promptTest
	 * @param position the one-based randomized execution position
	 * @param receiptDirectory the durable per-trial evidence directory
	 */
	public record TrialRequest(String idempotencyKey, CandidateReference candidate,
		PromptTestGenerationPolicy.PromptTest promptTest, int position, Path receiptDirectory)
	{
		/**
		 * Validates one candidate trial request.
		 *
		 * @param idempotencyKey the stable per-candidate trial key
		 * @param candidate the candidate to execute
		 * @param promptTest the shared paired promptTest
		 * @param position the one-based randomized execution position
		 * @param receiptDirectory the durable per-trial evidence directory
		 */
		public TrialRequest
		{
			idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(candidate, "candidate must not be null");
			Objects.requireNonNull(promptTest, "promptTest must not be null");
			if (position != 1 && position != 2)
				throw new IllegalArgumentException("position must be one or two: " + position);
			Objects.requireNonNull(receiptDirectory, "receiptDirectory must not be null");
		}
	}

	/**
	 * Holds one retained candidate-trial outcome.
	 *
	 * @param passed whether every applicable assertion passed
	 * @param sessionIdentifier the fresh harness-session identifier
	 * @param evidenceFingerprint the retained evidence fingerprint
	 * @param inputTokens the observed input tokens, or zero when unavailable
	 * @param outputTokens the observed output tokens, or zero when unavailable
	 * @param elapsedNanos the observed elapsed time
	 */
	public record TrialOutcome(boolean passed, String sessionIdentifier, String evidenceFingerprint, long inputTokens,
		long outputTokens, long elapsedNanos)
	{
		/**
		 * Validates one retained trial outcome.
		 *
		 * @param passed whether every applicable assertion passed
		 * @param sessionIdentifier the fresh harness-session identifier
		 * @param evidenceFingerprint the retained evidence fingerprint
		 * @param inputTokens the observed input tokens, or zero when unavailable
		 * @param outputTokens the observed output tokens, or zero when unavailable
		 * @param elapsedNanos the observed elapsed time
		 */
		public TrialOutcome
		{
			sessionIdentifier = requireText(sessionIdentifier, "sessionIdentifier");
			evidenceFingerprint = requireText(evidenceFingerprint, "evidenceFingerprint");
			if (!evidenceFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("evidenceFingerprint must be a lowercase SHA-256 value");
			if (inputTokens < 0 || outputTokens < 0 || elapsedNanos < 0)
				throw new IllegalArgumentException("Trial usage and elapsed time must not be negative");
		}
	}

	/**
	 * Holds one completed paired prompt_test.
	 *
	 * @param familyIdentifier the owning leaf family
	 * @param promptTestIdentifier the frozen prompt_test identity
	 * @param promptTestFingerprint the rendered prompt_test fingerprint
	 * @param firstCandidateIdentifier the randomized first candidate
	 * @param champion the champion outcome
	 * @param challenger the challenger outcome
	 */
	public record PairResult(String familyIdentifier, String promptTestIdentifier, String promptTestFingerprint,
		String firstCandidateIdentifier, TrialOutcome champion, TrialOutcome challenger)
	{
		/**
		 * Validates one paired result.
		 *
		 * @param familyIdentifier the owning leaf family
		 * @param promptTestIdentifier the frozen prompt_test identity
		 * @param promptTestFingerprint the rendered prompt_test fingerprint
		 * @param firstCandidateIdentifier the randomized first candidate
		 * @param champion the champion outcome
		 * @param challenger the challenger outcome
		 */
		public PairResult
		{
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			promptTestIdentifier = requireText(promptTestIdentifier, "promptTestIdentifier");
			promptTestFingerprint = requireText(promptTestFingerprint, "promptTestFingerprint");
			if (!promptTestFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("promptTestFingerprint must be a lowercase SHA-256 value");
			firstCandidateIdentifier = requireText(firstCandidateIdentifier, "firstCandidateIdentifier");
			Objects.requireNonNull(champion, "champion must not be null");
			Objects.requireNonNull(challenger, "challenger must not be null");
		}
	}

	/**
	 * Holds the completed comparison decision and its retained evidence.
	 *
	 * @param action the executable next action
	 * @param retainedBest the candidate retained after this stage
	 * @param familyPairs the canonical paired summaries
	 * @param pairs every paired result
	 * @param additionalPairs the exact additional pairs requested
	 * @param affectedFamilies the families requiring regression repair
	 */
	public record Result(Action action, CandidateReference retainedBest,
		List<DevelopmentPolicy.FamilyPairs> familyPairs, List<PairResult> pairs, int additionalPairs,
		List<String> affectedFamilies)
	{
		/**
		 * Validates and snapshots one comparison result.
		 *
		 * @param action the executable next action
		 * @param retainedBest the candidate retained after this stage
		 * @param familyPairs the canonical paired summaries
		 * @param pairs every paired result
		 * @param additionalPairs the exact additional pairs requested
		 * @param affectedFamilies the families requiring regression repair
		 */
		public Result
		{
			Objects.requireNonNull(action, "action must not be null");
			Objects.requireNonNull(retainedBest, "retainedBest must not be null");
			familyPairs = List.copyOf(familyPairs);
			pairs = List.copyOf(pairs);
			if (additionalPairs < 0)
				throw new IllegalArgumentException("additionalPairs must not be negative");
			affectedFamilies = List.copyOf(affectedFamilies);
		}

		/**
		 * Returns the workflow-registry result represented by this stage-specific action.
		 *
		 * @return the registered comparison result
		 */
		public OperationResultKind registryResult()
		{
			return switch (action)
			{
				case BROADEN_COMPARISON, PROMOTE -> OperationResultKind.PROMOTE;
				case REJECT -> OperationResultKind.REJECT;
				case MORE_PAIRS -> OperationResultKind.MORE_PAIRS;
				case REPAIR_REGRESSION -> OperationResultKind.REPAIR_REGRESSION;
			};
		}
	}
}

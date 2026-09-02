package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationOperation;
import io.github.cowwoc.cat.common.runner.HarnessRequestBinding;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.HarnessTranscript;
import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Diagnoses retained development failures and proposes a bounded set of target-prompt replacements in one isolated
 * harness session.
 *
 * <p>
 * Customer prompt, contract, and failure content remain read-only evidence and are not copied into trusted
 * instructions. The response may name surrounding artifacts for a later authorization decision, but candidates can
 * replace only the frozen target prompt.
 */
public final class HarnessCandidateGenerator
{
	private static final int MAXIMUM_RESPONSE_CHARACTERS = 2 * 1024 * 1024;
	private static final Set<String> RESPONSE_FIELDS = Set.of("diagnoses", "candidates");
	private static final Set<String> DIAGNOSIS_FIELDS = Set.of("family_id", "cause", "evidence_ids",
		"surrounding_artifacts");
	private static final Set<String> CANDIDATE_FIELDS = Set.of("candidate_id", "rationale", "replacement");
	private final JsonMapper json;
	private final HarnessModelProfile profile;
	private final Path operationRoot;
	private final int maximumCandidates;
	private final RequestFactory requestFactory;
	private final HarnessEvaluationRunner runner;

	/**
	 * Creates one bounded candidate-generation operation.
	 *
	 * @param json the operation-owned JSON codec
	 * @param profile the exact optimizer model profile
	 * @param operationRoot the durable operation root
	 * @param maximumCandidates the maximum candidates permitted in one response
	 * @param requestFactory the harness-specific request factory
	 * @param runner the harness-neutral execution boundary
	 */
	public HarnessCandidateGenerator(JsonMapper json, HarnessModelProfile profile, Path operationRoot,
		int maximumCandidates, RequestFactory requestFactory, HarnessEvaluationRunner runner)
	{
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.profile = SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			Objects.requireNonNull(profile, "profile must not be null"));
		this.operationRoot = Objects.requireNonNull(operationRoot, "operationRoot must not be null").
			toAbsolutePath().normalize();
		if (maximumCandidates <= 0)
			throw new IllegalArgumentException("maximumCandidates must be positive");
		this.maximumCandidates = maximumCandidates;
		this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
	}

	/**
	 * Runs failure diagnosis and candidate generation or returns retained harness evidence on retry.
	 *
	 * @param inputs the immutable prompt, contract, and failure evidence
	 * @return the strict bounded proposal
	 * @throws IOException if input validation, model execution, or response parsing fails
	 */
	public Proposal generate(Inputs inputs) throws IOException
	{
		Objects.requireNonNull(inputs, "inputs must not be null");
		CandidateBundle.Candidate champion = CandidateBundle.read(json, inputs.championBundle());
		if (!champion.primary().relativePath().equals(inputs.targetPrimaryRule()))
			throw new IOException("Candidate-generation target differs from the champion primary rule");
		List<Path> evidence = List.of(inputs.championBundle(), inputs.contractEvidenceRoot(), inputs.failureEvidence());
		String prompt = trustedPrompt(inputs);
		HarnessEvaluationRequest request = requestFactory.create(profile, prompt, evidence, operationRoot);
		if (!request.profile().equals(profile))
			throw new IOException("Candidate generator request changed the frozen model profile");
		SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR, request.profile());
		HarnessRequestBinding.requireExactPrompt(json, request, prompt, "Candidate generator");
		if (!new HashSet<>(request.readOnlyEvidence()).containsAll(evidence))
			throw new IOException("Candidate generator request omits required read-only evidence");
		HarnessEvaluationExecution execution = HarnessEvaluationOperation.execute(json, request, runner);
		String response = HarnessTranscript.finalAssistantResponse(json, execution.result().rawResult());
		if (response.length() > MAXIMUM_RESPONSE_CHARACTERS)
			throw new IOException("Candidate generator response exceeds the supported size");
		Path retainedResponse = operationRoot.resolve("candidate-proposal.json");
		RecordStore.writeText(retainedResponse, response + System.lineSeparator());
		return parse(response, inputs.targetFamilies(), retainedResponse);
	}

	/**
	 * Builds trusted instructions that refer to customer content only by read-only path.
	 *
	 * @param inputs the immutable evidence locations and target metadata
	 * @return the trusted prompt
	 */
	private String trustedPrompt(Inputs inputs)
	{
		return "You are CAT's bounded prompt candidate generator. Diagnose only the named target families " +
			inputs.targetFamilies() + " using the immutable champion bundle at " + inputs.championBundle() +
			", certification contract at " + inputs.contractDocument() + ", and retained failure evidence at " +
			inputs.failureEvidence() + ". Apply CAT prompt-builder principles to preserve all existing obligations while " +
			"creating at most " + maximumCandidates + " distinct replacements for only " +
			inputs.targetPrimaryRule() + ". Do not edit a surrounding artifact; name it only when cited evidence shows a " +
			"likely conflict. Return exactly one JSON object with fields diagnoses and candidates. Each diagnosis has " +
			"family_id, cause, evidence_ids, and surrounding_artifacts. Each candidate has candidate_id, rationale, and " +
			"replacement containing the complete target file. Return no Markdown fence, commentary, or unknown field.";
	}

	/**
	 * Parses one exact proposal document.
	 *
	 * @param response the assistant response
	 * @param targetFamilies the only permitted diagnosis families
	 * @param source the retained response path
	 * @return the strict proposal
	 * @throws IOException if the response is malformed, overbroad, or empty
	 */
	private Proposal parse(String response, Set<String> targetFamilies, Path source) throws IOException
	{
		JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
		try
		{
			JsonNode parsed = strict.readTree(response);
			if (!(parsed instanceof ObjectNode object) || !fields(object).equals(RESPONSE_FIELDS))
				throw new IOException("Candidate generator response has unsupported or missing fields: " + source);
			List<Diagnosis> diagnoses = diagnoses(object.path("diagnoses"), targetFamilies, source);
			List<CandidateProposal> candidates = candidates(object.path("candidates"), source);
			if (candidates.isEmpty())
				throw new IOException("Candidate generator returned no candidate: " + source);
			if (candidates.size() > maximumCandidates)
				throw new IOException("Candidate generator exceeded its candidate limit: " + source);
			return new Proposal(diagnoses, candidates);
		}
		catch (JacksonException e)
		{
			throw new IOException("Candidate generator response is not strict JSON: " + source, e);
		}
	}

	/**
	 * Parses diagnosis entries.
	 *
	 * @param node the diagnosis array
	 * @param targetFamilies the permitted families
	 * @param source the response path
	 * @return the diagnoses
	 * @throws IOException if an entry is malformed or outside the requested families
	 */
	private static List<Diagnosis> diagnoses(JsonNode node, Set<String> targetFamilies, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Candidate generator diagnoses is not an array: " + source);
		List<Diagnosis> result = new ArrayList<>(array.size());
		for (JsonNode value : array)
		{
			if (!(value instanceof ObjectNode diagnosis) || !fields(diagnosis).equals(DIAGNOSIS_FIELDS))
				throw new IOException("Candidate generator diagnosis is malformed: " + source);
			String family = requiredText(diagnosis, "family_id", source);
			if (!targetFamilies.contains(family))
				throw new IOException("Candidate generator diagnosed an unrequested family: " + family);
			result.add(new Diagnosis(family, requiredText(diagnosis, "cause", source),
				textArray(diagnosis.path("evidence_ids"), "evidence_ids", source),
				safePaths(diagnosis.path("surrounding_artifacts"), source)));
		}
		return List.copyOf(result);
	}

	/**
	 * Parses candidate entries and rejects duplicate identifiers.
	 *
	 * @param node the candidate array
	 * @param source the response path
	 * @return the candidates
	 * @throws IOException if an entry is malformed or duplicated
	 */
	private static List<CandidateProposal> candidates(JsonNode node, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Candidate generator candidates is not an array: " + source);
		List<CandidateProposal> result = new ArrayList<>(array.size());
		Set<String> identifiers = new HashSet<>();
		for (JsonNode value : array)
		{
			if (!(value instanceof ObjectNode candidate) || !fields(candidate).equals(CANDIDATE_FIELDS))
				throw new IOException("Candidate generator candidate is malformed: " + source);
			CandidateProposal proposal = new CandidateProposal(requiredText(candidate, "candidate_id", source),
				requiredText(candidate, "rationale", source), requiredText(candidate, "replacement", source));
			if (!identifiers.add(proposal.identifier()))
				throw new IOException("Candidate generator returned a duplicate candidate: " + proposal.identifier());
			result.add(proposal);
		}
		return List.copyOf(result);
	}

	/**
	 * Parses safe project-relative artifact paths.
	 *
	 * @param node the array
	 * @param source the response path
	 * @return canonical slash-separated paths
	 * @throws IOException if a path is absolute or escapes its project
	 */
	private static List<String> safePaths(JsonNode node, Path source) throws IOException
	{
		List<String> values = textArray(node, "surrounding_artifacts", source);
		List<String> result = new ArrayList<>(values.size());
		for (String value : values)
		{
			Path path = Path.of(value);
			if (path.isAbsolute() || path.normalize().startsWith(".."))
				throw new IOException("Candidate generator named an unsafe surrounding artifact: " + value);
			result.add(path.normalize().toString().replace(path.getFileSystem().getSeparator(), "/"));
		}
		return List.copyOf(result);
	}

	/**
	 * Parses one nonblank text array.
	 *
	 * @param node the array
	 * @param field the field name
	 * @param source the response path
	 * @return the values
	 * @throws IOException if the array is malformed
	 */
	private static List<String> textArray(JsonNode node, String field, Path source) throws IOException
	{
		if (!(node instanceof ArrayNode array))
			throw new IOException("Candidate generator " + field + " is not an array: " + source);
		List<String> result = new ArrayList<>(array.size());
		for (JsonNode value : array)
		{
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Candidate generator " + field + " contains invalid text: " + source);
			result.add(value.asString());
		}
		return List.copyOf(result);
	}

	/**
	 * Requires one nonblank text field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the response path
	 * @return the value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Candidate generator has invalid " + field + ": " + source);
		return value.asString();
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
	 * Validates one open text value.
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
	 * Creates a harness request while keeping harness-specific launcher and artifact ownership outside this component.
	 */
	@FunctionalInterface
	public interface RequestFactory
	{
		/**
		 * Creates one independent generator request.
		 *
		 * @param profile the exact optimizer profile
		 * @param trustedPrompt trusted instructions containing no customer content
		 * @param readOnlyEvidence immutable customer evidence
		 * @param runRoot the durable operation root
		 * @return the typed harness request
		 * @throws IOException if request preparation fails
		 */
		HarnessEvaluationRequest create(HarnessModelProfile profile, String trustedPrompt, List<Path> readOnlyEvidence,
			Path runRoot) throws IOException;
	}

	/**
	 * Holds immutable candidate-generation inputs.
	 *
	 * @param championBundle the current immutable candidate bundle
	 * @param contractEvidenceRoot the isolated read-only directory containing the frozen certification contract
	 * @param contractRelativePath the contract document path below its evidence root
	 * @param failureEvidence the retained failure-evidence root
	 * @param targetPrimaryRule the only rule candidates may replace
	 * @param targetFamilies the limiting or edited families to diagnose
	 */
	public record Inputs(Path championBundle, Path contractEvidenceRoot, String contractRelativePath,
		Path failureEvidence, String targetPrimaryRule, Set<String> targetFamilies)
	{
		/**
		 * Validates and normalizes candidate-generation inputs.
		 *
		 * @param championBundle the current immutable candidate bundle
		 * @param contractEvidenceRoot the isolated read-only directory containing the frozen certification contract
		 * @param contractRelativePath the contract document path below its evidence root
		 * @param failureEvidence the retained failure-evidence root
		 * @param targetPrimaryRule the only rule candidates may replace
		 * @param targetFamilies the limiting or edited families to diagnose
		 */
		public Inputs
		{
			championBundle = Objects.requireNonNull(championBundle, "championBundle must not be null").
				toAbsolutePath().normalize();
			contractEvidenceRoot = Objects.requireNonNull(contractEvidenceRoot,
				"contractEvidenceRoot must not be null").
				toAbsolutePath().normalize();
			contractRelativePath = safeRelativePath(contractRelativePath, "contractRelativePath");
			failureEvidence = Objects.requireNonNull(failureEvidence, "failureEvidence must not be null").
				toAbsolutePath().normalize();
			targetPrimaryRule = safeRelativePath(targetPrimaryRule, "targetPrimaryRule");
			Objects.requireNonNull(targetFamilies, "targetFamilies must not be null");
			Set<String> families = new TreeSet<>();
			for (String family : targetFamilies)
				families.add(requireText(family, "target family"));
			if (families.isEmpty())
				throw new IllegalArgumentException("targetFamilies must not be empty");
			targetFamilies = Set.copyOf(families);
		}

		/**
		 * Returns the normalized contract document path.
		 *
		 * @return the document inside the isolated evidence root
		 */
		public Path contractDocument()
		{
			return contractEvidenceRoot.resolve(contractRelativePath).normalize();
		}
	}

	/**
	 * Validates one safe relative path.
	 *
	 * @param value the supplied path
	 * @param name the parameter name
	 * @return the canonical slash-separated path
	 */
	private static String safeRelativePath(String value, String name)
	{
		String text = requireText(value, name);
		Path path = Path.of(text);
		if (path.isAbsolute() || path.normalize().startsWith(".."))
			throw new IllegalArgumentException(name + " must be project-relative");
		return path.normalize().toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Describes one evidence-grounded failure cause.
	 *
	 * @param familyIdentifier the affected family
	 * @param cause the bounded diagnosis
	 * @param evidenceIdentifiers the cited retained evidence
	 * @param surroundingArtifacts likely surrounding prompt conflicts that require separate authorization
	 */
	public record Diagnosis(String familyIdentifier, String cause, List<String> evidenceIdentifiers,
		List<String> surroundingArtifacts)
	{
		/**
		 * Validates and snapshots one diagnosis.
		 *
		 * @param familyIdentifier the affected family
		 * @param cause the bounded diagnosis
		 * @param evidenceIdentifiers the cited retained evidence
		 * @param surroundingArtifacts likely surrounding prompt conflicts that require separate authorization
		 */
		public Diagnosis
		{
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			cause = requireText(cause, "cause");
			evidenceIdentifiers = List.copyOf(evidenceIdentifiers);
			surroundingArtifacts = List.copyOf(surroundingArtifacts);
		}
	}

	/**
	 * Describes one complete replacement for the target prompt.
	 *
	 * @param identifier the bounded local-to-this-certification-process candidate identifier
	 * @param rationale the evidence-grounded strategy
	 * @param replacement the complete replacement target content
	 */
	public record CandidateProposal(String identifier, String rationale, String replacement)
	{
		/**
		 * Validates one target replacement.
		 *
		 * @param identifier the bounded local-to-this-certification-process candidate identifier
		 * @param rationale the evidence-grounded strategy
		 * @param replacement the complete replacement target content
		 */
		public CandidateProposal
		{
			identifier = requireText(identifier, "identifier");
			if (!identifier.matches("[a-z0-9][a-z0-9._-]{0,63}"))
				throw new IllegalArgumentException("identifier must be a safe lowercase candidate identifier");
			rationale = requireText(rationale, "rationale");
			replacement = requireText(replacement, "replacement");
		}
	}

	/**
	 * Holds one complete bounded generator response.
	 *
	 * @param diagnoses evidence-grounded failure diagnoses
	 * @param candidates complete target-only candidate replacements
	 */
	public record Proposal(List<Diagnosis> diagnoses, List<CandidateProposal> candidates)
	{
		/**
		 * Validates and snapshots one proposal.
		 *
		 * @param diagnoses evidence-grounded failure diagnoses
		 * @param candidates complete target-only candidate replacements
		 */
		public Proposal
		{
			diagnoses = List.copyOf(diagnoses);
			candidates = List.copyOf(candidates);
		}
	}
}

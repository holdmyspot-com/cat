package io.github.cowwoc.cat.common.optimization.evaluator;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Defines a frozen semantic-evaluator protocol whose instructions and bounded untrusted evidence remain separate.
 */
public final class EvaluatorProtocol
{
	/** Maximum aggregate untrusted evidence size for one semantic evaluation request. */
	public static final int MAXIMUM_EVIDENCE_BYTES = 1024 * 1024;
	/** Maximum size of one untrusted evidence field. */
	public static final int MAXIMUM_EVIDENCE_FIELD_BYTES = 64 * 1024;

	private final String identifier;
	private final HarnessModelProfile modelProfile;
	private final String evaluatorPromptFingerprint;
	private final String rubricFingerprint;
	private final int maximumEvidenceBytes;
	private final Aggregation aggregation;
	private final String fingerprint;

	/**
	 * Creates one frozen protocol.
	 *
	 * @param identifier the protocol identifier
	 * @param modelProfile the evaluator model profile
	 * @param evaluatorPromptFingerprint the evaluator-owned prompt fingerprint
	 * @param rubricFingerprint the frozen rubric fingerprint
	 * @param maximumEvidenceBytes the aggregate evidence byte limit
	 * @param aggregation the verdict aggregation rule
	 */
	private EvaluatorProtocol(String identifier, HarnessModelProfile modelProfile, String evaluatorPromptFingerprint,
		String rubricFingerprint, int maximumEvidenceBytes, Aggregation aggregation)
	{
		this.identifier = requireText(identifier, "identifier");
		if (modelProfile == null)
			throw new NullPointerException("modelProfile must not be null");
		this.modelProfile = SupportedModelProfiles.requireSupported(ProfileRole.SEMANTIC_EVALUATOR, modelProfile);
		this.evaluatorPromptFingerprint = requireSha256(evaluatorPromptFingerprint, "evaluatorPromptFingerprint");
		this.rubricFingerprint = requireSha256(rubricFingerprint, "rubricFingerprint");
		if (maximumEvidenceBytes <= 0 || maximumEvidenceBytes > MAXIMUM_EVIDENCE_BYTES)
		{
			throw new IllegalArgumentException("maximumEvidenceBytes must be positive and no greater than " +
				MAXIMUM_EVIDENCE_BYTES + ": " + maximumEvidenceBytes);
		}
		this.maximumEvidenceBytes = maximumEvidenceBytes;
		if (aggregation == null)
			throw new NullPointerException("aggregation must not be null");
		this.aggregation = aggregation;
		this.fingerprint = sha256(String.join("\u0000", identifier, modelProfile.model(),
			modelProfile.effort().name(), evaluatorPromptFingerprint, rubricFingerprint,
			Integer.toString(maximumEvidenceBytes), aggregation.name()));
	}

	/**
	 * Creates a single-evaluator protocol.
	 *
	 * @param identifier the protocol identifier
	 * @param modelProfile the evaluator model profile
	 * @param evaluatorPromptFingerprint the evaluator-owned prompt fingerprint
	 * @param rubricFingerprint the frozen rubric fingerprint
	 * @param maximumEvidenceBytes the aggregate evidence byte limit
	 * @return the protocol
	 */
	public static EvaluatorProtocol single(String identifier, HarnessModelProfile modelProfile,
		String evaluatorPromptFingerprint, String rubricFingerprint, int maximumEvidenceBytes)
	{
		return new EvaluatorProtocol(identifier, modelProfile, evaluatorPromptFingerprint, rubricFingerprint,
			maximumEvidenceBytes, Aggregation.SINGLE);
	}

	/**
	 * Creates a two-evaluator protocol with a blinded tiebreaker when the first decisions differ.
	 *
	 * @param identifier the protocol identifier
	 * @param modelProfile the evaluator model profile
	 * @param evaluatorPromptFingerprint the evaluator-owned prompt fingerprint
	 * @param rubricFingerprint the frozen rubric fingerprint
	 * @param maximumEvidenceBytes the aggregate evidence byte limit
	 * @return the protocol
	 */
	public static EvaluatorProtocol adjudicated(String identifier, HarnessModelProfile modelProfile,
		String evaluatorPromptFingerprint, String rubricFingerprint, int maximumEvidenceBytes)
	{
		return new EvaluatorProtocol(identifier, modelProfile, evaluatorPromptFingerprint, rubricFingerprint,
			maximumEvidenceBytes, Aggregation.TWO_PLUS_TIEBREAKER);
	}

	/**
	 * Creates an isolated evaluation request from frozen assertions and bounded evidence.
	 *
	 * @param assertions the applicable atomic assertions
	 * @param evidence the untrusted evidence fields
	 * @return the isolated request
	 */
	public Request request(List<Assertion> assertions, List<Evidence> evidence)
	{
		return new Request(fingerprint, maximumEvidenceBytes, assertions, evidence);
	}

	/**
	 * Applies this protocol's frozen aggregation rule.
	 *
	 * @param verdicts the independent verdicts in execution order
	 * @return the final verdict
	 */
	public Verdict aggregate(List<Verdict> verdicts)
	{
		List<Verdict> values = copyList(verdicts, "verdicts");
		if (aggregation == Aggregation.SINGLE)
		{
			if (values.size() != 1)
				throw new IllegalArgumentException("A single-evaluator protocol requires exactly one verdict");
			return values.getFirst();
		}
		if (values.size() < 2 || values.size() > 3)
			throw new IllegalArgumentException("An adjudicated protocol requires two verdicts and at most one tiebreaker");
		if (values.get(0) == values.get(1))
		{
			if (values.size() == 3)
				throw new IllegalArgumentException("A tiebreaker is not permitted when the first two evaluators agree");
			return values.getFirst();
		}
		if (values.size() != 3)
			throw new IllegalArgumentException("A disagreement requires one blinded tiebreaker verdict");
		return values.get(2);
	}

	/**
	 * Validates independent evaluator responses and adjudicates each disputed atomic assertion separately.
	 *
	 * @param request the frozen isolated request
	 * @param responses two complete independent responses and, only when needed, one disputed-assertion response
	 * @return the canonical final assertion verdicts and formal outcome
	 */
	public Adjudication adjudicate(Request request, List<List<AssertionVerdict>> responses)
	{
		if (request == null)
			throw new NullPointerException("request must not be null");
		if (!request.protocolFingerprint().equals(fingerprint))
			throw new IllegalArgumentException("Evaluation request belongs to another protocol");
		List<List<AssertionVerdict>> values = copyList(responses, "responses");
		if (aggregation == Aggregation.SINGLE)
		{
			if (values.size() != 1)
				throw new IllegalArgumentException("A single-evaluator protocol requires exactly one response");
			List<AssertionVerdict> finalVerdicts = request.validateComplete(values.getFirst());
			return new Adjudication(outcome(finalVerdicts), finalVerdicts);
		}
		if (values.size() < 2 || values.size() > 3)
			throw new IllegalArgumentException("An adjudicated protocol requires two responses and at most one tiebreaker");

		List<AssertionVerdict> first = request.validateComplete(values.get(0));
		List<AssertionVerdict> second = request.validateComplete(values.get(1));
		Map<String, AssertionVerdict> firstById = byAssertion(first);
		Map<String, AssertionVerdict> secondById = byAssertion(second);
		Set<String> disputed = new TreeSet<>();
		for (Assertion assertion : request.assertions())
		{
			String assertionId = assertion.identifier();
			if (firstById.get(assertionId).verdict() != secondById.get(assertionId).verdict())
				disputed.add(assertionId);
		}
		if (disputed.isEmpty())
		{
			if (values.size() == 3)
				throw new IllegalArgumentException("A tiebreaker is not permitted when the evaluators agree");
			return new Adjudication(outcome(first), first);
		}
		if (values.size() != 3)
			throw new IllegalArgumentException("Disputed assertions require one blinded tiebreaker response");

		List<AssertionVerdict> tiebreaker = request.validateSubset(values.get(2), disputed);
		Map<String, AssertionVerdict> tiebreakerById = byAssertion(tiebreaker);
		List<AssertionVerdict> finalVerdicts = new ArrayList<>(first.size());
		for (Assertion assertion : request.assertions())
		{
			String assertionId = assertion.identifier();
			if (disputed.contains(assertionId))
				finalVerdicts.add(tiebreakerById.get(assertionId));
			else
				finalVerdicts.add(firstById.get(assertionId));
		}
		return new Adjudication(outcome(finalVerdicts), finalVerdicts);
	}

	/**
	 * Indexes canonical assertion verdicts.
	 *
	 * @param verdicts the verdicts
	 * @return the identifier index
	 */
	private static Map<String, AssertionVerdict> byAssertion(List<AssertionVerdict> verdicts)
	{
		Map<String, AssertionVerdict> result = new LinkedHashMap<>();
		for (AssertionVerdict verdict : verdicts)
			result.put(verdict.assertionIdentifier(), verdict);
		return result;
	}

	/**
	 * Reduces complete assertion verdicts to the formal all-assertions outcome.
	 *
	 * @param verdicts the complete final verdict set
	 * @return pass only when every assertion passes
	 */
	private static FormalOutcome outcome(List<AssertionVerdict> verdicts)
	{
		for (AssertionVerdict verdict : verdicts)
		{
			if (verdict.verdict() == Verdict.FAIL)
				return FormalOutcome.FAIL;
		}
		return FormalOutcome.PASS;
	}

	/**
	 * Returns the immutable protocol identity.
	 *
	 * @return the protocol fingerprint
	 */
	public String fingerprint()
	{
		return fingerprint;
	}

	/**
	 * Returns the protocol identifier.
	 *
	 * @return the protocol identifier
	 */
	public String identifier()
	{
		return identifier;
	}

	/**
	 * Returns the evaluator model profile.
	 *
	 * @return the model profile
	 */
	public HarnessModelProfile modelProfile()
	{
		return modelProfile;
	}

	/**
	 * Returns the evaluator-owned prompt fingerprint.
	 *
	 * @return the prompt fingerprint
	 */
	public String evaluatorPromptFingerprint()
	{
		return evaluatorPromptFingerprint;
	}

	/**
	 * Returns the frozen rubric fingerprint.
	 *
	 * @return the rubric fingerprint
	 */
	public String rubricFingerprint()
	{
		return rubricFingerprint;
	}

	/**
	 * Returns the aggregate untrusted-evidence byte limit.
	 *
	 * @return the positive byte limit
	 */
	public int maximumEvidenceBytes()
	{
		return maximumEvidenceBytes;
	}

	/**
	 * Names the aggregation behavior.
	 */
	public enum Aggregation
	{
		SINGLE,
		TWO_PLUS_TIEBREAKER
	}

	/**
	 * Returns the frozen response aggregation rule.
	 *
	 * @return the aggregation rule
	 */
	public Aggregation aggregation()
	{
		return aggregation;
	}

	/**
	 * Names one evaluator verdict.
	 */
	public enum Verdict
	{
		PASS,
		FAIL
	}

	/**
	 * Names the complete formal outcome after every applicable assertion is evaluated.
	 */
	public enum FormalOutcome
	{
		PASS,
		FAIL
	}

	/**
	 * Marks whether a request field may influence evaluator instructions.
	 */
	public enum Trust
	{
		UNTRUSTED_DATA
	}

	/**
	 * Names a bounded evidence field's source category.
	 */
	public enum EvidenceKind
	{
		EXECUTOR_OUTPUT,
		TOOL_OUTPUT,
		RETRIEVED_TEXT,
		FILE_CONTENT
	}

	/**
	 * Holds one atomic evaluator-owned assertion.
	 *
	 * @param identifier the frozen assertion identifier
	 * @param passProposition the exact atomic pass rule
	 * @param permittedEvidenceIdentifiers evidence fields the evaluator may cite
	 */
	public record Assertion(String identifier, String passProposition, Set<String> permittedEvidenceIdentifiers)
	{
		/**
		 * Validates and snapshots one assertion.
		 *
		 * @param identifier the frozen assertion identifier
		 * @param passProposition the exact atomic pass rule
		 * @param permittedEvidenceIdentifiers evidence fields the evaluator may cite
		 */
		public Assertion
		{
			identifier = requireText(identifier, "identifier");
			passProposition = requireText(passProposition, "passProposition");
			permittedEvidenceIdentifiers = copySet(permittedEvidenceIdentifiers,
				"permittedEvidenceIdentifiers");
		}
	}

	/**
	 * Holds one untrusted byte-exact evidence field.
	 *
	 * @param identifier the evidence identifier
	 * @param kind the evidence source category
	 * @param content the exact untrusted bytes
	 */
	public record Evidence(String identifier, EvidenceKind kind, byte[] content)
	{
		/**
		 * Validates and copies one evidence field.
		 *
		 * @param identifier the evidence identifier
		 * @param kind the evidence source category
		 * @param content the exact untrusted bytes
		 */
		public Evidence
		{
			identifier = requireText(identifier, "identifier");
			if (kind == null)
				throw new NullPointerException("kind must not be null");
			if (content == null)
				throw new NullPointerException("content must not be null");
			content = Arrays.copyOf(content, content.length);
		}

		/**
		 * Returns a defensive copy of the evidence bytes.
		 *
		 * @return the exact evidence bytes
		 */
		@Override
		public byte[] content()
		{
			return Arrays.copyOf(content, content.length);
		}

		/**
		 * Returns the evidence trust classification.
		 *
		 * @return untrusted data
		 */
		public Trust trust()
		{
			return Trust.UNTRUSTED_DATA;
		}
	}

	/**
	 * Holds the evaluator's typed decision for one assertion.
	 *
	 * @param assertionIdentifier the decided assertion
	 * @param verdict the pass or fail decision
	 * @param citedEvidenceIdentifiers evidence supporting the decision
	 */
	public record AssertionVerdict(String assertionIdentifier, Verdict verdict,
		Set<String> citedEvidenceIdentifiers)
	{
		/**
		 * Validates and snapshots one assertion verdict.
		 *
		 * @param assertionIdentifier the decided assertion
		 * @param verdict the pass or fail decision
		 * @param citedEvidenceIdentifiers evidence supporting the decision
		 */
		public AssertionVerdict
		{
			assertionIdentifier = requireText(assertionIdentifier, "assertionIdentifier");
			if (verdict == null)
				throw new NullPointerException("verdict must not be null");
			citedEvidenceIdentifiers = copySet(citedEvidenceIdentifiers, "citedEvidenceIdentifiers");
			if (citedEvidenceIdentifiers.isEmpty())
				throw new IllegalArgumentException("An assertion verdict must cite retained evidence");
		}
	}

	/**
	 * Holds the per-assertion adjudication result consumed by formal certification.
	 *
	 * @param outcome pass only when every applicable assertion passed
	 * @param finalVerdicts the canonical final verdict for every assertion
	 */
	public record Adjudication(FormalOutcome outcome, List<AssertionVerdict> finalVerdicts)
	{
		/**
		 * Validates and snapshots one adjudication result.
		 *
		 * @param outcome pass only when every applicable assertion passed
		 * @param finalVerdicts the canonical final verdict for every assertion
		 */
		public Adjudication
		{
			if (outcome == null)
				throw new NullPointerException("outcome must not be null");
			finalVerdicts = copyList(finalVerdicts, "finalVerdicts");
			if (finalVerdicts.isEmpty())
				throw new IllegalArgumentException("finalVerdicts must not be empty");
		}
	}

	/**
	 * Holds one evaluator request whose trusted assertions and untrusted evidence remain separate fields.
	 */
	public static final class Request
	{
		private final String protocolFingerprint;
		private final List<Assertion> assertions;
		private final List<Evidence> evidence;
		private final Map<String, Assertion> assertionsById;
		private final Set<String> evidenceIds;

		/**
		 * Validates and snapshots one request.
		 *
		 * @param protocolFingerprint the frozen protocol identity
		 * @param maximumEvidenceBytes the aggregate evidence limit
		 * @param assertions the trusted evaluator-owned assertions
		 * @param evidence the untrusted evidence fields
		 */
		private Request(String protocolFingerprint, int maximumEvidenceBytes, List<Assertion> assertions,
			List<Evidence> evidence)
		{
			this.protocolFingerprint = protocolFingerprint;
			this.assertions = sortedUnique(assertions, Assertion::identifier, "assertion");
			this.evidence = sortedUnique(evidence, Evidence::identifier, "evidence");
			if (this.assertions.isEmpty())
				throw new IllegalArgumentException("An evaluator request requires at least one assertion");

			this.assertionsById = new LinkedHashMap<>();
			for (Assertion assertion : this.assertions)
				assertionsById.put(assertion.identifier(), assertion);
			this.evidenceIds = new HashSet<>();
			long evidenceBytes = 0;
			for (Evidence field : this.evidence)
			{
				evidenceIds.add(field.identifier());
				int fieldBytes = field.content().length;
				if (fieldBytes > MAXIMUM_EVIDENCE_FIELD_BYTES)
				{
					throw new IllegalArgumentException("Evidence field exceeds the byte limit: " + field.identifier());
				}
				evidenceBytes += fieldBytes;
				if (evidenceBytes > maximumEvidenceBytes)
					throw new IllegalArgumentException("Evidence exceeds the protocol byte limit: " + field.identifier());
			}
			for (Assertion assertion : this.assertions)
			{
				if (!evidenceIds.containsAll(assertion.permittedEvidenceIdentifiers()))
				{
					Set<String> missing = new TreeSet<>(assertion.permittedEvidenceIdentifiers());
					missing.removeAll(evidenceIds);
					throw new IllegalArgumentException("Missing required evidence for " + assertion.identifier() + ": " +
						missing);
				}
			}
		}

		/**
		 * Validates a complete typed response and reduces it to one formal outcome.
		 *
		 * @param verdicts one verdict for every applicable assertion
		 * @return pass only when every assertion passes
		 */
		public FormalOutcome accept(List<AssertionVerdict> verdicts)
		{
			return outcome(validateComplete(verdicts));
		}

		/**
		 * Validates one complete evaluator response.
		 *
		 * @param verdicts the response verdicts
		 * @return the canonical verdicts
		 */
		private List<AssertionVerdict> validateComplete(List<AssertionVerdict> verdicts)
		{
			return validateSubset(verdicts, assertionsById.keySet());
		}

		/**
		 * Validates one response against an exact assertion subset.
		 *
		 * @param verdicts the response verdicts
		 * @param expectedAssertionIds the exact permitted assertion identifiers
		 * @return the canonical verdicts
		 */
		private List<AssertionVerdict> validateSubset(List<AssertionVerdict> verdicts,
			Set<String> expectedAssertionIds)
		{
			List<AssertionVerdict> values = sortedUnique(verdicts, AssertionVerdict::assertionIdentifier, "verdict");
			Set<String> actualAssertionIds = new TreeSet<>();
			for (AssertionVerdict value : values)
				actualAssertionIds.add(value.assertionIdentifier());
			if (!actualAssertionIds.equals(expectedAssertionIds))
			{
				if (expectedAssertionIds.size() == assertions.size())
					throw new IllegalArgumentException("The evaluator must return one verdict for every assertion");
				throw new IllegalArgumentException("Tiebreaker verdict set does not match disputed assertions");
			}

			for (AssertionVerdict value : values)
			{
				Assertion assertion = assertionsById.get(value.assertionIdentifier());
				if (assertion == null)
					throw new IllegalArgumentException("Undeclared assertion verdict: " + value.assertionIdentifier());
				for (String citation : value.citedEvidenceIdentifiers())
				{
					if (!assertion.permittedEvidenceIdentifiers().contains(citation))
						throw new IllegalArgumentException("Undeclared evidence citation: " + citation);
				}
			}
			return values;
		}

		/**
		 * Returns the frozen protocol identity.
		 *
		 * @return the protocol fingerprint
		 */
		public String protocolFingerprint()
		{
			return protocolFingerprint;
		}

		/**
		 * Returns the evaluator-owned assertions.
		 *
		 * @return the assertions
		 */
		public List<Assertion> assertions()
		{
			return assertions;
		}

		/**
		 * Returns the untrusted evidence fields.
		 *
		 * @return the evidence
		 */
		public List<Evidence> evidence()
		{
			return evidence;
		}
	}

	/**
	 * Validates text.
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
	 * Validates one lowercase SHA-256 value.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireSha256(String value, String name)
	{
		requireText(value, name);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
		return value;
	}

	/**
	 * Copies a nonnull list.
	 *
	 * @param values the values
	 * @param name the parameter name
	 * @param <T> the element type
	 * @return the immutable copy
	 */
	private static <T> List<T> copyList(List<T> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		for (T value : values)
		{
			if (value == null)
				throw new NullPointerException(name + " must not contain null elements");
		}
		return List.copyOf(values);
	}

	/**
	 * Copies a nonnull set in canonical order.
	 *
	 * @param values the values
	 * @param name the parameter name
	 * @return the immutable copy
	 */
	private static Set<String> copySet(Set<String> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		for (String value : values)
			requireText(value, name + " element");
		return Set.copyOf(new TreeSet<>(values));
	}

	/**
	 * Sorts values and rejects duplicate identifiers.
	 *
	 * @param values the values
	 * @param identifier the identifier accessor
	 * @param kind the value kind
	 * @param <T> the element type
	 * @return the immutable sorted values
	 */
	private static <T> List<T> sortedUnique(List<T> values, java.util.function.Function<T, String> identifier,
		String kind)
	{
		List<T> result = new ArrayList<>(copyList(values, kind + "s"));
		result.sort(Comparator.comparing(identifier));
		String previous = null;
		for (T value : result)
		{
			String current = identifier.apply(value);
			if (current.equals(previous))
				throw new IllegalArgumentException("Duplicate " + kind + " identifier: " + current);
			previous = current;
		}
		return List.copyOf(result);
	}

	/**
	 * Calculates a lowercase SHA-256 fingerprint.
	 *
	 * @param value the value
	 * @return the fingerprint
	 */
	private static String sha256(String value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}

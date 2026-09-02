package io.github.cowwoc.cat.common.optimization.contract;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Freezes a source-traced prompt certification contract after deterministic schema and coverage validation.
 */
public final class CertificationContract
{
	private static final String SCHEMA_VERSION = "certification-contract-v1";
	private final Draft content;
	private final String fingerprint;

	/**
	 * Creates one validated immutable contract.
	 *
	 * @param content the canonical contract content
	 */
	private CertificationContract(Draft content)
	{
		this.content = canonicalize(content);
		this.fingerprint = fingerprint(this.content);
	}

	/**
	 * Validates and freezes a proposed contract.
	 *
	 * @param draft the agent-produced contract draft
	 * @return a ready frozen contract or every deterministic blocking issue
	 */
	public static Result freeze(Draft draft)
	{
		if (draft == null)
			throw new NullPointerException("draft must not be null");

		List<Issue> issues = validate(draft);
		if (!issues.isEmpty())
			return new Result(Status.BLOCKED, Optional.empty(), issues);
		return new Result(Status.READY, Optional.of(new CertificationContract(draft)), List.of());
	}

	/**
	 * Returns the versioned schema identifier.
	 *
	 * @return the schema identifier
	 */
	public String schemaVersion()
	{
		return content.schemaVersion();
	}

	/**
	 * Returns the immutable canonical contract content.
	 *
	 * @return the contract content
	 */
	public Draft content()
	{
		return content;
	}

	/**
	 * Returns the lowercase SHA-256 identity of the canonical contract.
	 *
	 * @return the contract fingerprint
	 */
	public String fingerprint()
	{
		return fingerprint;
	}

	/**
	 * Validates referential integrity and complete family coverage.
	 *
	 * @param draft the proposed contract
	 * @return deterministic blocking issues
	 */
	private static List<Issue> validate(Draft draft)
	{
		List<Issue> issues = new ArrayList<>();
		if (!draft.schemaVersion().equals(SCHEMA_VERSION))
		{
			issues.add(new Issue(IssueCode.UNSUPPORTED_SCHEMA_VERSION, draft.schemaVersion(),
				"Expected " + SCHEMA_VERSION));
		}
		Map<String, Artifact> artifacts = index(draft.artifacts(), Artifact::identifier, "artifact", issues);
		Map<String, SourceSpan> spans = index(draft.sourceSpans(), SourceSpan::identifier, "source span", issues);
		Map<String, Requirement> requirements = index(draft.requirements(), Requirement::identifier, "requirement",
			issues);
		Map<String, ApplicabilityRule> rules = index(draft.applicabilityRules(), ApplicabilityRule::identifier,
			"applicability rule", issues);
		Map<String, Distribution> distributions = index(draft.distributions(), Distribution::identifier,
			"distribution", issues);
		Map<String, Assertion> assertions = index(draft.assertions(), Assertion::identifier, "assertion", issues);
		index(draft.families(), LeafFamily::identifier, "leaf family", issues);
		index(draft.promptTests(), PromptTest::identifier, "promptTest", issues);
		Set<String> protocols = uniqueStrings(draft.evaluatorProtocolIds(), "evaluator protocol", issues);

		for (Artifact artifact : draft.artifacts())
			validateFingerprint(artifact.identifier(), artifact.contentFingerprint(), issues);
		for (PromptTest promptTest : draft.promptTests())
			validateFingerprint(promptTest.identifier(), promptTest.contentFingerprint(), issues);
		for (SourceSpan span : draft.sourceSpans())
		{
			if (!artifacts.containsKey(span.artifactIdentifier()))
				issues.add(new Issue(IssueCode.MISSING_ARTIFACT, span.identifier(), span.artifactIdentifier()));
		}

		for (ApplicabilityRule rule : draft.applicabilityRules())
		{
			if (!rule.outcomes().equals(EnumSet.allOf(ApplicabilityOutcome.class)))
			{
				issues.add(new Issue(IssueCode.INCOMPLETE_APPLICABILITY_OUTCOMES, rule.identifier(),
					"Expected applicable, not applicable, and invalid evidence"));
			}
		}

		Set<String> coveredRequirements = new HashSet<>();
		Set<String> assignedAssertions = new HashSet<>();
		for (Requirement requirement : draft.requirements())
		{
			for (String spanId : requirement.sourceSpanIdentifiers())
			{
				if (!spans.containsKey(spanId))
					issues.add(new Issue(IssueCode.MISSING_SOURCE_SPAN, requirement.identifier(), spanId));
			}
			for (String ruleId : requirement.applicabilityRuleIdentifiers())
			{
				if (!rules.containsKey(ruleId))
					issues.add(new Issue(IssueCode.MISSING_APPLICABILITY_RULE, requirement.identifier(), ruleId));
			}
			for (String dependencyId : requirement.dependencyIdentifiers())
			{
				if (!requirements.containsKey(dependencyId))
					issues.add(new Issue(IssueCode.MISSING_REQUIREMENT, requirement.identifier(), dependencyId));
			}
		}

		for (Assertion assertion : draft.assertions())
		{
			if (!requirements.containsKey(assertion.requirementIdentifier()))
			{
				issues.add(new Issue(IssueCode.MISSING_REQUIREMENT, assertion.identifier(),
					assertion.requirementIdentifier()));
			}
			if (!protocols.contains(assertion.evaluatorProtocolIdentifier()))
			{
				issues.add(new Issue(IssueCode.MISSING_EVALUATOR_PROTOCOL, assertion.identifier(),
					assertion.evaluatorProtocolIdentifier()));
			}
		}

		for (LeafFamily family : draft.families())
		{
			if (!distributions.containsKey(family.distributionIdentifier()))
			{
				issues.add(new Issue(IssueCode.MISSING_DISTRIBUTION, family.identifier(),
					family.distributionIdentifier()));
			}
			for (String requirementId : family.requirementIdentifiers())
			{
				if (!requirements.containsKey(requirementId))
					issues.add(new Issue(IssueCode.MISSING_REQUIREMENT, family.identifier(), requirementId));
				else
					coveredRequirements.add(requirementId);
			}
			for (String ruleId : family.applicabilityRuleIdentifiers())
			{
				if (!rules.containsKey(ruleId))
					issues.add(new Issue(IssueCode.MISSING_APPLICABILITY_RULE, family.identifier(), ruleId));
			}
			for (String assertionId : family.assertionIdentifiers())
			{
				Assertion assertion = assertions.get(assertionId);
				if (assertion == null)
					issues.add(new Issue(IssueCode.MISSING_ASSERTION, family.identifier(), assertionId));
				else
					assignedAssertions.add(assertionId);
			}
		}

		for (String requirementId : requirements.keySet())
		{
			if (!coveredRequirements.contains(requirementId))
				issues.add(new Issue(IssueCode.UNCOVERED_REQUIREMENT, requirementId, "No leaf family covers it"));
		}
		for (String assertionId : assertions.keySet())
		{
			if (!assignedAssertions.contains(assertionId))
				issues.add(new Issue(IssueCode.UNASSIGNED_ASSERTION, assertionId, "No leaf family assigns it"));
		}
		if (draft.requirements().isEmpty())
		{
			issues.add(new Issue(IssueCode.EMPTY_REQUIREMENTS, draft.schemaVersion(),
				"At least one requirement is required"));
		}
		if (draft.families().isEmpty())
			issues.add(new Issue(IssueCode.EMPTY_FAMILIES, draft.schemaVersion(), "At least one leaf family is required"));

		issues.sort(Comparator.comparing(Issue::code).thenComparing(Issue::subject).thenComparing(Issue::detail));
		return List.copyOf(issues);
	}

	/**
	 * Builds a unique identifier index and records duplicates.
	 *
	 * @param values the values to index
	 * @param identifier the identifier accessor
	 * @param kind the value kind for diagnostics
	 * @param issues the issue accumulator
	 * @param <T> the value type
	 * @return the first value for each identifier
	 */
	private static <T> Map<String, T> index(List<T> values, Function<T, String> identifier, String kind,
		List<Issue> issues)
	{
		Map<String, T> result = new HashMap<>();
		for (T value : values)
		{
			String id = identifier.apply(value);
			if (result.putIfAbsent(id, value) != null)
				issues.add(new Issue(IssueCode.DUPLICATE_IDENTIFIER, id, "Duplicate " + kind + " identifier"));
		}
		return result;
	}

	/**
	 * Builds a unique string set and records duplicates.
	 *
	 * @param values the values to index
	 * @param kind the value kind for diagnostics
	 * @param issues the issue accumulator
	 * @return the unique values
	 */
	private static Set<String> uniqueStrings(List<String> values, String kind, List<Issue> issues)
	{
		Set<String> result = new HashSet<>();
		for (String value : values)
		{
			if (!result.add(value))
				issues.add(new Issue(IssueCode.DUPLICATE_IDENTIFIER, value, "Duplicate " + kind + " identifier"));
		}
		return result;
	}

	/**
	 * Records an invalid artifact fingerprint.
	 *
	 * @param identifier the artifact identifier
	 * @param fingerprint the declared fingerprint
	 * @param issues the issue accumulator
	 */
	private static void validateFingerprint(String identifier, String fingerprint, List<Issue> issues)
	{
		if (!fingerprint.matches("[0-9a-f]{64}"))
			issues.add(new Issue(IssueCode.INVALID_FINGERPRINT, identifier, fingerprint));
	}

	/**
	 * Canonicalizes every unordered top-level list.
	 *
	 * @param draft the validated draft
	 * @return canonical content
	 */
	private static Draft canonicalize(Draft draft)
	{
		return new Draft(draft.schemaVersion(), sorted(draft.artifacts(), Artifact::identifier),
			sorted(draft.sourceSpans(), SourceSpan::identifier), sorted(draft.requirements(), Requirement::identifier),
			sorted(draft.applicabilityRules(), ApplicabilityRule::identifier),
			sorted(draft.distributions(), Distribution::identifier), sorted(draft.families(), LeafFamily::identifier),
			sorted(draft.assertions(), Assertion::identifier), draft.evaluatorProtocolIds().stream().sorted().toList(),
			sorted(draft.promptTests(), PromptTest::identifier));
	}

	/**
	 * Sorts a record list by its stable identifier.
	 *
	 * @param values the values
	 * @param identifier the identifier accessor
	 * @param <T> the value type
	 * @return the immutable sorted list
	 */
	private static <T> List<T> sorted(List<T> values, Function<T, String> identifier)
	{
		return values.stream().sorted(Comparator.comparing(identifier)).toList();
	}

	/**
	 * Calculates the identity of a canonical contract.
	 *
	 * @param draft the canonical contract
	 * @return its lowercase SHA-256 fingerprint
	 */
	private static String fingerprint(Draft draft)
	{
		CanonicalText canonical = new CanonicalText();
		canonical.add(draft.schemaVersion());
		draft.artifacts().forEach(value -> canonical.add("artifact", value.identifier(), value.projectRelativePath(),
			value.contentFingerprint()));
		draft.sourceSpans().forEach(value -> canonical.add("span", value.identifier(), value.artifactIdentifier(),
			Integer.toString(value.startPosition()), Integer.toString(value.endPosition()), value.normalizedSource()));
		draft.requirements().forEach(value -> canonical.add("requirement", value.identifier(), value.atomicProposition(),
			value.sourceSpanIdentifiers().toString(), value.applicabilityRuleIdentifiers().toString(),
			value.dependencyIdentifiers().toString()));
		draft.applicabilityRules().forEach(value -> canonical.add("rule", value.identifier(), value.predicateSchema(),
			value.requiredEvidenceFields().toString(), value.outcomes().toString()));
		draft.distributions().forEach(value -> canonical.add("distribution", value.identifier(), value.samplerIdentifier(),
			value.superficialVariationDimensions().toString(), value.exclusionPredicates().toString(),
			value.seedNamespace(), value.supportValidationReceipt()));
		for (LeafFamily value : draft.families())
		{
			String parentIdentifier = value.parentIdentifier();
			if (parentIdentifier == null)
				parentIdentifier = "";
			canonical.add("family", value.identifier(), parentIdentifier, value.requirementIdentifiers().toString(),
				value.applicabilityRuleIdentifiers().toString(), value.substantiveBehavior(),
				value.distributionIdentifier(), value.generatorIdentifier(), value.assertionIdentifiers().toString());
		}
		draft.assertions().forEach(value -> canonical.add("assertion", value.identifier(),
			value.requirementIdentifier(), value.classification().name(), value.atomicPassProposition(),
			value.requiredEvidenceFields().toString(), value.evaluatorProtocolIdentifier(),
			value.infrastructureFailureRule().name()));
		draft.evaluatorProtocolIds().forEach(value -> canonical.add("protocol", value));
		draft.promptTests().forEach(value -> canonical.add("test", value.identifier(), value.projectRelativePath(),
			value.contentFingerprint()));
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.value().getBytes(UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Validates required open text.
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
	 * Validates and snapshots a list.
	 *
	 * @param value the list
	 * @param name the parameter name
	 * @param <T> the element type
	 * @return an immutable copy
	 */
	private static <T> List<T> copyList(List<T> value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		for (T element : value)
		{
			if (element == null)
				throw new NullPointerException(name + " must not contain null elements");
		}
		return List.copyOf(value);
	}

	/**
	 * Validates and snapshots a set in canonical order.
	 *
	 * @param value the set
	 * @param name the parameter name
	 * @param <T> the element type
	 * @return an immutable sorted set
	 */
	private static <T extends Comparable<? super T>> Set<T> copySet(Set<T> value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		for (T element : value)
		{
			if (element == null)
				throw new NullPointerException(name + " must not contain null elements");
		}
		return Collections.unmodifiableSet(new TreeSet<>(value));
	}

	/**
	 * Names the deterministic extraction result.
	 */
	public enum Status
	{
		READY,
		BLOCKED
	}

	/**
	 * Names a deterministic reason that contract freezing is blocked.
	 */
	public enum IssueCode
	{
		UNSUPPORTED_SCHEMA_VERSION,
		DUPLICATE_IDENTIFIER,
		INVALID_FINGERPRINT,
		MISSING_ARTIFACT,
		MISSING_SOURCE_SPAN,
		MISSING_REQUIREMENT,
		MISSING_APPLICABILITY_RULE,
		INCOMPLETE_APPLICABILITY_OUTCOMES,
		MISSING_DISTRIBUTION,
		MISSING_ASSERTION,
		MISSING_EVALUATOR_PROTOCOL,
		UNCOVERED_REQUIREMENT,
		UNASSIGNED_ASSERTION,
		EMPTY_REQUIREMENTS,
		EMPTY_FAMILIES
	}

	/**
	 * Names an applicability predicate's complete result domain.
	 */
	public enum ApplicabilityOutcome
	{
		APPLICABLE,
		NOT_APPLICABLE,
		INVALID_EVIDENCE
	}

	/**
	 * Names whether an assertion has a deterministic or semantic verdict.
	 */
	public enum AssertionClassification
	{
		DETERMINISTIC,
		SEMANTIC
	}

	/**
	 * Names how evaluator infrastructure failure affects formal evidence.
	 */
	public enum InfrastructureFailureRule
	{
		EXCLUDE_FROM_FORMAL_OUTCOMES
	}

	/**
	 * Holds one deterministic blocking issue.
	 *
	 * @param code the issue category
	 * @param subject the affected identifier
	 * @param detail the missing or invalid value
	 */
	public record Issue(IssueCode code, String subject, String detail)
	{
		/**
		 * Validates one issue.
		 *
		 * @param code the issue category
		 * @param subject the affected identifier
		 * @param detail the missing or invalid value
		 */
		public Issue
		{
			if (code == null)
				throw new NullPointerException("code must not be null");
			subject = requireText(subject, "subject");
			detail = requireText(detail, "detail");
		}
	}

	/**
	 * Holds a validated freeze result.
	 *
	 * @param status whether the contract is ready
	 * @param contract the frozen contract when ready
	 * @param issues every blocking issue when blocked
	 */
	public record Result(Status status, Optional<CertificationContract> contract, List<Issue> issues)
	{
		/**
		 * Validates and snapshots one result.
		 *
		 * @param status whether the contract is ready
		 * @param contract the frozen contract when ready
		 * @param issues every blocking issue when blocked
		 */
		public Result
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			if (contract == null)
				throw new NullPointerException("contract must not be null");
			issues = copyList(issues, "issues");
			if (status == Status.READY && (contract.isEmpty() || !issues.isEmpty()))
				throw new IllegalArgumentException("A ready result requires one contract and no issues");
			if (status == Status.BLOCKED && (contract.isPresent() || issues.isEmpty()))
				throw new IllegalArgumentException("A blocked result requires issues and no contract");
		}
	}

	/**
	 * Holds all proposed contract records before deterministic validation.
	 *
	 * @param schemaVersion the contract schema version
	 * @param artifacts the source inventory
	 * @param sourceSpans the source spans
	 * @param requirements the atomic requirements
	 * @param applicabilityRules the applicability rules
	 * @param distributions the within-family distributions
	 * @param families the leaf promptTest families
	 * @param assertions the atomic assertions
	 * @param evaluatorProtocolIds the approved evaluator protocol identifiers
	 * @param promptTests the local promptTests
	 */
	public record Draft(String schemaVersion, List<Artifact> artifacts, List<SourceSpan> sourceSpans,
		List<Requirement> requirements, List<ApplicabilityRule> applicabilityRules, List<Distribution> distributions,
		List<LeafFamily> families, List<Assertion> assertions, List<String> evaluatorProtocolIds,
		List<PromptTest> promptTests)
	{
		/**
		 * Validates and snapshots draft fields without making semantic completeness claims.
		 *
		 * @param schemaVersion the contract schema version
		 * @param artifacts the source inventory
		 * @param sourceSpans the source spans
		 * @param requirements the atomic requirements
		 * @param applicabilityRules the applicability rules
		 * @param distributions the within-family distributions
		 * @param families the leaf promptTest families
		 * @param assertions the atomic assertions
		 * @param evaluatorProtocolIds the approved evaluator protocol identifiers
		 * @param promptTests the local promptTests
		 */
		public Draft
		{
			schemaVersion = requireText(schemaVersion, "schemaVersion");
			artifacts = copyList(artifacts, "artifacts");
			sourceSpans = copyList(sourceSpans, "sourceSpans");
			requirements = copyList(requirements, "requirements");
			applicabilityRules = copyList(applicabilityRules, "applicabilityRules");
			distributions = copyList(distributions, "distributions");
			families = copyList(families, "families");
			assertions = copyList(assertions, "assertions");
			evaluatorProtocolIds = copyList(evaluatorProtocolIds, "evaluatorProtocolIds");
			for (String protocol : evaluatorProtocolIds)
				requireText(protocol, "evaluatorProtocolIds element");
			promptTests = copyList(promptTests, "promptTests");
		}
	}

	/**
	 * Holds one inventoried source artifact.
	 *
	 * @param identifier the stable artifact identifier
	 * @param projectRelativePath the necessary project-relative path
	 * @param contentFingerprint the lowercase SHA-256 content fingerprint
	 */
	public record Artifact(String identifier, String projectRelativePath, String contentFingerprint)
	{
		/**
		 * Validates one artifact's required fields.
		 *
		 * @param identifier the stable artifact identifier
		 * @param projectRelativePath the necessary project-relative path
		 * @param contentFingerprint the lowercase SHA-256 content fingerprint
		 */
		public Artifact
		{
			identifier = requireText(identifier, "identifier");
			projectRelativePath = requireText(projectRelativePath, "projectRelativePath");
			contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
		}
	}

	/**
	 * Holds one source-trace span.
	 *
	 * @param identifier the stable span identifier
	 * @param artifactIdentifier the containing artifact
	 * @param startPosition the inclusive canonical character position
	 * @param endPosition the exclusive canonical character position
	 * @param normalizedSource the quoted normalized source
	 */
	public record SourceSpan(String identifier, String artifactIdentifier, int startPosition, int endPosition,
		String normalizedSource)
	{
		/**
		 * Validates one source span.
		 *
		 * @param identifier the stable span identifier
		 * @param artifactIdentifier the containing artifact
		 * @param startPosition the inclusive canonical character position
		 * @param endPosition the exclusive canonical character position
		 * @param normalizedSource the quoted normalized source
		 */
		public SourceSpan
		{
			identifier = requireText(identifier, "identifier");
			artifactIdentifier = requireText(artifactIdentifier, "artifactIdentifier");
			if (startPosition < 0 || endPosition <= startPosition)
				throw new IllegalArgumentException("Source-span positions must define a nonempty range");
			normalizedSource = requireText(normalizedSource, "normalizedSource");
		}
	}

	/**
	 * Holds one atomic prompt requirement.
	 *
	 * @param identifier the stable requirement identifier
	 * @param atomicProposition the required behavior
	 * @param sourceSpanIdentifiers the complete source trace
	 * @param applicabilityRuleIdentifiers the applicability rules
	 * @param dependencyIdentifiers prerequisite requirement identifiers
	 */
	public record Requirement(String identifier, String atomicProposition, List<String> sourceSpanIdentifiers,
		List<String> applicabilityRuleIdentifiers, List<String> dependencyIdentifiers)
	{
		/**
		 * Validates and canonicalizes one requirement.
		 *
		 * @param identifier the stable requirement identifier
		 * @param atomicProposition the required behavior
		 * @param sourceSpanIdentifiers the complete source trace
		 * @param applicabilityRuleIdentifiers the applicability rules
		 * @param dependencyIdentifiers prerequisite requirement identifiers
		 */
		public Requirement
		{
			identifier = requireText(identifier, "identifier");
			atomicProposition = requireText(atomicProposition, "atomicProposition");
			sourceSpanIdentifiers = canonicalStrings(sourceSpanIdentifiers, "sourceSpanIdentifiers");
			applicabilityRuleIdentifiers = canonicalStrings(applicabilityRuleIdentifiers,
				"applicabilityRuleIdentifiers");
			dependencyIdentifiers = canonicalStrings(dependencyIdentifiers, "dependencyIdentifiers");
		}
	}

	/**
	 * Holds one executable applicability rule.
	 *
	 * @param identifier the stable rule identifier
	 * @param predicateSchema the executable predicate representation
	 * @param requiredEvidenceFields the fields required to evaluate it
	 * @param outcomes the complete result domain
	 */
	public record ApplicabilityRule(String identifier, String predicateSchema, Set<String> requiredEvidenceFields,
		Set<ApplicabilityOutcome> outcomes)
	{
		/**
		 * Validates and snapshots one applicability rule.
		 *
		 * @param identifier the stable rule identifier
		 * @param predicateSchema the executable predicate representation
		 * @param requiredEvidenceFields the fields required to evaluate it
		 * @param outcomes the complete result domain
		 */
		public ApplicabilityRule
		{
			identifier = requireText(identifier, "identifier");
			predicateSchema = requireText(predicateSchema, "predicateSchema");
			requiredEvidenceFields = copySet(requiredEvidenceFields, "requiredEvidenceFields");
			outcomes = copySet(outcomes, "outcomes");
		}
	}

	/**
	 * Holds one within-family promptTest distribution.
	 *
	 * @param identifier the stable distribution identifier
	 * @param samplerIdentifier the versioned sampler identifier
	 * @param superficialVariationDimensions the permitted superficial variations
	 * @param exclusionPredicates predicates that exclude invalid generated promptTests
	 * @param seedNamespace the independent random-seed namespace
	 * @param supportValidationReceipt evidence that the sampler stays inside the family definition
	 */
	public record Distribution(String identifier, String samplerIdentifier, List<String> superficialVariationDimensions,
		List<String> exclusionPredicates, String seedNamespace, String supportValidationReceipt)
	{
		/**
		 * Validates and canonicalizes one distribution.
		 *
		 * @param identifier the stable distribution identifier
		 * @param samplerIdentifier the versioned sampler identifier
		 * @param superficialVariationDimensions the permitted superficial variations
		 * @param exclusionPredicates predicates that exclude invalid generated promptTests
		 * @param seedNamespace the independent random-seed namespace
		 * @param supportValidationReceipt evidence that the sampler stays inside the family definition
		 */
		public Distribution
		{
			identifier = requireText(identifier, "identifier");
			samplerIdentifier = requireText(samplerIdentifier, "samplerIdentifier");
			superficialVariationDimensions = canonicalStrings(superficialVariationDimensions,
				"superficialVariationDimensions");
			exclusionPredicates = canonicalStrings(exclusionPredicates, "exclusionPredicates");
			seedNamespace = requireText(seedNamespace, "seedNamespace");
			supportValidationReceipt = requireText(supportValidationReceipt, "supportValidationReceipt");
		}
	}

	/**
	 * Holds one atomic assertion.
	 *
	 * @param identifier the stable assertion identifier
	 * @param requirementIdentifier the requirement it evaluates
	 * @param classification whether its verdict is deterministic or semantic
	 * @param atomicPassProposition the exact pass proposition
	 * @param requiredEvidenceFields the bounded evidence fields
	 * @param evaluatorProtocolIdentifier the approved evaluator protocol
	 * @param infrastructureFailureRule the handling of unavailable evaluator outcomes
	 */
	public record Assertion(String identifier, String requirementIdentifier, AssertionClassification classification,
		String atomicPassProposition, Set<String> requiredEvidenceFields, String evaluatorProtocolIdentifier,
		InfrastructureFailureRule infrastructureFailureRule)
	{
		/**
		 * Validates and snapshots one assertion.
		 *
		 * @param identifier the stable assertion identifier
		 * @param requirementIdentifier the requirement it evaluates
		 * @param classification whether its verdict is deterministic or semantic
		 * @param atomicPassProposition the exact pass proposition
		 * @param requiredEvidenceFields the bounded evidence fields
		 * @param evaluatorProtocolIdentifier the approved evaluator protocol
		 * @param infrastructureFailureRule the handling of unavailable evaluator outcomes
		 */
		public Assertion
		{
			identifier = requireText(identifier, "identifier");
			requirementIdentifier = requireText(requirementIdentifier, "requirementIdentifier");
			if (classification == null)
				throw new NullPointerException("classification must not be null");
			atomicPassProposition = requireText(atomicPassProposition, "atomicPassProposition");
			requiredEvidenceFields = copySet(requiredEvidenceFields, "requiredEvidenceFields");
			evaluatorProtocolIdentifier = requireText(evaluatorProtocolIdentifier, "evaluatorProtocolIdentifier");
			if (infrastructureFailureRule == null)
				throw new NullPointerException("infrastructureFailureRule must not be null");
		}
	}

	/**
	 * Holds one independently certified leaf promptTest family.
	 *
	 * @param identifier the stable family identifier
	 * @param parentIdentifier the optional descriptive parent identifier
	 * @param requirementIdentifiers the covered requirements
	 * @param applicabilityRuleIdentifiers the applicable rules
	 * @param substantiveBehavior the behavior boundary that defines this leaf
	 * @param distributionIdentifier the within-family distribution
	 * @param generatorIdentifier the versioned promptTest generator
	 * @param assertionIdentifiers the assertions scored for every applicable promptTest
	 */
	public record LeafFamily(String identifier, String parentIdentifier, List<String> requirementIdentifiers,
		List<String> applicabilityRuleIdentifiers, String substantiveBehavior, String distributionIdentifier,
		String generatorIdentifier, List<String> assertionIdentifiers)
	{
		/**
		 * Validates and canonicalizes one leaf family.
		 *
		 * @param identifier the stable family identifier
		 * @param parentIdentifier the optional descriptive parent identifier
		 * @param requirementIdentifiers the covered requirements
		 * @param applicabilityRuleIdentifiers the applicable rules
		 * @param substantiveBehavior the behavior boundary that defines this leaf
		 * @param distributionIdentifier the within-family distribution
		 * @param generatorIdentifier the versioned promptTest generator
		 * @param assertionIdentifiers the assertions scored for every applicable promptTest
		 */
		public LeafFamily
		{
			identifier = requireText(identifier, "identifier");
			if (parentIdentifier != null)
				parentIdentifier = requireText(parentIdentifier, "parentIdentifier");
			requirementIdentifiers = canonicalStrings(requirementIdentifiers, "requirementIdentifiers");
			applicabilityRuleIdentifiers = canonicalStrings(applicabilityRuleIdentifiers,
				"applicabilityRuleIdentifiers");
			substantiveBehavior = requireText(substantiveBehavior, "substantiveBehavior");
			distributionIdentifier = requireText(distributionIdentifier, "distributionIdentifier");
			generatorIdentifier = requireText(generatorIdentifier, "generatorIdentifier");
			assertionIdentifiers = canonicalStrings(assertionIdentifiers, "assertionIdentifiers");
		}
	}

	/**
	 * Holds one client-retained promptTest identity.
	 *
	 * @param identifier the stable test identifier
	 * @param projectRelativePath the necessary project-relative path
	 * @param contentFingerprint the lowercase SHA-256 content fingerprint
	 */
	public record PromptTest(String identifier, String projectRelativePath, String contentFingerprint)
	{
		/**
		 * Validates one prompt-test record.
		 *
		 * @param identifier the stable test identifier
		 * @param projectRelativePath the necessary project-relative path
		 * @param contentFingerprint the lowercase SHA-256 content fingerprint
		 */
		public PromptTest
		{
			identifier = requireText(identifier, "identifier");
			projectRelativePath = requireText(projectRelativePath, "projectRelativePath");
			contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
		}
	}

	/**
	 * Produces an unambiguous length-prefixed canonical text representation.
	 */
	private static final class CanonicalText
	{
		private final StringBuilder value = new StringBuilder();

		/**
		 * Creates an empty canonical representation.
		 */
		private CanonicalText()
		{
		}

		/**
		 * Appends fields without delimiter ambiguity.
		 *
		 * @param fields the ordered fields
		 */
		private void add(String... fields)
		{
			for (String field : fields)
				value.append(field.length()).append(':').append(field);
		}

		/**
		 * Returns the completed canonical representation.
		 *
		 * @return the representation
		 */
		private String value()
		{
			return value.toString();
		}
	}

	/**
	 * Canonicalizes and de-duplicates a string list.
	 *
	 * @param values the values
	 * @param name the parameter name
	 * @return the sorted unique values
	 */
	private static List<String> canonicalStrings(List<String> values, String name)
	{
		List<String> copy = copyList(values, name);
		for (String value : copy)
			requireText(value, name + " element");
		return List.copyOf(new TreeSet<>(copy));
	}
}

package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Binds every frozen leaf family to the exact executable prompt_test policy that formal trials may use.
 */
public final class PromptTestContractCatalog
{
	private final Map<String, PromptTestGenerationPolicy> policiesByFamily;

	/**
	 * Creates one validated immutable executable catalog.
	 *
	 * @param policiesByFamily the exact policy for every frozen family
	 */
	private PromptTestContractCatalog(Map<String, PromptTestGenerationPolicy> policiesByFamily)
	{
		this.policiesByFamily = Collections.unmodifiableMap(new HashMap<>(policiesByFamily));
	}

	/**
	 * Validates executable policies against one frozen certification contract.
	 *
	 * @param contract the frozen certification contract
	 * @param policies the executable prompt_test policies
	 * @return a ready catalog or every deterministic mismatch
	 */
	public static Result bind(CertificationContract contract, List<PromptTestGenerationPolicy> policies)
	{
		Objects.requireNonNull(contract, "contract must not be null");
		Objects.requireNonNull(policies, "policies must not be null");
		Map<String, PromptTestGenerationPolicy> byFamily = new HashMap<>();
		List<Issue> issues = new ArrayList<>();
		for (PromptTestGenerationPolicy policy : policies)
		{
			Objects.requireNonNull(policy, "policies must not contain null elements");
			if (policy.pool() != PromptTestPoolPartition.Pool.FORMAL)
			{
				issues.add(new Issue(IssueCode.WRONG_EVIDENCE_POOL, policy.familyIdentifier(),
					"Expected FORMAL, received " + policy.pool()));
			}
			if (byFamily.putIfAbsent(policy.familyIdentifier(), policy) != null)
				issues.add(new Issue(IssueCode.DUPLICATE_POLICY, policy.familyIdentifier(), "Duplicate executable policy"));
		}

		Map<String, CertificationContract.Distribution> distributions = new HashMap<>();
		for (CertificationContract.Distribution distribution : contract.content().distributions())
			distributions.put(distribution.identifier(), distribution);
		Set<String> contractFamilies = new HashSet<>();
		for (CertificationContract.LeafFamily family : contract.content().families())
		{
			contractFamilies.add(family.identifier());
			PromptTestGenerationPolicy policy = byFamily.get(family.identifier());
			if (policy == null)
			{
				issues.add(new Issue(IssueCode.MISSING_POLICY, family.identifier(), "No executable policy"));
				continue;
			}
			CertificationContract.Distribution distribution = distributions.get(family.distributionIdentifier());
			compare(IssueCode.DISTRIBUTION_MISMATCH, family.identifier(), family.distributionIdentifier(),
				policy.distributionIdentifier(), issues);
			compare(IssueCode.SAMPLER_MISMATCH, family.identifier(), distribution.samplerIdentifier(),
				policy.samplerIdentifier(), issues);
			compare(IssueCode.GENERATOR_MISMATCH, family.identifier(), family.generatorIdentifier(),
				policy.generatorIdentifier(), issues);
			compare(IssueCode.SEED_NAMESPACE_MISMATCH, family.identifier(), distribution.seedNamespace(),
				policy.seedNamespace(), issues);
			if (!distribution.superficialVariationDimensions().equals(policy.dimensionNames()))
			{
				issues.add(new Issue(IssueCode.DIMENSION_MISMATCH, family.identifier(), "Expected " +
					distribution.superficialVariationDimensions() + ", received " + policy.dimensionNames()));
			}
			compare(IssueCode.SUPPORT_RECEIPT_MISMATCH, family.identifier(), distribution.supportValidationReceipt(),
				policy.supportValidationReceipt(), issues);
			PromptTestGenerationPolicy.Validation validation = policy.validateSupport();
			if (validation.status() == PromptTestGenerationPolicy.ValidationStatus.BLOCKED)
			{
				issues.add(new Issue(IssueCode.UNSUPPORTED_POLICY, family.identifier(),
					validation.reason().orElseThrow().name()));
			}
		}
		for (String familyIdentifier : byFamily.keySet())
		{
			if (!contractFamilies.contains(familyIdentifier))
				issues.add(new Issue(IssueCode.EXTRA_POLICY, familyIdentifier, "Family is absent from the contract"));
		}
		issues.sort(Comparator.comparing(Issue::subject).thenComparing(Issue::code).thenComparing(Issue::detail));
		if (!issues.isEmpty())
			return new Result(Status.BLOCKED, Optional.empty(), issues);
		return new Result(Status.READY, Optional.of(new PromptTestContractCatalog(byFamily)), List.of());
	}

	/**
	 * Returns the executable policy for one frozen leaf family.
	 *
	 * @param familyIdentifier the frozen family identifier
	 * @return the exact bound policy
	 */
	public PromptTestGenerationPolicy policy(String familyIdentifier)
	{
		String identifier = requireText(familyIdentifier, "familyIdentifier");
		PromptTestGenerationPolicy result = policiesByFamily.get(identifier);
		if (result == null)
			throw new IllegalArgumentException("Unknown frozen prompt_test family: " + identifier);
		return result;
	}

	/**
	 * Records an exact mismatch when two executable identities differ.
	 *
	 * @param code the mismatch category
	 * @param subject the family identifier
	 * @param expected the frozen value
	 * @param actual the executable value
	 * @param issues the issue accumulator
	 */
	private static void compare(IssueCode code, String subject, String expected, String actual, List<Issue> issues)
	{
		if (!expected.equals(actual))
			issues.add(new Issue(code, subject, "Expected " + expected + ", received " + actual));
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
	 * Names whether every frozen family has an exact executable policy.
	 */
	public enum Status
	{
		READY,
		BLOCKED
	}

	/**
	 * Names a deterministic executable-policy mismatch.
	 */
	public enum IssueCode
	{
		WRONG_EVIDENCE_POOL,
		MISSING_POLICY,
		EXTRA_POLICY,
		DUPLICATE_POLICY,
		DISTRIBUTION_MISMATCH,
		SAMPLER_MISMATCH,
		GENERATOR_MISMATCH,
		SEED_NAMESPACE_MISMATCH,
		DIMENSION_MISMATCH,
		SUPPORT_RECEIPT_MISMATCH,
		UNSUPPORTED_POLICY
	}

	/**
	 * Describes one blocking executable-policy mismatch.
	 *
	 * @param code the mismatch category
	 * @param subject the family identifier
	 * @param detail the expected and observed values
	 */
	public record Issue(IssueCode code, String subject, String detail)
	{
		/**
		 * Validates one issue.
		 *
		 * @param code the mismatch category
		 * @param subject the family identifier
		 * @param detail the expected and observed values
		 */
		public Issue
		{
			Objects.requireNonNull(code, "code must not be null");
			subject = requireText(subject, "subject");
			detail = requireText(detail, "detail");
		}
	}

	/**
	 * Holds a ready executable catalog or every blocking mismatch.
	 *
	 * @param status the binding result
	 * @param catalog the ready catalog
	 * @param issues every blocking mismatch
	 */
	public record Result(Status status, Optional<PromptTestContractCatalog> catalog, List<Issue> issues)
	{
		/**
		 * Validates and snapshots one result.
		 *
		 * @param status the binding result
		 * @param catalog the ready catalog
		 * @param issues every blocking mismatch
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(catalog, "catalog must not be null");
			Objects.requireNonNull(issues, "issues must not be null");
			issues = List.copyOf(issues);
			if ((status == Status.READY) != catalog.isPresent())
				throw new IllegalArgumentException("Only a ready result must contain a catalog");
			if ((status == Status.BLOCKED) != !issues.isEmpty())
				throw new IllegalArgumentException("Only a blocked result must contain issues");
		}
	}
}

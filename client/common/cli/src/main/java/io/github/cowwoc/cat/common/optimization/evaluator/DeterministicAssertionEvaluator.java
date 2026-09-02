package io.github.cowwoc.cat.common.optimization.evaluator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs registered deterministic assertion checks against bounded, typed retained evidence.
 */
public final class DeterministicAssertionEvaluator
{
	private final Map<String, Check> checksByIdentifier;

	/**
	 * Creates an evaluator with an immutable deterministic-check registry.
	 *
	 * @param checks the registered checks
	 */
	public DeterministicAssertionEvaluator(List<RegisteredCheck> checks)
	{
		Objects.requireNonNull(checks, "checks must not be null");
		Map<String, Check> values = new LinkedHashMap<>();
		for (RegisteredCheck registered : checks)
		{
			Objects.requireNonNull(registered, "checks must not contain null elements");
			if (values.putIfAbsent(registered.identifier(), registered.check()) != null)
				throw new IllegalArgumentException("Duplicate deterministic check: " + registered.identifier());
		}
		this.checksByIdentifier = Map.copyOf(values);
	}

	/**
	 * Evaluates one assertion using one registered deterministic check.
	 *
	 * @param checkIdentifier the registered check identifier
	 * @param assertionIdentifier the frozen assertion identifier
	 * @param evidence the bounded retained evidence fields
	 * @return the complete pass/fail result and its retained-evidence citations
	 */
	public Result evaluate(String checkIdentifier, String assertionIdentifier,
		List<EvaluatorProtocol.Evidence> evidence)
	{
		checkIdentifier = requireText(checkIdentifier, "checkIdentifier");
		assertionIdentifier = requireText(assertionIdentifier, "assertionIdentifier");
		Check check = checksByIdentifier.get(checkIdentifier);
		if (check == null)
			throw new IllegalArgumentException("Unknown deterministic check: " + checkIdentifier);
		EvidenceRequest request = new EvidenceRequest(assertionIdentifier, evidence);
		Result result = Objects.requireNonNull(check.evaluate(request), "check result must not be null");
		request.validateCitations(result.citedEvidenceIdentifiers());
		return result;
	}

	/**
	 * A named deterministic check.
	 *
	 * @param identifier the stable check identifier
	 * @param check the executable check
	 */
	public record RegisteredCheck(String identifier, Check check)
	{
		/**
		 * Validates a registry entry.
		 *
		 * @param identifier the stable check identifier
		 * @param check the executable check
		 */
		public RegisteredCheck
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(check, "check must not be null");
		}
	}

	/** Executes one deterministic assertion check. */
	@FunctionalInterface
	public interface Check
	{
		/**
		 * Evaluates the frozen assertion against only the supplied retained evidence.
		 *
		 * @param request the typed evidence request
		 * @return a pass/fail result with citations from that request
		 */
		Result evaluate(EvidenceRequest request);
	}

	/**
	 * Holds an assertion check's final binary result.
	 *
	 * @param verdict the binary result
	 * @param citedEvidenceIdentifiers the evidence fields supporting it
	 */
	public record Result(Verdict verdict, Set<String> citedEvidenceIdentifiers)
	{
		/**
		 * Validates a deterministic check result.
		 *
		 * @param verdict the binary result
		 * @param citedEvidenceIdentifiers the evidence fields supporting it
		 */
		public Result
		{
			Objects.requireNonNull(verdict, "verdict must not be null");
			Objects.requireNonNull(citedEvidenceIdentifiers, "citedEvidenceIdentifiers must not be null");
			citedEvidenceIdentifiers = Set.copyOf(new TreeSet<>(citedEvidenceIdentifiers));
			if (citedEvidenceIdentifiers.isEmpty())
				throw new IllegalArgumentException("A deterministic result must cite retained evidence");
		}
	}

	/** Names a deterministic assertion's binary result. */
	public enum Verdict
	{
		PASS,
		FAIL
	}

	/** Holds the only evidence a deterministic check may inspect. */
	public static final class EvidenceRequest
	{
		private final String assertionIdentifier;
		private final List<EvaluatorProtocol.Evidence> evidence;
		private final Set<String> evidenceIdentifiers;

		/**
		 * Validates one bounded deterministic-check input.
		 *
		 * @param assertionIdentifier the frozen assertion identifier
		 * @param evidence the retained evidence fields
		 */
		private EvidenceRequest(String assertionIdentifier, List<EvaluatorProtocol.Evidence> evidence)
		{
			this.assertionIdentifier = assertionIdentifier;
			Objects.requireNonNull(evidence, "evidence must not be null");
			List<EvaluatorProtocol.Evidence> values = new ArrayList<>(evidence);
			values.sort(Comparator.comparing(EvaluatorProtocol.Evidence::identifier));
			Set<String> identifiers = new TreeSet<>();
			long totalBytes = 0;
			for (EvaluatorProtocol.Evidence item : values)
			{
				Objects.requireNonNull(item, "evidence must not contain null elements");
				if (!identifiers.add(item.identifier()))
					throw new IllegalArgumentException("Duplicate evidence identifier: " + item.identifier());
				int bytes = item.content().length;
				if (bytes > EvaluatorProtocol.MAXIMUM_EVIDENCE_FIELD_BYTES)
					throw new IllegalArgumentException("Evidence field exceeds the byte limit: " + item.identifier());
				totalBytes += bytes;
				if (totalBytes > EvaluatorProtocol.MAXIMUM_EVIDENCE_BYTES)
					throw new IllegalArgumentException("Evidence exceeds the document byte limit");
			}
			if (values.isEmpty())
				throw new IllegalArgumentException("A deterministic check requires retained evidence");
			this.evidence = List.copyOf(values);
			this.evidenceIdentifiers = Set.copyOf(identifiers);
		}

		/**
		 * Returns the frozen assertion identifier.
		 *
		 * @return the assertion identifier
		 */
		public String assertionIdentifier()
		{
			return assertionIdentifier;
		}

		/**
		 * Returns the retained untrusted evidence fields.
		 *
		 * @return the evidence fields
		 */
		public List<EvaluatorProtocol.Evidence> evidence()
		{
			return evidence;
		}

		/**
		 * Rejects citations outside this request's retained evidence.
		 *
		 * @param citations the asserted evidence identifiers
		 */
		private void validateCitations(Set<String> citations)
		{
			if (!evidenceIdentifiers.containsAll(citations))
			{
				Set<String> undeclared = new TreeSet<>(citations);
				undeclared.removeAll(evidenceIdentifiers);
				throw new IllegalArgumentException("Undeclared evidence citation: " + undeclared.iterator().next());
			}
		}
	}

	/**
	 * Validates required identifier text.
	 *
	 * @param value the proposed value
	 * @param name the parameter name
	 * @return the validated value
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}

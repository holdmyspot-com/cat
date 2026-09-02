package io.github.cowwoc.cat.common.optimization.evaluator;

import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Freezes the selected semantic-evaluator protocol for every assertion family before formal evidence begins.
 */
public final class EvaluatorProtocolRegistry
{
	private final Map<String, Assignment> assignments;

	/**
	 * Creates a registry with one explicit calibration outcome for every assertion family.
	 *
	 * @param assignments the family assignments
	 */
	public EvaluatorProtocolRegistry(List<Assignment> assignments)
	{
		Objects.requireNonNull(assignments, "assignments must not be null");
		Map<String, Assignment> values = new LinkedHashMap<>();
		for (Assignment assignment : assignments)
		{
			Objects.requireNonNull(assignment, "assignments must not contain null elements");
			if (values.putIfAbsent(assignment.assertionFamilyIdentifier(), assignment) != null)
			{
				throw new IllegalArgumentException("Duplicate evaluator protocol assignment: " +
					assignment.assertionFamilyIdentifier());
			}
		}
		this.assignments = Map.copyOf(values);
	}

	/**
	 * Returns the frozen assignment for one assertion family.
	 *
	 * @param assertionFamilyIdentifier the family identifier
	 * @return the assignment
	 */
	public Assignment assignment(String assertionFamilyIdentifier)
	{
		Assignment assignment = assignments.get(requireText(assertionFamilyIdentifier, "assertionFamilyIdentifier"));
		if (assignment == null)
			throw new IllegalArgumentException("No evaluator protocol assignment: " + assertionFamilyIdentifier);
		return assignment;
	}

	/**
	 * Performs the formal-evidence preflight for the complete assertion-family set.
	 *
	 * @param assertionFamilyIdentifiers families that require a semantic protocol
	 * @return eligible only when every requested family has an approved protocol
	 */
	public Preflight preflight(Set<String> assertionFamilyIdentifiers)
	{
		Objects.requireNonNull(assertionFamilyIdentifiers, "assertionFamilyIdentifiers must not be null");
		List<String> unapproved = new ArrayList<>();
		for (String identifier : new TreeSet<>(assertionFamilyIdentifiers))
		{
			Assignment assignment = assignment(identifier);
			if (assignment.protocol().isEmpty())
				unapproved.add(identifier);
		}
		Status status = Status.UNCERTIFIABLE;
		if (unapproved.isEmpty())
			status = Status.ELIGIBLE;
		return new Preflight(status, unapproved);
	}

	/**
	 * Names one assertion family's calibration-bound protocol assignment.
	 *
	 * @param assertionFamilyIdentifier the assertion-family identifier
	 * @param calibration the retained calibration result, or empty when no corpus is approved
	 */
	public record Assignment(String assertionFamilyIdentifier,
		Optional<EvaluatorCalibrationDocument.Loaded> calibration)
	{
		/**
		 * Validates one calibration-bound assignment.
		 *
		 * @param assertionFamilyIdentifier the assertion-family identifier
		 * @param calibration the retained calibration result, or empty when unapproved
		 */
		public Assignment
		{
			assertionFamilyIdentifier = requireText(assertionFamilyIdentifier, "assertionFamilyIdentifier");
			calibration = Objects.requireNonNull(calibration, "calibration must not be null");
			calibration.ifPresent(Assignment::validateSemanticProfile);
		}

		/**
		 * Returns the selected protocol only when calibration approved one.
		 *
		 * @return the approved protocol, if any
		 */
		public Optional<EvaluatorProtocol> protocol()
		{
			return calibration.flatMap(EvaluatorCalibrationDocument.Loaded::selectedProtocol);
		}

		/**
		 * Ensures the selected protocol uses the only currently supported semantic evaluator profile.
		 *
		 * @param loaded the frozen calibration result
		 */
		private static void validateSemanticProfile(EvaluatorCalibrationDocument.Loaded loaded)
		{
			loaded.selectedProtocol().ifPresent(protocol ->
				SupportedModelProfiles.requireSupported(ProfileRole.SEMANTIC_EVALUATOR, protocol.modelProfile()));
		}
	}

	/**
	 * Holds the complete semantic-evaluator preflight result.
	 *
	 * @param status whether formal evidence may proceed
	 * @param unapprovedAssertionFamilyIdentifiers requested families lacking approval
	 */
	public record Preflight(Status status, List<String> unapprovedAssertionFamilyIdentifiers)
	{
		/**
		 * Validates one preflight result.
		 *
		 * @param status whether formal evidence may proceed
		 * @param unapprovedAssertionFamilyIdentifiers requested families lacking approval
		 */
		public Preflight
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(unapprovedAssertionFamilyIdentifiers,
				"unapprovedAssertionFamilyIdentifiers must not be null");
			unapprovedAssertionFamilyIdentifiers = unapprovedAssertionFamilyIdentifiers.stream().
				map(identifier -> requireText(identifier, "unapprovedAssertionFamilyIdentifiers element")).sorted().toList();
			if ((status == Status.ELIGIBLE) != unapprovedAssertionFamilyIdentifiers.isEmpty())
				throw new IllegalArgumentException("Preflight status does not match unapproved assertion families");
		}
	}

	/** Names whether semantic formal evidence may begin. */
	public enum Status
	{
		ELIGIBLE,
		UNCERTIFIABLE
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

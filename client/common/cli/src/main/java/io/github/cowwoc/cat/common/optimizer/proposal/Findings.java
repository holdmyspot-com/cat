package io.github.cowwoc.cat.common.optimizer.proposal;

import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

/**
 * Validates {@link DeriverProtocol}'s additional findings alongside the proposed contract: {@code derived_sources},
 * {@code profile_restriction} and {@code scope_conditions}, as {@code deriver-findings.schema.json} describes them.
 * <p>
 * Each violation is reported at its location in the deriver's output, in the form that the proposal schema uses.
 */
final class Findings
{
	/**
	 * The property that lists the derived prompt sources.
	 */
	static final String DERIVED_SOURCES = "derived_sources";
	/**
	 * The property that lists the user's profile restriction.
	 */
	static final String PROFILE_RESTRICTION = "profile_restriction";
	/**
	 * The property that lists the scope conditions.
	 */
	static final String SCOPE_CONDITIONS = "scope_conditions";
	/**
	 * The outcome that each scope condition reports.
	 */
	private static final List<Outcome> CONDITIONS = List.of(Outcome.UNSUPPORTED_INSTRUCTION_SOURCE,
		Outcome.UNSUPPORTED_OUTPUT, Outcome.INELIGIBLE_TRIAL_EFFECT, Outcome.UNSUPPORTED_TEST_INTERACTION);
	private static final Map<String, Outcome> CONDITIONS_BY_NAME = CONDITIONS.stream().
		collect(Collectors.toUnmodifiableMap(Findings::conditionName, outcome -> outcome));
	private static final String CONDITION_NAMES = CONDITIONS.stream().map(Findings::conditionName).
		collect(Collectors.joining(", "));
	private final List<String> problems;

	/**
	 * Creates a reader that appends violations to the supplied list.
	 *
	 * @param problems receives one message per violation
	 */
	Findings(List<String> problems)
	{
		this.problems = problems;
	}

	/**
	 * Reads the derived prompt sources.
	 *
	 * @param node the {@code derived_sources} value, or null if the output omits it
	 * @return the sources that are valid
	 */
	List<DerivedSource> derivedSources(JsonNode node)
	{
		List<DerivedSource> sources = new ArrayList<>();
		String location = "$." + DERIVED_SOURCES;
		if (!isArray(node, location))
			return sources;
		for (int i = 0; i < node.size(); ++i)
		{
			String itemLocation = location + "[" + i + "]";
			JsonNode item = node.get(i);
			if (!isObject(item, itemLocation, Set.of("path", "rule")))
				continue;
			String path = text(item, "path", itemLocation);
			String rule = text(item, "rule", itemLocation);
			if (path != null && rule != null)
				sources.add(new DerivedSource(path, rule));
		}
		return sources;
	}

	/**
	 * Reads the user's profile restriction.
	 *
	 * @param node the {@code profile_restriction} value, or null if the output omits it
	 * @return the patterns that are valid
	 */
	List<TrialProfile> profileRestriction(JsonNode node)
	{
		List<TrialProfile> patterns = new ArrayList<>();
		String location = "$." + PROFILE_RESTRICTION;
		if (!isArray(node, location))
			return patterns;
		for (int i = 0; i < node.size(); ++i)
		{
			String itemLocation = location + "[" + i + "]";
			JsonNode item = node.get(i);
			if (!isObject(item, itemLocation, Set.of("model", "effort")))
				continue;
			String model = string(item, "model", itemLocation);
			String effort = string(item, "effort", itemLocation);
			if (model == null || effort == null)
				continue;
			try
			{
				patterns.add(new TrialProfile(model, effort));
			}
			catch (IllegalArgumentException e)
			{
				problems.add(itemLocation + ": " + e.getMessage());
			}
		}
		return patterns;
	}

	/**
	 * Reads a required string property that may be empty.
	 *
	 * @param object the object
	 * @param name the property name
	 * @param location the location of the object
	 * @return the value, or null if it is invalid
	 */
	private String string(JsonNode object, String name, String location)
	{
		JsonNode value = object.get(name);
		String valueLocation = location + "." + name;
		if (value == null)
		{
			problems.add(valueLocation + ": required property is missing");
			return null;
		}
		if (!value.isString())
		{
			problems.add(valueLocation + ": expected string but found " + typeOf(value));
			return null;
		}
		return value.asString();
	}

	/**
	 * Reads the scope conditions.
	 *
	 * @param node the {@code scope_conditions} value, or null if the output omits it
	 * @return the conditions that are valid
	 */
	List<ScopeCondition> scopeConditions(JsonNode node)
	{
		List<ScopeCondition> conditions = new ArrayList<>();
		String location = "$." + SCOPE_CONDITIONS;
		if (!isArray(node, location))
			return conditions;
		for (int i = 0; i < node.size(); ++i)
		{
			String itemLocation = location + "[" + i + "]";
			JsonNode item = node.get(i);
			if (!isObject(item, itemLocation, Set.of("condition", "requirement_ids", "evidence")))
				continue;
			Outcome outcome = null;
			String condition = text(item, "condition", itemLocation);
			if (condition != null)
			{
				outcome = CONDITIONS_BY_NAME.get(condition);
				if (outcome == null)
					problems.add(itemLocation + ".condition: must be one of " + CONDITION_NAMES);
			}
			Optional<List<String>> requirementIds = requirementIds(item.get("requirement_ids"),
				itemLocation + ".requirement_ids");
			String evidence = text(item, "evidence", itemLocation);
			if (outcome != null && requirementIds.isPresent() && evidence != null)
				conditions.add(new ScopeCondition(outcome, requirementIds.get(), evidence));
		}
		return conditions;
	}

	/**
	 * Reports each requirement identifier in a scope condition that the contract does not contain.
	 *
	 * @param node the {@code scope_conditions} value, or null if the output omits it
	 * @param contract the proposed contract
	 */
	void checkRequirements(JsonNode node, Contract contract)
	{
		if (node == null || !node.isArray())
			return;
		Set<String> known = contract.requirements().stream().map(Requirement::id).collect(Collectors.toSet());
		for (int i = 0; i < node.size(); ++i)
		{
			JsonNode ids = node.get(i).path("requirement_ids");
			for (int j = 0; j < ids.size(); ++j)
			{
				JsonNode id = ids.get(j);
				if (id.isString() && !known.contains(id.asString()))
				{
					problems.add("$." + SCOPE_CONDITIONS + "[" + i + "].requirement_ids[" + j + "]: " + id.asString() +
						" is not a requirement of the proposal");
				}
			}
		}
	}

	/**
	 * Reads the requirement identifiers of a scope condition.
	 *
	 * @param node the value, or null if it is missing
	 * @param location the location of the value
	 * @return the identifiers, or empty if the value is invalid
	 */
	private Optional<List<String>> requirementIds(JsonNode node, String location)
	{
		if (!isArray(node, location))
			return Optional.empty();
		if (node.isEmpty())
		{
			problems.add(location + ": must list at least 1 item(s) but lists 0");
			return Optional.empty();
		}
		List<String> ids = new ArrayList<>();
		boolean valid = true;
		for (int i = 0; i < node.size(); ++i)
		{
			String id = nonBlank(node.get(i), location + "[" + i + "]");
			if (id == null)
				valid = false;
			else
				ids.add(id);
		}
		if (!valid)
			return Optional.empty();
		return Optional.of(ids);
	}

	/**
	 * Indicates whether the value is an array, and reports a missing value or another JSON type.
	 *
	 * @param node the value, or null if it is missing
	 * @param location the location of the value
	 * @return {@code true} if it is an array
	 */
	private boolean isArray(JsonNode node, String location)
	{
		if (node == null)
		{
			problems.add(location + ": required property is missing");
			return false;
		}
		if (!node.isArray())
		{
			problems.add(location + ": expected array but found " + typeOf(node));
			return false;
		}
		return true;
	}

	/**
	 * Indicates whether the value is an object, and reports another JSON type or unexpected properties.
	 *
	 * @param node the value
	 * @param location the location of the value
	 * @param allowed the names of the allowed properties
	 * @return {@code true} if it is an object, even if it has an unexpected property
	 */
	private boolean isObject(JsonNode node, String location, Set<String> allowed)
	{
		if (!node.isObject())
		{
			problems.add(location + ": expected object but found " + typeOf(node));
			return false;
		}
		for (String name : node.propertyNames())
		{
			if (!allowed.contains(name))
				problems.add(location + "." + name + ": unexpected property");
		}
		return true;
	}

	/**
	 * Reads a required non-blank string property.
	 *
	 * @param object the object
	 * @param name the property name
	 * @param location the location of the object
	 * @return the value, or null if it is invalid
	 */
	private String text(JsonNode object, String name, String location)
	{
		JsonNode value = object.get(name);
		String valueLocation = location + "." + name;
		if (value == null)
		{
			problems.add(valueLocation + ": required property is missing");
			return null;
		}
		return nonBlank(value, valueLocation);
	}

	/**
	 * Reads a non-blank string.
	 *
	 * @param value the value
	 * @param location the location of the value
	 * @return the string, or null if it is invalid
	 */
	private String nonBlank(JsonNode value, String location)
	{
		if (!value.isString())
		{
			problems.add(location + ": expected string but found " + typeOf(value));
			return null;
		}
		if (value.asString().isBlank())
		{
			problems.add(location + ": must not be blank");
			return null;
		}
		return value.asString();
	}

	/**
	 * Returns the JSON condition name for an unavailability outcome.
	 *
	 * @param outcome the outcome
	 * @return the lowercase name used by the deriver findings schema
	 */
	private static String conditionName(Outcome outcome)
	{
		return outcome.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Names the JSON type of a value.
	 *
	 * @param value the value
	 * @return the type name, such as {@code object}
	 */
	private static String typeOf(JsonNode value)
	{
		return value.getNodeType().name().toLowerCase(Locale.ROOT);
	}
}

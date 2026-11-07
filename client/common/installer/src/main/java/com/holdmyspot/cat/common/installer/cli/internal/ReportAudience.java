package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decides who reads the installer's result, and what the installer prints for that reader.
 */
public final class ReportAudience
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private ReportAudience()
	{
	}

	/**
	 * Returns whether a person, not a program, reads the standard output: it shows on a terminal, the command does not
	 * ask for {@code --json}, and no installed harness's agent session runs the installer. An agent parses the result
	 * even where its harness gives the command a terminal.
	 *
	 * @param json                  {@code true} if the command line asks for {@code --json}
	 * @param stdoutIsTerminal      tells whether the standard output shows on a terminal; it is asked only when the
	 *                              command does not ask for {@code --json}
	 * @param environment           the installer's environment
	 * @param agentSessionVariables the variable that each installed harness sets in the environment of its agent
	 *                              sessions
	 * @return {@code true} if the result is described to a person
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean isPerson(boolean json, BooleanSupplier stdoutIsTerminal, Map<String, String> environment,
		Collection<String> agentSessionVariables)
	{
		requireThat(stdoutIsTerminal, "stdoutIsTerminal").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(agentSessionVariables, "agentSessionVariables").isNotNull();
		return !json && stdoutIsTerminal.getAsBoolean() &&
			agentSessionVariables.stream().allMatch(variable -> environment.getOrDefault(variable, "").isEmpty());
	}

	/**
	 * Returns the text that the installer prints for a result.
	 *
	 * @param result  the result object
	 * @param person  {@code true} if a person reads the text
	 * @param product the name of the result's harness, or empty when the installer does not know the harness
	 * @param entries the permission and sandbox entries that the report for a person lists, or null when it lists
	 *                none
	 * @return the report for a person, otherwise the result as one line of JSON, without a final line separator
	 * @throws NullPointerException if {@code result} or {@code product} are null
	 */
	public static String text(ObjectNode result, boolean person, Optional<String> product, ReportedEntries entries)
	{
		requireThat(result, "result").isNotNull();
		requireThat(product, "product").isNotNull();
		if (person)
			return HumanReport.render(result, product, entries);
		return MAPPER.writeValueAsString(result);
	}
}

package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decides what the installer prints for its result. The installer always describes the result to a person, unless the
 * command line asks for {@code --json}, whatever the standard output is, and whoever runs the command.
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

package io.github.cowwoc.cat.common.optimizer.adapter.fakes;

import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fixture artifact rules used by {@link RunnerSessions} tests.
 */
public final class RoleRuleFixtures
{
	/**
	 * The fixture rules supplied to authors, in delivery order.
	 */
	public static final List<String> AUTHOR_RULES = List.of("write-clearly", "failure-prevention", "contract-design",
		"rule-organization", "prompt-file-dependencies", "prompt-file-references");

	/**
	 * Prevents instantiation.
	 */
	private RoleRuleFixtures()
	{
	}

	/**
	 * Writes rules with frontmatter, direct includes and installed-artifact references.
	 *
	 * @param artifact the test-owned artifact directory
	 * @return the artifact directory
	 * @throws IOException if a fixture cannot be written
	 */
	public static Path write(Path artifact) throws IOException
	{
		Path rules = Files.createDirectories(artifact.resolve("rules/include/common"));
		Files.writeString(rules.resolve("companion.md"), "# Companion\n\nApply the companion.\n");
		Files.writeString(rules.resolve("body.md"), "Included guidance: lazy load `companion.md`.\n");
		for (String rule : AUTHOR_RULES)
		{
			Files.writeString(rules.resolve(rule + ".md"), """
				---
				depends-on: []
				---
				# %s

				## Guidance

				Complete guidance for %s, with literal {{rules}} in its example.
				The artifact is <!-- cat:plugin-root --> and ${CAT_PLUGIN_ROOT}.
				<!-- cat:include body.md -->
				""".formatted(rule, rule));
		}
		return artifact;
	}
}

package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.RelativeRuleReference;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that this repository's project rules name every rule they reference in a form that does not depend on the
 * reader's location, through the same matching that warns users at session start.
 */
public final class ProjectRuleReferencesTest
{
	/**
	 * Creates a test instance.
	 */
	public ProjectRuleReferencesTest()
	{
	}

	/**
	 * Verifies that no project rule body names an existing Markdown file through a path relative to the rule's own
	 * directory or to the project's rule directory. An agent can resolve such a path against the wrong directory, so
	 * each project rule names another file through {@code ${CAT_PROJECT_ROOT}} or {@code ${CAT_PLUGIN_ROOT}}.
	 * Frontmatter is exempt, because the loader resolves its {@code depends-on} entries itself.
	 *
	 * @throws IOException if the rule directory cannot be examined
	 */
	@Test
	public void projectRulesNameReferencedFilesFromRoot() throws IOException
	{
		Path project = clientDirectory().getParent();
		requireThat(project.resolve(".cat/rules"), "rules").isDirectory();

		requireThat(messages(project), "relativeReferences").isEmpty();
	}

	/**
	 * Verifies that the check above reports a project whose rule names another rule relative to its own directory.
	 *
	 * @throws IOException if the rules cannot be written
	 */
	@Test
	public void reportsRelativeReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			Path rule = project.resolve(".cat/rules/common/team.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "Load `java.md`.\n");
			Files.writeString(rule.resolveSibling("java.md"), "Java guidance");

			requireThat(messages(project), "relativeReferences").isNotEmpty();
		}
	}

	/**
	 * Returns one message for each relative reference in a project's rules.
	 *
	 * @param project the project directory
	 * @return the messages
	 */
	private static List<String> messages(Path project)
	{
		return RelativeRuleReference.find(null, project).stream().map(RelativeRuleReference::message).toList();
	}

	/**
	 * Returns the {@code client} directory that the build passes in.
	 *
	 * @return the directory
	 */
	private static Path clientDirectory()
	{
		String value = System.getProperty("cat.client.directory");
		requireThat(value, "cat.client.directory").isNotNull();
		return Path.of(value).toAbsolutePath().normalize();
	}
}

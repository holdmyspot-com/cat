package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RelativeRuleReference;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link RelativeRuleReference}.
 */
public final class RelativeRuleReferenceTest
{
	/**
	 * Creates a test instance.
	 */
	public RelativeRuleReferenceTest()
	{
	}

	/**
	 * Verifies that a project rule's reference through a parent directory and a user rule's bare file name each yield the
	 * rule file, line and the replacement from the stated root, while the rules still load.
	 *
	 * @throws IOException if the rules cannot be written or read
	 */
	@Test
	public void findsReferencesRelativeToRuleDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-")).toAbsolutePath().normalize();
			Path userRules = cleanup.add(Files.createTempDirectory("cat-user-rules-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/common/team.md", "# Team\n\nBefore building, load `../include/build.md`.\n");
			write(project, ".cat/rules/include/build.md", "Build guidance");
			write(userRules, "include/personal.md", "---\npaths:\n  - \"**/*.java\"\n---\n# Personal\n\n" +
				"Also load `style.md`.\n");
			write(userRules, "include/style.md", "Style guidance");

			List<RelativeRuleReference> references = RelativeRuleReference.find(userRules, project);
			String rules = new ProjectRuleLoader(scope.yaml()).loadForMainAgent(plugin, userRules, project, "harnessa");

			requireThat(references, "references").isEqualTo(List.of(
				new RelativeRuleReference(userRules.resolve("include/personal.md"), 7, "style.md",
					"${CAT_USER_RULES}/include/style.md"),
				new RelativeRuleReference(project.resolve(".cat/rules/common/team.md"), 3, "../include/build.md",
					"${CAT_PROJECT_ROOT}/.cat/rules/include/build.md")));
			requireThat(rules, "rules").contains("Before building, load");
			requireThat(RelativeRuleReference.warning(references), "warning").
				contains(project.resolve(".cat/rules/common/team.md") + ":3: `../include/build.md`").
				contains("`${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`");
		}
	}

	/**
	 * Verifies that a reference to the rule directory's own file through its root, an example path that names no file,
	 * and a frontmatter dependency yield no reference.
	 *
	 * @throws IOException if the rules cannot be written
	 */
	@Test
	public void ignoresRootedExampleAndDependencyPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/include/build.md", "Build guidance");
			write(project, ".cat/rules/include/team.md", "---\ndepends-on:\n  - `build.md`\n---\n# Team\n\n" +
				"Load `${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`. For example, name a rule `docs/example.md`, " +
				"or `<name>.md`.\n");

			requireThat(RelativeRuleReference.find(null, project), "references").isEmpty();
		}
	}

	/**
	 * Verifies that a warning about many references names a bounded number of them and counts the rest.
	 */
	@Test
	public void boundsWarningLength()
	{
		Path rule = Path.of("/project/.cat/rules/common/team.md").toAbsolutePath();
		List<RelativeRuleReference> references = new ArrayList<>();
		for (int line = 1; line <= 25; ++line)
		{
			references.add(new RelativeRuleReference(rule, line, "x.md",
				"${CAT_PROJECT_ROOT}/.cat/rules/common/x.md"));
		}

		String warning = RelativeRuleReference.warning(references);

		requireThat(warning, "warning").contains(":20: `x.md`").doesNotContain(":21: `x.md`").
			contains("and 5 more");
	}

	/**
	 * Writes a UTF-8 rule below a root directory.
	 *
	 * @param root         the rule root
	 * @param relativePath the relative rule path
	 * @param content      the rule content
	 * @throws IOException if the rule cannot be written
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}

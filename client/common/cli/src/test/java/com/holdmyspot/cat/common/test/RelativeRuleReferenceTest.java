package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RelativeRuleReference;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
			requireThat(RelativeRuleReference.warning(references, project.resolve("list.md")), "warning").
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
	 * Verifies that the warning states how many references there are and why they matter, gives a request that the user
	 * can paste to their agent naming the file that lists them, tells the user how to fix them, and says that it repeats.
	 */
	@Test
	public void warningTellsUserHowToFix()
	{
		Path rule = Path.of("/project/.cat/rules/common/team.md").toAbsolutePath();
		Path listFile = Path.of("/project/.cat/sessions/s/relative-rule-references.md").toAbsolutePath();
		List<RelativeRuleReference> references = List.of(new RelativeRuleReference(rule, 3, "x.md",
			"${CAT_PROJECT_ROOT}/.cat/rules/common/x.md"), new RelativeRuleReference(rule, 4, "y.md",
			"${CAT_PROJECT_ROOT}/.cat/rules/common/y.md"));

		String warning = RelativeRuleReference.warning(references, listFile);

		requireThat(warning, "warning").startsWith("CAT: 2 rule references name a file relative to the rule's own " +
				"directory, so an agent can load the wrong file.").
			contains("ask it: \"Replace each rule reference listed in `" + listFile + "` with the form shown for it.\"").
			contains("To fix them yourself, replace each listed reference with the form shown for it").
			contains("`depends-on` entries are not affected").
			contains("repeats at each session start until none remain").
			contains("- " + rule + ":4: `y.md` is relative to the rule's directory; write " +
				"`${CAT_PROJECT_ROOT}/.cat/rules/common/y.md`").
			doesNotContain("more, listed in");
	}

	/**
	 * Verifies that with more references than the warning names, the list file holds every one of them and the warning
	 * counts the rest and names that file.
	 *
	 * @throws IOException if the rules cannot be written or the list file cannot be read
	 */
	@Test
	public void listsEveryReferenceBeyondWarning() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/include/x.md", "X guidance");
			StringBuilder team = new StringBuilder("# Team\n");
			for (int reference = 1; reference <= 25; ++reference)
				team.append("Load `x.md`.\n");
			write(project, ".cat/rules/include/team.md", team.toString());
			Path listFile = project.resolve("relative-rule-references.md");
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			String warning = RelativeRuleReference.sessionWarning(null, project, listFile,
				new PrintStream(error, true, UTF_8));

			Path rule = project.resolve(".cat/rules/include/team.md");
			List<String> expected = new ArrayList<>();
			for (int line = 2; line <= 26; ++line)
			{
				expected.add(rule + ":" + line + ": `x.md` is relative to the rule's directory; write " +
					"`${CAT_PROJECT_ROOT}/.cat/rules/include/x.md`");
			}
			requireThat(Files.readAllLines(listFile, UTF_8), "list").isEqualTo(expected);
			requireThat(warning, "warning").startsWith("CAT: 25 rule references").
				contains(rule + ":21: `x.md`").doesNotContain(rule + ":22: `x.md`").
				endsWith("\n- and 5 more, listed in `" + listFile + "`");
			requireThat(error.toString(UTF_8), "error").isEmpty();
		}
	}

	/**
	 * Verifies that a later session start without references removes the list file of an earlier start and shows no
	 * warning.
	 *
	 * @throws IOException if the rules cannot be written or removed
	 */
	@Test
	public void removesListWhenReferencesAreFixed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/include/x.md", "X guidance");
			write(project, ".cat/rules/include/team.md", "# Team\n\nLoad `x.md`.\n");
			Path listFile = project.resolve("relative-rule-references.md");
			PrintStream error = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);
			RelativeRuleReference.sessionWarning(null, project, listFile, error);
			requireThat(listFile, "listFile").isRegularFile();
			write(project, ".cat/rules/include/team.md", "# Team\n\nLoad `${CAT_PROJECT_ROOT}/.cat/rules/include/x.md`.\n");

			String warning = RelativeRuleReference.sessionWarning(null, project, listFile, error);

			requireThat(warning, "warning").isEmpty();
			requireThat(Files.exists(listFile), "listFileExists").isFalse();
		}
	}

	/**
	 * Verifies that a list file that cannot be written is reported and still yields the warning.
	 *
	 * @throws IOException if the rules cannot be written
	 */
	@Test
	public void warnsWhenListCannotBeWritten() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			write(project, ".cat/rules/include/x.md", "X guidance");
			write(project, ".cat/rules/include/team.md", "# Team\n\nLoad `x.md`.\n");
			// A directory where the list file belongs makes writing the file fail
			Path listFile = Files.createDirectories(project.resolve("relative-rule-references.md/blocked")).getParent();
			ByteArrayOutputStream error = new ByteArrayOutputStream();

			String warning = RelativeRuleReference.sessionWarning(null, project, listFile,
				new PrintStream(error, true, UTF_8));

			requireThat(warning, "warning").contains(project.resolve(".cat/rules/include/team.md") + ":3: `x.md`");
			requireThat(error.toString(UTF_8), "error").contains("CAT could not update the list of relative rule " +
				"references in " + listFile);
		}
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

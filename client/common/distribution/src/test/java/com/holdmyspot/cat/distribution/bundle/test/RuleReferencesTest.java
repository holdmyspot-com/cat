package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.distribution.bundle.RuleArtifactRouter;
import com.holdmyspot.cat.distribution.bundle.RuleReferences;
import org.testng.annotations.Test;

/**
 * Tests how packaging writes the references that packaged Markdown makes to packaged rules, and how the build rejects
 * a reference that an agent would have to resolve against a file's location.
 */
public final class RuleReferencesTest
{
	/** Creates a test instance. */
	public RuleReferencesTest()
	{
	}

	/**
	 * Verifies a lazy-loaded rule whose source names another rule relative to its own directory ships naming that rule
	 * through the plugin directory, so an agent cannot resolve it against the wrong directory.
	 */
	@Test
	public void lazyRuleNamesRuleThroughPluginRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			write(artifact, "rules/common/goals.md", "Goals\n");
			write(artifact, "rules/include/common/planning.md", "Lazy load `../../common/goals.md` first.\n");

			RuleArtifactRouter.route(artifact, scope.yaml());

			requireThat(Files.readString(artifact.resolve("rules/include/common/planning.md")), "planning").
				isEqualTo("Lazy load `${CAT_PLUGIN_ROOT}/rules/common/goals.md` first.\n");
		}
	}

	/**
	 * Verifies a path-scoped rule, which packaging moves below {@code rules/include}, ships naming the rules that its
	 * source named relative to its source directory through the plugin directory.
	 */
	@Test
	public void movedRuleNamesRuleThroughPluginRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			write(artifact, "rules/common/java.md", "---\npaths: [\"*.java\"]\n---\n" +
				"When writing Javadoc, lazy load `../include/java/javadoc.md`.\n");
			write(artifact, "rules/include/java/javadoc.md", "Javadoc\n");

			RuleArtifactRouter.route(artifact, scope.yaml());

			requireThat(Files.readString(artifact.resolve("rules/include/common/java.md")), "java").
				isEqualTo("When writing Javadoc, lazy load `${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`.\n");
		}
	}

	/**
	 * Verifies a packaged skill that names a rule relative to the skill's directory ships naming it through the plugin
	 * directory, while its reference to a file of its own directory stays as written.
	 */
	@Test
	public void skillNamesRuleThroughPluginRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			write(artifact, "rules/include/common/tdd.md", "TDD\n");
			write(artifact, "skills/review/java.md", "Java review\n");
			write(artifact, "skills/review/first-use.md", "Read `../../rules/include/common/tdd.md`, then `java.md`.\n");

			RuleArtifactRouter.route(artifact, scope.yaml());

			requireThat(Files.readString(artifact.resolve("skills/review/first-use.md")), "firstUse").
				isEqualTo("Read `${CAT_PLUGIN_ROOT}/rules/include/common/tdd.md`, then `java.md`.\n");
		}
	}

	/**
	 * Verifies an example path in a rule's prose that names no packaged rule ships as written.
	 */
	@Test
	public void examplePathStaysAsWritten() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			String body = "A rule can depend on `../common/terms.md`, and a project on `docs/README.md`.\n";
			write(artifact, "rules/include/common/dependencies.md", body);

			RuleArtifactRouter.route(artifact, scope.yaml());

			requireThat(Files.readString(artifact.resolve("rules/include/common/dependencies.md")), "dependencies").
				isEqualTo(body);
		}
	}

	/**
	 * Verifies the build rejects a packaged artifact whose Markdown still names a packaged rule relative to its own
	 * directory, naming the file, line and reference.
	 *
	 * @throws IOException for the expected rejection
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"(?s).*rules/include/common/planning\\.md:2: `\\.\\./\\.\\./common/goals\\.md`.*")
	public void rejectsRelativeRuleReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			write(artifact, "rules/common/goals.md", "Goals\n");
			write(artifact, "rules/include/common/planning.md", "# Planning\nLazy load `../../common/goals.md`.\n");

			RuleReferences.requireResolvable(artifact);
		}
	}

	/**
	 * Verifies the build accepts a packaged artifact whose Markdown names each packaged rule through the plugin
	 * directory.
	 */
	@Test
	public void acceptsPluginRootRuleReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-rule-references-"));
			write(artifact, "rules/common/goals.md", "Goals\n");
			write(artifact, "rules/include/common/planning.md",
				"Lazy load `${CAT_PLUGIN_ROOT}/rules/common/goals.md`.\n");

			RuleReferences.requireResolvable(artifact);
		}
	}

	/**
	 * Writes a fixture file, creating its parent directories.
	 *
	 * @param root    the directory that the path is relative to
	 * @param path    the file's slash-separated path below {@code root}
	 * @param content the file's content
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path root, String path, String content) throws IOException
	{
		Path file = root.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}

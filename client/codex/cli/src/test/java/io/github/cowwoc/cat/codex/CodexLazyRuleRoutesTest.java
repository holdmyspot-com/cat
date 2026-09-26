package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Checks that Codex-facing packaged lazy-rule routes use the account-gated command.
 */
public final class CodexLazyRuleRoutesTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexLazyRuleRoutesTest()
	{
	}

	/**
	 * Rewrites generated indexes, expanded direct paths, and displayed shell reads without changing project paths.
	 *
	 * @throws IOException if test-owned rules cannot be created
	 */
	@Test
	public void mediatesPackagedRoutes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-mediated-routes-"));
			Path include = plugin.resolve("rules/include/common/write-clearly.md");
			Files.createDirectories(include.getParent());
			Files.writeString(include, "Writing guidance");
			String direct = "Read `" + include + "` or `rules/include/common/write-clearly.md`. " +
				"Run cat \"$CAT_PLUGIN_ROOT/rules/include/common/write-clearly.md\". " +
				"Project rule `.cat/rules/common/project.md` remains direct.";

			String result = CodexLazyRuleRoutes.hookContext(direct, plugin);

			requireThat(result, "result").contains(
				"`\"$CAT_PLUGIN_ROOT/client/bin/rule-load\" include/common/write-clearly.md`").
				contains("Run \"$CAT_PLUGIN_ROOT/client/bin/rule-load\" include/common/write-clearly.md").
				contains("`.cat/rules/common/project.md`").
				doesNotContain("`" + include + "`");
		}
	}

	/**
	 * Resolves a packaged lazy rule's own relative dependency before returning it to the agent.
	 *
	 * @throws IOException if test-owned rules cannot be created
	 */
	@Test
	public void mediatesLazyDependencies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-mediated-lazy-"));
			Path source = plugin.resolve("rules/include/common/start.md");
			Path dependency = plugin.resolve("rules/include/common/check-assumptions.md");
			Files.createDirectories(source.getParent());
			Files.writeString(source, "Load `./check-assumptions.md` first");
			Files.writeString(dependency, "Check assumptions");

			String result = CodexLazyRuleRoutes.lazyBody(Files.readString(source), plugin, source);

			requireThat(result, "result").contains(
				"`\"$CAT_PLUGIN_ROOT/client/bin/rule-load\" include/common/check-assumptions.md`");
		}
	}
}

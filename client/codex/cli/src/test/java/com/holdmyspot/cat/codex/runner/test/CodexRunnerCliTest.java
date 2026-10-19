package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRunnerCli;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexRunnerCli}.
 */
public final class CodexRunnerCliTest
{
	/**
	 * A Codex thread ID in canonical form.
	 */
	private static final String THREAD_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";

	/**
	 * Verifies that in a Codex thread a runner command keeps its runs in the temporary directory of the thread's CAT
	 * session directory in the nearest CAT project.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void defaultsRunRootToSessionTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-runner-cli-"));
			Files.createDirectories(project.resolve(".cat"));
			Path folder = Files.createDirectories(project.resolve("src"));

			try (ToolScope scope = CodexRunnerCli.toolScope(folder, Map.of("CODEX_THREAD_ID", THREAD_ID,
				"CODEX_HOME", project.resolve("codex-home").toString())))
			{
				HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact",
					project.resolve("artifact").toAbsolutePath().toString()}, "codex", HarnessTemporaryRoot.from(scope));

				Path sessionTemp = project.toAbsolutePath().normalize().resolve(".cat/sessions").resolve(THREAD_ID).
					resolve("temp");
				requireThat(options.runRoot().getParent(), "runRootParent").isEqualTo(sessionTemp);
				requireThat(sessionTemp.resolveSibling("session.json"), "marker").isRegularFile();
			}
		}
	}

	/**
	 * Verifies that without {@code CODEX_THREAD_ID} a runner command must name {@code --run-root}, and that the refusal
	 * names the variable.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void requiresRunRootWithoutThreadId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-runner-cli-"));
			Files.createDirectories(project.resolve(".cat"));
			String[] args = {"--artifact", project.resolve("artifact").toAbsolutePath().toString()};
			try (ToolScope scope = CodexRunnerCli.toolScope(project, Map.of()))
			{
				HarnessRunnerOptions.parse(args, "codex", HarnessTemporaryRoot.from(scope));
				throw new AssertionError("A command without a thread or --run-root chose a run root");
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").contains("--run-root").contains("CODEX_THREAD_ID");
			}
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies the bridge command reads the session's tool-output token limit, and that a command without one keeps
	 * Codex's default.
	 */
	@Test
	public void readsTheToolOutputTokenLimit()
	{
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact",
			"--tool-output-token-limit", "5000", "--keep-run"}), "limited").isEqualTo(5000);
		requireThat(CodexRunnerCli.toolOutputTokenLimit(new String[]{"serve", "--artifact", "/artifact"}),
			"unlimited").isEqualTo(0);
	}

	/**
	 * Returns tool-output token limits that are missing or not a positive number.
	 *
	 * @return the bridge commands
	 */
	@DataProvider
	public Object[][] invalidLimits()
	{
		return new Object[][]{{new String[]{"serve", "--tool-output-token-limit"}},
			{new String[]{"serve", "--tool-output-token-limit", "--keep-run"}},
			{new String[]{"serve", "--tool-output-token-limit", "0"}},
			{new String[]{"serve", "--tool-output-token-limit", "many"}}};
	}

	/**
	 * Verifies a tool-output token limit that is missing or not a positive number is refused.
	 *
	 * @param args the bridge command
	 */
	@Test(dataProvider = "invalidLimits", expectedExceptions = IllegalArgumentException.class)
	public void refusesAnInvalidToolOutputTokenLimit(String[] args)
	{
		CodexRunnerCli.toolOutputTokenLimit(args);
	}
}

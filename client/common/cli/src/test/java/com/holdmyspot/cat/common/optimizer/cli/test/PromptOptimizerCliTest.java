package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;
import com.holdmyspot.cat.common.optimizer.contract.PromptConfiguration;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Tests shared optimizer command scope ownership and failure output.
 */
public final class PromptOptimizerCliTest
{
	/**
	 * Creates the test.
	 */
	public PromptOptimizerCliTest()
	{
	}

	/**
	 * Verifies a command closes its supplied scope and retains the invalid-input exit code.
	 */
	@Test
	public void closesScopeAfterInvalidCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, project);
				PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"frobnicate"}, () -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(2);
				requireThat(scope.isClosed(), "closed").isTrue();
			}
		}
	}

	/**
	 * Verifies that a command whose project is the user's home folder is refused, with a message that says the home
	 * folder cannot be a CAT project, before it creates a CAT session directory there.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesHomeFolderProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(home.resolve(".git"));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, home);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				scope.useHome(home);

				int exit = PromptOptimizerCli.run(new String[]{"status", "AGENTS.md"}, () -> scope, ZoneOffset.UTC,
					out);

				requireThat(exit, "exit").isEqualTo(1);
				JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
				requireThat(result.get("status").asString(), "status").isEqualTo("refused");
				requireThat(result.get("message").asString(), "message").contains("cannot be a CAT project").
					contains("Open a folder below the home folder");
			}
			requireThat(Files.exists(home.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies scope-creation failures produce the same failure JSON and exit code as command failures.
	 */
	@Test
	public void rendersScopeCreationFailure()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"status"}, () ->
			{
				throw new IOException("configuration unavailable");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("error");
			requireThat(result.get("message").asString(), "message").isEqualTo("configuration unavailable");
		}
	}

	/**
	 * Verifies that a contract tool runs without opening a command scope, so that it needs nothing of the harness
	 * inside a contract author session's sandbox.
	 */
	@Test
	public void contractToolOpensNoScope()
	{
		// Requirement: R-C27
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int exit = PromptOptimizerCli.run(new String[]{"contract", "check", "missing-cat-contract.json"}, () ->
			{
				throw new AssertionError("A contract tool opened a command scope");
			}, ZoneOffset.UTC, out);

			requireThat(exit, "exit").isEqualTo(1);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "output").contains("missing-cat-contract.json");
		}
	}

	/**
	 * Verifies that {@code start} refuses a prompt test configuration that has no key for the harness, through the
	 * command entry point, without changing the project: no CAT session directory and no {@code .gitignore}, which
	 * creating the session directory would add, and the refusal does not mention a {@code .gitignore}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusedStartLeavesProjectUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("prompt-optimizer-cli-"));
			Files.createDirectory(project.resolve(".git"));
			Files.writeString(project.resolve("AGENTS.md"), "Be brief.\n");
			Path configuration = project.resolve(PromptConfiguration.location(Path.of("AGENTS.md")));
			Files.createDirectories(configuration.getParent());
			Files.writeString(configuration, "{\"codex\": {\"plugins\": []}}");
			Map<String, String> before = contents(project);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (TestOptimizerCommandScope<?> scope = TestOptimizerCommandScope.of(Harness.CLAUDE, project);
				PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(new String[]{"start", "AGENTS.md"}, () -> scope, ZoneOffset.UTC, out);

				requireThat(exit, "exit").isEqualTo(1);
			}
			JsonNode result = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(result.get("status").asString(), "status").isEqualTo("refused");
			requireThat(result.get("message").asString(), "message").contains("no \"claude\" key").
				doesNotContain(".gitignore");
			requireThat(contents(project), "contents").isEqualTo(before);
		}
	}

	/**
	 * Returns the content of every file below a directory.
	 *
	 * @param root the directory
	 * @return each path relative to the directory, with the file's content or a marker for a directory
	 * @throws IOException if a file cannot be read
	 */
	private static Map<String, String> contents(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path path : files.toList())
			{
				String value = "<directory>";
				if (Files.isRegularFile(path))
					value = Files.readString(path, StandardCharsets.ISO_8859_1);
				result.put(root.relativize(path).toString(), value);
			}
		}
		return result;
	}
}

package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.distribution.bundle.RuleArtifactRouter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Tests the {@code run-part} command that the bundled read-file rule gives Codex agents.
 */
public final class ReadFileRuleCommandTest
{
	/**
	 * The first fenced code block of a rule, which holds the {@code run-part} command.
	 */
	private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```\\R(.*?)```");
	/**
	 * The command that the agent runs through {@code run-part} in place of the rule's {@code <command>} placeholder.
	 */
	private static final String COMMAND = "Get-Content notes.md";

	/** Creates a test instance. */
	public ReadFileRuleCommandTest()
	{
	}

	/**
	 * Verifies that the {@code run-part} command in the read-file rule passes the agent's command to {@code run-part}
	 * when the plugin's path holds a single quote and a space, in the shell that Codex runs commands in: PowerShell on
	 * Windows and a POSIX shell elsewhere.
	 * <p>
	 * The test writes the command as the rule tells the agent to: it takes the rule's code block, writes the plugin's
	 * path wherever the block writes {@code ${CAT_PLUGIN_ROOT}}, and writes {@link #COMMAND} in place of
	 * {@code <command>}. A stand-in for the plugin's {@code run-part} prints the command that it reads from its standard
	 * input, without running it.
	 *
	 * @throws IOException          if the rules cannot be copied, routed or loaded, or the shell cannot be run
	 * @throws URISyntaxException   if the class path returns a malformed resource location
	 * @throws InterruptedException if the thread is interrupted while the shell runs
	 */
	@Test
	public void runPartCommandAcceptsQuoteInPluginPath() throws IOException, URISyntaxException, InterruptedException
	{
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-o'brien plugin-")).toAbsolutePath().normalize();
			Path project = cleanup.add(Files.createTempDirectory("cat-read-file-project-"));
			BundledRules.copy(plugin.resolve("rules"));
			RuleArtifactRouter.route(plugin, scope.yaml());
			Path bin = Files.createDirectories(plugin.resolve("bin"));
			Path errors = project.resolve("errors.txt");

			String guidance = new ProjectRuleLoader(scope.yaml(), platform).
				loadSessionForMainAgent(plugin, null, project, "codex").readingGuidance();
			Matcher block = CODE_BLOCK.matcher(guidance);
			requireThat(block.find(), "hasCodeBlock").withContext(guidance, "guidance").isTrue();
			String command = block.group(1).replace("${CAT_PLUGIN_ROOT}", plugin.toString()).
				replace("<command>", COMMAND);

			String output;
			if (platform.isWindows())
			{
				Files.writeString(bin.resolve("run-part.cmd"), "@findstr \"^\"\r\n");
				String encoded = Base64.getEncoder().encodeToString(command.getBytes(UTF_16LE));
				output = run(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
					encoded), command, errors);
			}
			else
			{
				Path runPart = Files.writeString(bin.resolve("run-part"), "#!/bin/sh\ncat\n");
				requireThat(runPart.toFile().setExecutable(true, false), "executable").isTrue();
				output = run(new ProcessBuilder("sh", "-c", command), command, errors);
			}
			requireThat(output.strip(), "output").withContext(command, "command").isEqualTo(COMMAND);
		}
	}

	/**
	 * Runs a shell to completion. The shell's standard error is kept apart from its output because PowerShell writes
	 * progress records there, such as "Preparing modules for first use", when standard error is not a console.
	 *
	 * @param builder the shell to run
	 * @param command the command that the shell runs, for the failure message
	 * @param errors  the file that receives the shell's standard error
	 * @return the shell's standard output
	 * @throws IOException          if the shell cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the shell runs
	 */
	private static String run(ProcessBuilder builder, String command, Path errors)
		throws IOException, InterruptedException
	{
		try (Process process = builder.redirectError(errors.toFile()).start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			requireThat(status, "status").withContext(command, "command").withContext(output, "output").
				withContext(Files.readString(errors, UTF_8), "errors").isEqualTo(0);
			return output;
		}
	}
}

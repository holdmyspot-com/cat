package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeRunnerCli}.
 */
public final class ClaudeRunnerCliTest
{
	/**
	 * The name of the CAT agent definition in a test artifact.
	 */
	private static final String AGENT = "probe-agent";

	/**
	 * Creates the test.
	 */
	public ClaudeRunnerCliTest()
	{
	}

	/**
	 * Verifies that a bridge given {@code --capacity-wait} gives up when no capacity frees within the wait, before it
	 * starts a session.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void capacityWaitExpires() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				ManualClock clock = new ManualClock(Instant.parse("2026-10-23T12:00:00Z"));
				AtomicInteger pauses = new AtomicInteger();
				Pause second = () ->
				{
					if (pauses.incrementAndGet() > 100)
						throw new AssertionError("The capacity wait did not end");
					clock.advance(Duration.ofSeconds(1));
				};
				HarnessCapacity capacity = HarnessCapacity.of(jvm.fileLocks(),
					root.resolve("locks"), 1, second, clock);
				String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
					root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--capacity-wait",
					"5", "--model", "sonnet", "--effort", "low"};
				try (HarnessCapacity.Permit _ = capacity.acquire())
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ -> capacity);
					throw new AssertionError("The bridge did not give up waiting for capacity");
				}
				catch (HarnessCapacityWaitExpiredException _)
				{
					requireThat(pauses.get(), "pauses").isEqualTo(5);
				}
			}
		}
	}

	/**
	 * Returns launches that lack a model, an effort or both, each with the options that the refusal must name.
	 *
	 * @return the profile options of each launch and the refusal's expected start
	 */
	@DataProvider
	public Object[][] incompleteProfiles()
	{
		return new Object[][]{{List.of("--effort", "low"), "Missing --model."},
			{List.of("--model", "sonnet"), "Missing --effort."},
			{List.of(), "Missing --model and --effort."}};
	}

	/**
	 * Verifies that a launch without {@code --model} or {@code --effort} is refused with a message that names the
	 * missing option and where to list the supported values, before it takes a capacity slot or starts Claude.
	 *
	 * @param profile  the launch's profile options
	 * @param expected the start of the refusal
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesLaunchWithoutProfile(List<String> profile, String expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				List<String> args = new ArrayList<>(List.of("--artifact", root.resolve("artifact").toString(),
					"--project", root.resolve("project").toString(), "--run-root", root.resolve("runs").toString()));
				args.addAll(profile);
				try
				{
					// Taking capacity comes before starting Claude, so a refusal that takes none starts no session
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						Map.of(), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`claude --help`");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns the profile options that a launch naming {@code --agent} adds.
	 *
	 * @return the profile options of each launch
	 */
	@DataProvider
	public Object[][] profilesBesideAgent()
	{
		return new Object[][]{{List.of("--model", "sonnet")}, {List.of("--effort", "low")},
			{List.of("--model", "sonnet", "--effort", "low")}};
	}

	/**
	 * Verifies that a launch that names {@code --agent} together with {@code --model} or {@code --effort} is refused,
	 * because the agent definition supplies the profile, before it takes a capacity slot or starts Claude.
	 *
	 * @param profile the launch's profile options
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "profilesBesideAgent")
	public void refusesProfileBesideAgent(List<String> profile) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, "model: opus\neffort: high\n");
				List<String> args = new ArrayList<>(List.of("--artifact", artifact.toString(), "--project",
					root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--agent", AGENT));
				args.addAll(profile);
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args.toArray(String[]::new), new StringReader(""), new StringWriter(),
						Map.of(), _ ->
						{
							throw new AssertionError("The launch took a capacity slot");
						});
					throw new AssertionError("The launch was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("--agent").contains("--model").contains("--effort");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns agent definitions that lack a model or an effort, each with the missing field.
	 *
	 * @return the frontmatter of each definition and the field it lacks
	 */
	@DataProvider
	public Object[][] incompleteAgents()
	{
		return new Object[][]{{"effort: high\n", "model"}, {"model: opus\n", "effort"}};
	}

	/**
	 * Verifies that an {@code --agent} launch whose agent definition does not name both a model and an effort is
	 * refused, naming the missing field, before it takes a capacity slot or starts Claude.
	 *
	 * @param frontmatter the definition's frontmatter fields
	 * @param missing     the field that the definition lacks
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "incompleteAgents")
	public void refusesAgentWithoutProfile(String frontmatter, String missing) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, frontmatter);
				String[] args = {"--artifact", artifact.toString(), "--project", root.resolve("project").toString(),
					"--run-root", root.resolve("runs").toString(), "--agent", AGENT};
				try
				{
					ClaudeRunnerCli.serve(jvm, root, args, new StringReader(""), new StringWriter(), Map.of(), _ ->
					{
						throw new AssertionError("The launch took a capacity slot");
					});
					throw new AssertionError("The launch was not refused");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(AGENT).contains("no " + missing + " ");
				}
				requireThat(Files.exists(root.resolve("runs")), "runRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that an {@code --agent} launch without {@code --model} and {@code --effort} runs under the model and
	 * effort that the agent definition names.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void agentSuppliesProfile() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I11
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "runner-cli-"));
				Path artifact = artifactWithAgent(root, "model: opus\neffort: none\n");
				HarnessRunnerOptions options = ClaudeRunnerCli.options(new String[]{"--artifact", artifact.toString(),
					"--run-root", root.resolve("runs").toString(), "--agent", AGENT, "--install-cat"},
					HarnessTemporaryRoot.withoutSession("no session"));
				requireThat(options.model(), "model").isEqualTo("opus");
				requireThat(options.effort(), "effort").isEqualTo("none");
			}
		}
	}

	/**
	 * Creates an artifact that holds one CAT Claude agent definition.
	 *
	 * @param root        the test's directory
	 * @param frontmatter the definition's frontmatter fields, one per line
	 * @return the artifact
	 * @throws IOException if the definition cannot be written
	 */
	private static Path artifactWithAgent(Path root, String frontmatter) throws IOException
	{
		Path artifact = root.resolve("artifact");
		Path agents = Files.createDirectories(artifact.resolve("agents/claude"));
		Files.writeString(agents.resolve(AGENT + ".md"), "---\nname: " + AGENT + "\n" + frontmatter +
			"---\nReview the change.\n");
		return artifact;
	}
}

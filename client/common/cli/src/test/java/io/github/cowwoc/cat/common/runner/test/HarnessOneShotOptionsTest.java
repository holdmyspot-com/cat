package io.github.cowwoc.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessOneShotOptions;
import io.github.cowwoc.cat.common.runner.HarnessTemporaryRoot;
import io.github.cowwoc.cat.common.secrets.HarnessOneShotOptionsSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessOneShotOptions}.
 */
public final class HarnessOneShotOptionsTest
{
	/**
	 * The temporary root of a command that runs in a harness session whose temporary directory is {@code /caller-temp}.
	 */
	private static final HarnessTemporaryRoot SESSION = HarnessTemporaryRoot.of(Path.of("/caller-temp").
		toAbsolutePath());

	/**
	 * Creates the test fixture.
	 */
	public HarnessOneShotOptionsTest()
	{
	}

	/**
	 * Verifies CAT installation is opt-in for one-shot requests.
	 */
	@Test
	public void defaultsToNoCatInstallation()
	{
		HarnessOneShotOptions result = HarnessOneShotOptions.parse(new String[] {"run", "--artifact", "/artifact",
			"--prompt-file", "/prompt", "--output", "/output"}, "harness", SESSION);

		requireThat(result.installCat(), "installCat").isFalse();
	}

	/**
	 * Verifies one-shot requests can explicitly install CAT.
	 */
	@Test
	public void parsesCatInstallation()
	{
		HarnessOneShotOptions result = HarnessOneShotOptions.parse(new String[] {"run", "--artifact", "/artifact",
			"--prompt-file", "/prompt", "--output", "/output", "--install-cat"}, "harness",
			SESSION);

		requireThat(result.installCat(), "installCat").isTrue();
	}

	/**
	 * Verifies an oversized whole-prompt input is rejected before it is retained in memory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsOversizedPrompt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-prompt-"));
			Path prompt = directory.resolve("prompt.txt");
			Files.write(prompt, new byte[HarnessOneShotOptionsSecrets.access().maximumPromptBytes() + 1]);
			HarnessOneShotOptions options = HarnessOneShotOptions.parse(new String[] {"run", "--artifact", "/artifact",
				"--prompt-file", prompt.toString(), "--output", "/output"}, "harness",
				SESSION);

			options.prompt();
		}
	}
}

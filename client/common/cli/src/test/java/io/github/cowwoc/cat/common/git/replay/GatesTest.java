package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests ordinary Git topic-replay quality gates.
 */
public final class GatesTest
{
	/**
	 * Creates the test fixture.
	 */
	public GatesTest()
	{
	}

	/**
	 * Verifies a successful gate retains its complete command output beside its manifest.
	 *
	 * @throws IOException if the fixture or gate cannot be created
	 */
	@Test
	public void retainsSuccessfulGateOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "cat-topic-replay-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");

			new Gates(new CommandRunner(directory)).run(manifest, "fixture-build",
				GateCommandFixture.command(GateCommandFixture.Mode.SUCCESS));

			requireThat(Files.readString(directory.resolve("gates/fixture-build.log")), "gateLog").isEqualTo("passed");
		}
	}
}

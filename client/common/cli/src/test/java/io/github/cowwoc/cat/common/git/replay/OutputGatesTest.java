package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests retained-output Git topic-replay gates.
 */
public final class OutputGatesTest
{
	/**
	 * Creates the test fixture.
	 */
	public OutputGatesTest()
	{
	}

	/**
	 * Verifies an output gate rejects a no-op command before materializing a worktree.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsNoOpCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = new OutputGates(new Git(directory),
				(ignored, prefix) -> directory.resolve(prefix));

			try
			{
				gates.run(manifest, "output-build", List.of(new OutputGates.Target("output-1",
					"tree", List.of("true"))));
				throw new AssertionError("Expected no-op output gate to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("no-op command");
			}
		}
	}

	/**
	 * Verifies a draft-output gate rejects a no-op command before creating its temporary commit.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsNoOpDraftCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			OutputGates gates = new OutputGates(new Git(directory),
				(ignored, prefix) -> directory.resolve(prefix));

			try
			{
				gates.runDraft(manifest, "draft-output-build", new OutputGates.Target("output-1", "tree",
					List.of("true")));
				throw new AssertionError("Expected no-op draft-output gate to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("no-op command");
			}
		}
	}
}

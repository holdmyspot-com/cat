package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link Tombstone}.
 */
public final class TombstoneTest
{
	/**
	 * Creates the test fixture.
	 */
	public TombstoneTest()
	{
	}

	/**
	 * Verifies a referenced terminal run is replaced by a durable summary before its evidence is deleted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void replacesReferencedRun() throws IOException
	{
		try (ToolScope scope = new ToolScope();
		TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-tombstone"));
			Path run = writeRun(root.resolve("evidence/run.json"), root.resolve("candidate-state.json"));
			Path state = root.resolve("candidate-state.json");
			Files.writeString(state, """
				{
				  "formal_failures": [{"run": "%s"}]
				}
				""".formatted(run.toAbsolutePath().normalize()));

			Path tombstone = Tombstone.replace(scope.json(), run, root.resolve("evidence"));

			requireThat(tombstone, "tombstone").isRegularFile();
			requireThat(Files.readString(tombstone), "summary").contains("prompt-evaluation-tombstone").contains(
				run.toAbsolutePath().normalize().toString());
			requireThat(Files.readString(state), "candidateState").contains(tombstone.toString()).contains(
				"record_sha256");
		}
	}

	/**
	 * Verifies a terminal run without candidate-state ownership needs no tombstone.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void skipsUnreferencedRun() throws IOException
	{
		try (ToolScope scope = new ToolScope();
		TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unreferenced-evaluation-tombstone"));
			Path run = root.resolve("evidence/run.json");
			Files.createDirectories(run.getParent());
			Files.writeString(run, "{}");

			requireThat(Tombstone.replace(scope.json(), run, run.getParent()), "tombstone").isNull();
		}
	}

	/**
	 * Writes a minimal terminal record that identifies its candidate-state ledger.
	 *
	 * @param run the terminal-record location
	 * @param state the candidate-state ledger that refers to the terminal record
	 * @return the written terminal record
	 * @throws IOException if the fixture cannot be written
	 */
	private static Path writeRun(Path run, Path state) throws IOException
	{
		Files.createDirectories(run.getParent());
		Files.writeString(run, """
			{
			  "failure_signature_state": "%s",
			  "suite_sha256": "suite",
			  "prompt_sha256": "prompt"
			}
			""".formatted(state.toAbsolutePath().normalize()));
		return run.toAbsolutePath().normalize();
	}
}

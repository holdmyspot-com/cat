package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.git.replay.Finalization;
import io.github.cowwoc.cat.common.git.replay.History;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies captured replay facts to the production filesystem receipt producer.
 */
final class ReceiptFixtures
{
	/** Prevents construction of static fixture operations. */
	private ReceiptFixtures()
	{
	}

	/**
	 * Creates a finalization owner whose data-based operations need no repository subprocesses.
	 *
	 * @param directory the test-owned working directory
	 * @return the finalization owner
	 */
	static Finalization finalization(Path directory)
	{
		Git git = new Git(directory);
		return new Finalization(git, new History(directory, git), ReplayJson.newMapper());
	}

	/**
	 * Supplies two retained transitions and a discarded logical output.
	 *
	 * @return captured sealed replay facts
	 */
	static Finalization.Replay replay()
	{
		return new Finalization.Replay("base", "source-2", "tree-2", "tree-2",
			List.of(new Finalization.Output("first", true), new Finalization.Output("second", true),
				new Finalization.Output("discarded", false)),
			List.of(new Finalization.OutputWitness("first", "tree-0", "tree-1"),
				new Finalization.OutputWitness("second", "tree-1", "tree-2")));
	}

	/**
	 * Supplies replacement commits and their direct parents in chronological order.
	 *
	 * @return captured replacement history
	 */
	static List<History.RangeCommit> chain()
	{
		return List.of(new History.RangeCommit("replacement-1", "base"),
			new History.RangeCommit("replacement-2", "replacement-1"));
	}

	/**
	 * Captures a canonical receipt using actual manifest and evidence files.
	 *
	 * @param finalization the production receipt owner
	 * @param manifest the manifest bound by the receipt
	 * @return the captured canonical receipt
	 * @throws IOException if evidence cannot be captured
	 */
	static Finalization.FinalizationReceipt capture(Finalization finalization, Path manifest) throws IOException
	{
		return finalization.captureReceipt(manifest, replay(), "replacement-2",
			Finalization.finalizedOutputs(replay(), chain()), finalization.sha256(manifest), "captured-identity-digest");
	}

	/**
	 * Writes the retained gate artifacts consumed by production receipt publication.
	 *
	 * @param manifest the manifest owning the gate directory
	 * @throws IOException if the evidence cannot be written
	 */
	static void writeGates(Path manifest) throws IOException
	{
		Path gates = manifest.resolveSibling("gates");
		Files.createDirectories(gates);
		for (String output : List.of("first", "second"))
		{
			for (String gate : List.of("per-output-build", "per-output-acceptance"))
				Files.writeString(gates.resolve(gate + "-" + output + ".log"), "checked " + output + "\n");
		}
		Files.writeString(gates.resolve("replay-result.log"), "checked final replay\n");
	}
}

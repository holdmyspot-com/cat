package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor;
import io.github.cowwoc.cat.common.git.replay.Batch;
import io.github.cowwoc.cat.common.git.replay.BatchManifest;
import io.github.cowwoc.cat.common.git.replay.DestinationWitness;
import io.github.cowwoc.cat.common.git.replay.Output;
import io.github.cowwoc.cat.common.git.replay.OutputWitness;
import io.github.cowwoc.cat.common.git.replay.PatchArtifact;
import io.github.cowwoc.cat.common.git.replay.PatchArtifacts;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import io.github.cowwoc.cat.common.git.replay.SealedPrefixRecovery;
import io.github.cowwoc.cat.common.git.replay.SealedPrefixValidation;
import io.github.cowwoc.cat.common.git.replay.SourceReconstruction;
import io.github.cowwoc.cat.common.git.replay.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests sealed-prefix recovery decisions from captured tree facts and actual patch files.
 */
public final class RecoveryTest
{
	private static final String FIRST_SOURCE = "1".repeat(40);
	private static final String SECOND_SOURCE = "2".repeat(40);
	private static final String BASE_TREE = "a".repeat(40);
	private static final String FIRST_TREE = "b".repeat(40);
	private static final String SECOND_TREE = "c".repeat(40);

	/** Creates the test fixture. */
	public RecoveryTest()
	{
	}

	/**
	 * Verifies a captured sealed prefix accepts matching mechanical facts and reports its affected suffix.
	 *
	 * @throws IOException if the fixture or recovery checks fail
	 */
	@Test
	public void validatesSealedPrefixAndSuffix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "recovery-prefix-"));
			Path manifestFile = directory.resolve("manifest.json");
			Path patch = directory.resolve("first.patch");
			Files.writeString(patch, "captured patch bytes\n");
			PatchArtifacts patches = new PatchArtifacts();
			BatchManifest manifest = manifest(patches.declare(manifestFile, patch));
			ReplayJson.newMapper().writeValue(manifestFile.toFile(), manifest);

			requireThat(new SealedPrefixValidation().length(manifest), "sealedPrefixLength").isEqualTo(1);
			for (SourceSnapshot source : manifest.sources())
				SealedPrefixRecovery.verifySourceTrees(source, source.parentTree(), source.tree());
			Output first = manifest.outputs().getFirst();
			OutputWitness witness = manifest.outputWitnesses().getFirst();
			SealedPrefixRecovery.verifyOutputBefore(first, witness, BASE_TREE);
			requireThat(SealedPrefixRecovery.verifyBatchPatch(manifestFile, manifest.batches().getFirst(), patches),
				"verifiedPatch").isEqualTo(patch);
			SealedPrefixRecovery.verifyBatchAfter(manifest.batches().getFirst(), FIRST_TREE);
			SealedPrefixRecovery.verifyOutputAfter(first, witness, FIRST_TREE);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(directory, directory);
			requireThat(editor.affectedSuffix(manifestFile, "output_1"), "affectedSuffix").containsExactly(
				List.of("output_1", "output_2"), "expectedSuffix");
		}
	}

	/**
	 * Verifies a changed source parent requires a fresh replay.
	 *
	 * @throws IOException for the expected changed source endpoint
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*prepare a new replay.*")
	public void rejectsChangedSourceParent() throws IOException
	{
		SealedPrefixRecovery.verifySourceTrees(new SourceSnapshot(FIRST_SOURCE, BASE_TREE, FIRST_TREE),
			SECOND_TREE, FIRST_TREE);
	}

	/**
	 * Verifies a changed source result requires a fresh replay.
	 *
	 * @throws IOException for the expected changed source endpoint
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*prepare a new replay.*")
	public void rejectsChangedSourceResult() throws IOException
	{
		SealedPrefixRecovery.verifySourceTrees(new SourceSnapshot(FIRST_SOURCE, BASE_TREE, FIRST_TREE),
			BASE_TREE, SECOND_TREE);
	}

	/**
	 * Verifies a retained output must continue the verified prefix tree.
	 *
	 * @throws IOException for the expected disconnected output
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*expects tree.*prefix produced.*")
	public void rejectsDisconnectedOutput() throws IOException
	{
		SealedPrefixRecovery.verifyOutputBefore(new Output("output_1", FIRST_SOURCE, "feat: first"),
			new OutputWitness("output_1", BASE_TREE, FIRST_TREE), SECOND_TREE);
	}

	/**
	 * Verifies changed sealed patch bytes are rejected before applying the patch.
	 *
	 * @throws IOException for the expected changed patch digest
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*recorded digest.*")
	public void rejectsChangedSealedPatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "recovery-patch-"));
			Path manifestFile = directory.resolve("manifest.json");
			Path patch = directory.resolve("first.patch");
			Files.writeString(patch, "captured patch bytes\n");
			PatchArtifacts patches = new PatchArtifacts();
			Batch batch = manifest(patches.declare(manifestFile, patch)).batches().getFirst();
			Files.writeString(patch, "changed patch bytes\n");
			SealedPrefixRecovery.verifyBatchPatch(manifestFile, batch, patches);
		}
	}

	/**
	 * Verifies a sealed batch must recreate its own recorded result tree.
	 *
	 * @throws IOException for the expected changed batch result
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*sealed batch.*recorded tree.*")
	public void rejectsChangedBatchResult() throws IOException
	{
		Batch batch = new Batch("first", FIRST_SOURCE, "output_1", null, new DestinationWitness(BASE_TREE, FIRST_TREE));
		SealedPrefixRecovery.verifyBatchAfter(batch, SECOND_TREE);
	}

	/**
	 * Verifies the output's final tree must match its witness after all batches.
	 *
	 * @throws IOException for the expected changed output result
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*sealed output.*recorded tree.*")
	public void rejectsChangedOutputResult() throws IOException
	{
		SealedPrefixRecovery.verifyOutputAfter(new Output("output_1", FIRST_SOURCE, "feat: first"),
			new OutputWitness("output_1", BASE_TREE, FIRST_TREE), SECOND_TREE);
	}

	/**
	 * Supplies two sources and outputs with only the first output mechanically sealed.
	 *
	 * @param patch the actual first batch's immutable artifact declaration
	 * @return the captured prefix manifest
	 */
	private BatchManifest manifest(PatchArtifact patch)
	{
		return new BatchManifest(5, "base", SECOND_TREE,
			List.of(new SourceSnapshot(FIRST_SOURCE, BASE_TREE, FIRST_TREE),
				new SourceSnapshot(SECOND_SOURCE, FIRST_TREE, SECOND_TREE)),
			List.of(new Output("output_1", FIRST_SOURCE, "feat: first"),
				new Output("output_2", SECOND_SOURCE, "docs: second")), List.of(),
			List.of(new Batch("first", FIRST_SOURCE, "output_1", patch, new DestinationWitness(BASE_TREE, FIRST_TREE))),
			List.of(new SourceReconstruction(FIRST_SOURCE, List.of("first"), null, BASE_TREE, FIRST_TREE),
				new SourceReconstruction(SECOND_SOURCE, List.of(), patch, FIRST_TREE, SECOND_TREE)),
			List.of(new OutputWitness("output_1", BASE_TREE, FIRST_TREE)), List.of(), List.of(), List.of(), null);
	}
}

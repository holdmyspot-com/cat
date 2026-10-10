package io.github.cowwoc.cat.common.git.replay.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.replay.Batch;
import io.github.cowwoc.cat.common.git.replay.BatchManifest;
import io.github.cowwoc.cat.common.git.replay.Output;
import io.github.cowwoc.cat.common.git.replay.OutputPlanInputExport;
import io.github.cowwoc.cat.common.git.replay.OutputPlanRequest;
import io.github.cowwoc.cat.common.git.replay.OutputWitness;
import io.github.cowwoc.cat.common.git.replay.PatchArtifact;
import io.github.cowwoc.cat.common.git.replay.PatchArtifactTransfer;
import io.github.cowwoc.cat.common.git.replay.PatchArtifacts;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import io.github.cowwoc.cat.common.git.replay.SourceReview;
import io.github.cowwoc.cat.common.git.replay.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests reviewed output-plan input export behavior.
 */
public final class OutputPlanInputExportTest
{
	/**
	 * Creates the test fixture.
	 */
	public OutputPlanInputExportTest()
	{
	}

	/**
	 * Verifies a retained reviewed output becomes a replacement-local retained plan request.
	 *
	 * @throws IOException if the isolated manifests or export cannot be created
	 */
	@Test
	public void exportsRetainedOutputWithCopiedPatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-replay-plan-export-"));
			Path previousDirectory = Files.createDirectories(root.resolve("previous"));
			Path replacementDirectory = Files.createDirectories(root.resolve("replacement"));
			Path previousManifest = Files.writeString(previousDirectory.resolve("manifest.json"), "{}\n");
			Path replacementManifest = Files.writeString(replacementDirectory.resolve("manifest.json"), "{}\n");
			Path patchFile = Files.writeString(previousDirectory.resolve("source.patch"), "patch bytes\n");
			PatchArtifacts patches = new PatchArtifacts();
			PatchArtifact patch = patches.declare(previousManifest, patchFile);
			Batch batch = new Batch("batch", "source", "output", patch, null);
			BatchManifest previous = reviewedManifest(batch);
			BatchManifest replacement = replacementManifest();
			Path plan = replacementDirectory.resolve("plan.json");

			new OutputPlanInputExport(ReplayJson.newMapper(), new PatchArtifactTransfer(patches)).export(previousManifest,
				previous, replacementManifest, replacement, plan);

			OutputPlanRequest[] exported = ReplayJson.newMapper().readValue(plan.toFile(), OutputPlanRequest[].class);
			requireThat(exported.length, "exportedPlanCount").isEqualTo(1);
			requireThat(exported[0].disposition(), "exportedDisposition").isEqualTo("retained");
			requireThat(exported[0].batches().getFirst().patch(), "exportedPatch").matches(
				"imported-patches/[0-9a-f]{64}\\.patch");
		}
	}

	/**
	 * Returns a completed source manifest with one retained output decision.
	 *
	 * @param batch the reviewed batch retained by the output
	 * @return a completed reviewed manifest
	 */
	private BatchManifest reviewedManifest(Batch batch)
	{
		return new BatchManifest(5, "base", "tree", List.of(new SourceSnapshot("source", "parent", "tree")),
			List.of(new Output("output", null, "feat: output")), List.of(), List.of(batch), List.of(),
			List.of(new OutputWitness("output", "parent", "tree")), List.of(), List.of(new SourceReview("source", "whole",
			List.of("output"), List.of(), "counterfactual", "evidence")), List.of(), null);
	}

	/**
	 * Returns a compatible replacement with no replay decisions.
	 *
	 * @return an unreviewed replacement manifest
	 */
	private BatchManifest replacementManifest()
	{
		return new BatchManifest(5, "base", "tree", List.of(new SourceSnapshot("replacement", "parent", "tree")),
			List.of(new Output("output", null, "feat: output")), List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), null);
	}
}

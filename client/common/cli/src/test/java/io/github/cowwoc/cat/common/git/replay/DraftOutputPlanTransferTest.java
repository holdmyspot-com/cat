package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests unsealed output-plan transfer behavior.
 */
public final class DraftOutputPlanTransferTest
{
	/**
	 * Creates the test fixture.
	 */
	public DraftOutputPlanTransferTest()
	{
	}

	/**
	 * Verifies a compatible replacement receives a copied draft plan whose batch refers only to its copied patch.
	 *
	 * @throws IOException if the isolated manifests or transfer cannot be created
	 */
	@Test
	public void copiesPlanWithReplacementPatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("topic-replay-draft-transfer-"));
			Path previousDirectory = Files.createDirectories(root.resolve("previous"));
			Path replacementDirectory = Files.createDirectories(root.resolve("replacement"));
			Path previousManifest = Files.writeString(previousDirectory.resolve("manifest.json"), "{}\n");
			Path replacementManifest = Files.writeString(replacementDirectory.resolve("manifest.json"), "{}\n");
			Path patchDirectory = Files.createDirectories(previousDirectory.resolve("patches"));
			Path patch = Files.writeString(patchDirectory.resolve("source.patch"), "patch bytes\n");
			Path previousPlan = previousDirectory.resolve("draft-plan.json");
			scope.json().writeValue(previousPlan.toFile(), List.of(new OutputPlanRequest("output", "retained",
				List.of(new BatchRequest("batch", "source", "patches/source.patch")))));
			BatchManifest previous = reviewedManifest();
			BatchManifest replacement = unreviewedManifest();
			Path copiedPlan = replacementDirectory.resolve("draft-plan.json");

			new DraftOutputPlanTransfer(scope.json(), new PatchArtifacts()).transfer(previousManifest, previousPlan,
				previous, replacementManifest, replacement, copiedPlan);

			OutputPlanRequest[] copied = scope.json().readValue(copiedPlan.toFile(), OutputPlanRequest[].class);
			requireThat(copied.length, "copiedPlanCount").isEqualTo(1);
			requireThat(copied[0].batches().getFirst().patch(), "copiedPatchPath").matches("patches/[0-9a-f]{64}\\.patch");
			requireThat(replacementDirectory.resolve(copied[0].batches().getFirst().patch()), "copiedPatch").isRegularFile();
			requireThat(Files.readString(replacementDirectory.resolve(copied[0].batches().getFirst().patch())),
				"copiedPatchContents").isEqualTo(Files.readString(patch));
		}
	}

	/**
	 * Returns a manifest with the completed source-review prerequisite for draft transfer.
	 *
	 * @return a reviewed source manifest
	 */
	private BatchManifest reviewedManifest()
	{
		return new BatchManifest(5, "base", "tree", List.of(new SourceSnapshot("source", "parent", "tree")),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(new SourceReview("source", "whole",
			List.of(), List.of(), "counterfactual", "evidence")), List.of(), null);
	}

	/**
	 * Returns a compatible replacement manifest that has no replay decisions.
	 *
	 * @return an unreviewed replacement manifest
	 */
	private BatchManifest unreviewedManifest()
	{
		return new BatchManifest(5, "base", "tree", List.of(new SourceSnapshot("replacement", "parent", "tree")),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
	}
}

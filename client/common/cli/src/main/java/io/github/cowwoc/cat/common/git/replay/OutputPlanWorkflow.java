package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Manages the manifest-local output-plan lifecycle before replay applies a sealed plan to Git.
 */
final class OutputPlanWorkflow
{
	private final JsonMapper json;
	private final ManifestStore<BatchManifest> manifests;
	private final OutputPlanValidation outputPlan;
	private final SealedOutputRecording sealedOutputRecording;
	private final OutputPlanSealing outputPlanSealing;
	private final DraftOutputAcceptance draftOutputAcceptance;
	private final OutputPlanInputExport outputPlanInputExport;
	private final DraftOutputPlanTransfer draftOutputPlanTransfer;
	private final OutputSuffixInvalidation outputSuffixInvalidation;

	/**
	 * Creates the workflow that owns one replay's mutable output-plan decisions.
	 *
	 * @param json the operation-scoped mapper for reviewer plan files
	 * @param manifests the manifest persistence owner
	 * @param outputPlan the output-plan validator and draft-tree owner
	 * @param sealedOutputRecording the retained-output recorder
	 * @param outputPlanSealing the complete-plan sealing owner
	 * @param draftOutputAcceptance the draft acceptance-check owner
	 * @param outputPlanInputExport the reviewed-plan input exporter
	 * @param draftOutputPlanTransfer the unsealed-plan transfer owner
	 * @param outputSuffixInvalidation the replacement-suffix policy
	 */
	OutputPlanWorkflow(JsonMapper json, ManifestStore<BatchManifest> manifests, OutputPlanValidation outputPlan,
		SealedOutputRecording sealedOutputRecording, OutputPlanSealing outputPlanSealing,
		DraftOutputAcceptance draftOutputAcceptance, OutputPlanInputExport outputPlanInputExport,
		DraftOutputPlanTransfer draftOutputPlanTransfer, OutputSuffixInvalidation outputSuffixInvalidation)
	{
		this.json = json;
		this.manifests = manifests;
		this.outputPlan = outputPlan;
		this.sealedOutputRecording = sealedOutputRecording;
		this.outputPlanSealing = outputPlanSealing;
		this.draftOutputAcceptance = draftOutputAcceptance;
		this.outputPlanInputExport = outputPlanInputExport;
		this.draftOutputPlanTransfer = draftOutputPlanTransfer;
		this.outputSuffixInvalidation = outputSuffixInvalidation;
	}

	/**
	 * Writes the Git tree produced after one output in a draft plan without changing the manifest or branch.
	 *
	 * <p>
	 * A reviewer can use the returned tree as the earlier version of a file when creating a patch for that output. The
	 * command verifies every preceding declaration and applies its patches in an isolated index. It validates only the
	 * plan prefix needed to reach the named output; sealing the complete plan remains the operation that proves every
	 * output and the final branch tree.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param planFile the manifest-relative draft plan containing the named output and every earlier output
	 * @param outputId the output after which to write the tree
	 * @return the tree after the named output
	 * @throws IOException if the draft plan is unsafe, incomplete through the named output, or cannot be applied
	 */
	String writeDraftOutputTree(Path manifestFile, Path planFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(planFile, "planFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		if (!planFile.toAbsolutePath().normalize().startsWith(directory))
			throw new IOException("Topic-replay draft plan must be below the manifest directory: " + planFile);
		OutputPlanRequest[] requests = json.readValue(planFile.toFile(), OutputPlanRequest[].class);
		return outputPlan.draftTree(manifestFile, manifest, List.of(requests), outputId);
	}

	/**
	 * Atomically records and seals the next output from reviewer-authored batch declarations.
	 *
	 * <p>
	 * The batch file is a JSON array of objects with {@code id}, {@code source}, and manifest-relative {@code patch}
	 * properties. The reviewer chooses those declarations; this method derives every tree witness, validates every patch,
	 * and changes the manifest only after the complete output succeeds.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param outputId the next retained output in final-history order
	 * @param batchFile the JSON file containing the output's ordered reviewer-authored batch declarations
	 * @throws IOException if the output is out of order, a declaration is invalid, a patch cannot be applied, or the
	 *         manifest cannot be updated
	 */
	void recordOutput(Path manifestFile, String outputId, Path batchFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		requireThat(batchFile, "batchFile").isRegularFile();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		sealedOutputRecording.record(manifestFile, manifest, outputId, batchFile);
	}

	/**
	 * Records the next retained output directly from its sealed batch declarations.
	 *
	 * @param manifestFile the replay manifest containing a sealed output plan
	 * @param outputId the next retained output in final-history order
	 * @throws IOException if the output is not the plan's next retained decision or cannot be recorded
	 */
	void recordPlannedOutput(Path manifestFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		sealedOutputRecording.recordPlanned(manifestFile, manifest, outputId);
	}

	/**
	 * Validates and seals every retained-or-discarded output decision before replay writes an output witness.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param planFile the JSON array of ordered output dispositions and retained batch declarations
	 * @throws IOException if the plan is incomplete, contradicts a review, cannot recreate the captured final tree, or
	 *         the manifest already contains a replay decision
	 */
	void sealOutputPlan(Path manifestFile, Path planFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(planFile, "planFile").isRegularFile();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		outputPlanSealing.seal(manifestFile, manifest, planFile);
	}

	/**
	 * Runs every declared topic acceptance command against the output tree proposed by an unsealed plan.
	 *
	 * <p>
	 * This command lets reviewers prove that a planned topic is runnable before the plan becomes immutable. Each
	 * command runs in a detached temporary worktree whose files exactly match the tree produced by that output and all
	 * earlier retained outputs.
	 *
	 * @param manifestFile the replay manifest that owns the proposed plan and gate logs
	 * @param planFile the complete reviewer-authored output plan
	 * @throws IOException if the plan is incomplete, an output tree cannot be recreated, or an acceptance command fails
	 */
	void validateDraftDeclaredOutputAcceptance(Path manifestFile, Path planFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(planFile, "planFile").isRegularFile();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		draftOutputAcceptance.validate(manifestFile, manifest, planFile);
	}

	/**
	 * Transfers verified patch artifacts from an earlier same-run replay and exports decisions as editable plan input.
	 *
	 * <p>
	 * The command transfers no witnesses, batches, or placement authority. It only makes the reviewed patch bytes
	 * available beneath the replacement manifest and writes an input plan that the reviewer must complete and seal.
	 *
	 * @param previousManifestFile the earlier replay manifest that owns the reviewed patch artifacts
	 * @param manifestFile the replacement replay manifest
	 * @param planFile the new manifest-local JSON plan input file
	 * @throws IOException if either manifest is incompatible, an artifact cannot be verified, or the target is unsafe
	 */
	void exportOutputPlanInput(Path previousManifestFile, Path manifestFile, Path planFile)
		throws IOException
	{
		requireThat(previousManifestFile, "previousManifestFile").isRegularFile();
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(planFile, "planFile").isNotNull();
		BatchManifest previous = manifests.readUnfinalized(previousManifestFile);
		BatchManifest replacement = manifests.readUnfinalized(manifestFile);
		outputPlanInputExport.export(previousManifestFile, previous, manifestFile, replacement, planFile);
	}

	/**
	 * Transfers a named, unsealed draft plan and its digest-verified patch artifacts to a replacement manifest.
	 *
	 * <p>
	 * The transferred plan remains reviewer input. This method does not write output decisions, witnesses, batches, or
	 * any placement authority to the replacement manifest; {@link #sealOutputPlan(Path, Path)} must still validate it.
	 *
	 * @param previousManifestFile the manifest that owns the named draft's patch artifacts
	 * @param previousPlanFile the named unsealed draft plan below the previous manifest
	 * @param manifestFile the replacement manifest
	 * @param planFile the new draft plan below the replacement manifest
	 * @throws IOException if an input is unsafe, incompatible, or cannot be copied with its digest intact
	 */
	void transferDraftOutputPlan(Path previousManifestFile, Path previousPlanFile, Path manifestFile,
		Path planFile) throws IOException
	{
		requireThat(previousManifestFile, "previousManifestFile").isRegularFile();
		requireThat(previousPlanFile, "previousPlanFile").isRegularFile();
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(planFile, "planFile").isNotNull();
		BatchManifest previous = manifests.readUnfinalized(previousManifestFile);
		BatchManifest replacement = manifests.readUnfinalized(manifestFile);
		draftOutputPlanTransfer.transfer(previousManifestFile, previousPlanFile, previous, manifestFile, replacement,
			planFile);
	}

	/**
	 * Exports one sealed output's reviewer-authored declarations so its suffix can be rebuilt without copying manifest
	 * internals by hand.
	 *
	 * @param manifestFile the replay manifest
	 * @param outputId the sealed output to export
	 * @param batchFile the new manifest-local batch file
	 * @throws IOException if the output is not sealed or the target file is unsafe
	 */
	void writeOutputBatch(Path manifestFile, String outputId, Path batchFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		requireThat(batchFile, "batchFile").isNotNull();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (manifest.outputWitnesses().stream().noneMatch(witness -> witness.outputId().equals(outputId)))
			throw new IOException("Topic-replay manifest does not contain a sealed output: " + outputId);
		Path target = batchFile.toAbsolutePath().normalize();
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		if (!target.startsWith(directory) || Files.exists(target))
			throw new IOException("Topic-replay output batch target must be new and below the manifest: " + batchFile);
		BatchRequest[] requests = manifest.batches().stream().filter(batch -> batch.destination().equals(outputId)).map(
			batch -> new BatchRequest(batch.id(), batch.source(), batch.patch().path())).toArray(BatchRequest[]::new);
		Files.createDirectories(target.getParent());
		json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), requests);
	}

	/**
	 * Records that one logical output is absorbed into earlier retained output rather than becoming a commit.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param outputId the next logical output to omit from the rewritten history
	 * @throws IOException if the output is unknown, already decided, or out of chronological order
	 */
	void discardOutput(Path manifestFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (!OutputManifestFacts.byId(manifest.outputs()).containsKey(outputId))
			throw new IOException("Topic-replay manifest does not contain output: " + outputId);
		String expectedOutput = OutputManifestFacts.nextUndecided(manifest);
		if (!outputId.equals(expectedOutput))
			throw new IOException("Topic-replay must decide output " + expectedOutput + " before " + outputId);
		if (!outputPlan.sealedOutput(manifest, outputId).disposition().equals("discarded"))
		{
			throw new IOException("Topic-replay output plan requires " + outputId + " to be retained, not discarded");
		}
		List<String> discarded = new ArrayList<>(OutputManifestFacts.discarded(manifest));
		discarded.add(outputId);
		manifests.write(manifestFile, manifest.withDiscardedOutputs(discarded));
	}

	/**
	 * Invalidates one retained output and every later output so their reviewed batch placement can be rebuilt.
	 *
	 * <p>
	 * Immutable source snapshots and raw source-patch reconstructions remain intact. Source reviews whose destinations
	 * change are removed because their whole-or-split claim must be reconsidered with the replacement batches.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param outputId the first output whose recorded placement must be replaced
	 * @throws IOException if the manifest cannot be read or the output is unknown or currently open
	 */
	void invalidateOutputSuffix(Path manifestFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (manifest.outputPlan() != null)
		{
			throw new IOException("A sealed output plan cannot be changed in place. Prepare a new manifest, import valid " +
				"review evidence, and seal a replacement plan before replay.");
		}
		manifests.write(manifestFile, outputSuffixInvalidation.invalidate(manifest, outputId));
	}
}

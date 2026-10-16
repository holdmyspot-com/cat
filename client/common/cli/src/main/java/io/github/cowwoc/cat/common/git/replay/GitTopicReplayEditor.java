package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.CommandLineArguments;
import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Executes version-5 reviewer-authored topic replays without rewriting Git interactive-rebase todo files.
 */
public final class GitTopicReplayEditor
{
	private final Path repository;
	private final Path temporaryRoot;
	private final Git git;
	private final History history;
	private final OriginCandidates originCandidates = new OriginCandidates();
	private final CommandRunner commands;
	private final Gates gates;
	private final OutputGates outputGates;
	private final Finalization finalization;
	private final Backup backups;
	private final JsonMapper json = ReplayJson.newMapper();
	private final ReviewEvidence reviewEvidence;
	private final SemanticEvidence semanticEvidence;
	private final PatchArtifacts patches;
	private final PatchArtifactTransfer patchArtifactTransfer;
	private final OutputPlanInputExport outputPlanInputExport;
	private final DraftOutputPlanTransfer draftOutputPlanTransfer;
	private final OutputSuffixInvalidation outputSuffixInvalidation = new OutputSuffixInvalidation();
	private final ManifestStore<BatchManifest> manifests;
	private final PatchApplication patchApplication;
	private final OutputPlanValidation outputPlan;
	private final OutputPlanWorkflow outputPlanWorkflow;
	private final SourceHunks sourceHunks;
	private final SourcePatches sourcePatches;
	private final PatchWorkflow patchWorkflow;
	private final TreeFiles treeFiles;
	private final ReplacementTrees replacementTrees;
	private final TargetPatches targetPatches;
	private final ThreeWayPatches threeWayPatches;
	private final AdaptedSourcePatches adaptedSourcePatches;
	private final MergeCandidates mergeCandidates;
	private final OutputRecording outputRecording;
	private final SealedOutputRecording sealedOutputRecording;
	private final OutputPlanSealing outputPlanSealing;
	private final DraftOutputAcceptance draftOutputAcceptance;
	private final DeclaredOutputAcceptance declaredOutputAcceptance;
	private final TopicOutputDeclaration topicOutputDeclaration;
	private final SourceReviewValidation sourceReviewValidation = new SourceReviewValidation();
	private final SourceReviewRecording sourceReviewRecording;
	private final OriginReviewValidation originReviewValidation;
	private final OriginReviewFacts originReviewFacts;
	private final OriginReviewReports originReviewReports;
	private final OriginReviewRecording originReviewRecording;
	private final PathTransitionValidation pathTransitionValidation = new PathTransitionValidation();
	private final PathTransitionRecording pathTransitionRecording;
	private final PathTransitionFacts pathTransitionFacts;
	private final OutputWitnessValidation outputWitnessValidation = new OutputWitnessValidation();
	private final SealedPrefixValidation sealedPrefixValidation = new SealedPrefixValidation();
	private final SealedPrefixRecovery sealedPrefixRecovery;
	private final ReplayExecution replayExecution;
	private final ReplayPreflight replayPreflight;
	private final ReplayVerification replayVerification;
	private final TopicReplayCompletion completion;
	private final ReviewEvidenceImport reviewEvidenceImport;
	private final ReplayManifestInitialization replayManifestInitialization;
	private final SourceBootstrap sourceBootstrap;
	private final SourceReconstructionRecording sourceReconstructionRecording;
	private final ReviewedPrefixImport reviewedPrefixImport;
	private final InvocationManifest invocationManifest;
	private final CompletedBatchManifest completedBatchManifest;

	/**
	 * Creates an editor that runs Git in one repository.
	 *
	 * @param repository the repository working directory
	 */
	public GitTopicReplayEditor(Path repository)
	{
		this(repository, temporaryRoot(repository));
	}

	/**
	 * Returns the CAT work temp directory for one repository.
	 *
	 * @param repository the repository that owns the workflow state
	 * @return the CAT-owned temporary root
	 */
	public static Path temporaryRoot(Path repository)
	{
		requireThat(repository, "repository").isNotNull();
		try (ToolScope scope = ToolScope.forWorkingDirectory(repository))
		{
			return scope.temporaryWorkRoot();
		}
	}

	/**
	 * Creates an editor that runs Git in one repository and stores transient state below one root.
	 *
	 * @param repository the repository working directory
	 * @param temporaryRoot the root that owns temporary workflow state
	 */
	public GitTopicReplayEditor(Path repository, Path temporaryRoot)
	{
		requireThat(repository, "repository").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		this.repository = repository.toAbsolutePath().normalize();
		this.temporaryRoot = temporaryRoot.toAbsolutePath().normalize();
		this.git = new Git(this.repository);
		this.history = new History(this.repository, this.git);
		this.originReviewValidation = new OriginReviewValidation(this.history);
		this.originReviewFacts = new OriginReviewFacts(this.originCandidates, this.originReviewValidation,
			this.sourceReviewValidation);
		this.commands = new CommandRunner(this.repository);
		this.gates = new Gates(this.commands);
		this.outputGates = new OutputGates(this.git, this::createTemporaryDirectory);
		this.finalization = new Finalization(this.git, this.history, json);
		this.backups = new Backup(this.git, Clock.systemUTC());
		this.reviewEvidence = new ReviewEvidence(json);
		this.originReviewReports = new OriginReviewReports(json, this.originReviewFacts, this.sourceReviewValidation,
			this.reviewEvidence);
		this.originReviewRecording = new OriginReviewRecording(json, this.reviewEvidence, this.originReviewValidation,
			this.sourceReviewValidation);
		this.pathTransitionRecording = new PathTransitionRecording(json, this.reviewEvidence,
			this.pathTransitionValidation, this.sourceReviewValidation);
		this.pathTransitionFacts = new PathTransitionFacts(this.git);
		this.patches = new PatchArtifacts();
		this.semanticEvidence = new SemanticEvidence(this.history, this.patches, this.reviewEvidence,
			new SemanticPlanValidation(this.reviewEvidence));
		this.patchArtifactTransfer = new PatchArtifactTransfer(this.patches);
		this.outputPlanInputExport = new OutputPlanInputExport(json, this.patchArtifactTransfer);
		this.draftOutputPlanTransfer = new DraftOutputPlanTransfer(json, this.patches);
		this.manifests = new ManifestStore<>(this.temporaryRoot, json, BatchManifest.class);
		this.patchApplication = new PatchApplication(this.git, this.manifests, this.patches);
		this.sealedPrefixRecovery = new SealedPrefixRecovery(this.git, this.history, this.patches,
			this.patchApplication, this.sealedPrefixValidation);
		this.outputPlan = new OutputPlanValidation(this.history, this.patches, this.patchApplication);
		this.sourceHunks = new SourceHunks(this.git);
		this.sourcePatches = new SourcePatches(this.git);
		this.treeFiles = new TreeFiles(this.git);
		this.replacementTrees = new ReplacementTrees(this.git, this.manifests);
		this.targetPatches = new TargetPatches(this.git, this.manifests, this.patchApplication);
		this.threeWayPatches = new ThreeWayPatches(this.git, this.manifests, this.patchApplication);
		this.adaptedSourcePatches = new AdaptedSourcePatches(this.git, this.sourceHunks, this.threeWayPatches);
		this.mergeCandidates = new MergeCandidates(this.git, this.manifests);
		this.patchWorkflow = new PatchWorkflow(this.manifests, this.sourcePatches, this.sourceHunks,
			this.adaptedSourcePatches, this.targetPatches, this.treeFiles, this.replacementTrees, this.mergeCandidates);
		this.outputRecording = new OutputRecording(this.history, this.patches, this.patchApplication);
		this.sealedOutputRecording = new SealedOutputRecording(json, this.manifests, this.outputPlan,
			this.outputRecording);
		this.outputPlanSealing = new OutputPlanSealing(json, this.manifests, this.sourceReviewValidation,
			this.originCandidates, this.originReviewValidation, this.outputPlan, this.reviewEvidence);
		this.draftOutputAcceptance = new DraftOutputAcceptance(json, this.outputPlan, this.sourceReviewValidation,
			this.reviewEvidence, this.semanticEvidence, this.outputGates);
		this.outputPlanWorkflow = new OutputPlanWorkflow(json, this.manifests, this.outputPlan,
			this.sealedOutputRecording, this.outputPlanSealing, this.draftOutputAcceptance, this.outputPlanInputExport,
			this.draftOutputPlanTransfer, this.outputSuffixInvalidation);
		this.topicOutputDeclaration = new TopicOutputDeclaration(json, this.manifests, this.reviewEvidence,
			this.semanticEvidence);
		this.sourceReviewRecording = new SourceReviewRecording(this.git, json, this.sourceReviewValidation,
			this.manifests);
		this.replayExecution = new ReplayExecution(this.git, this.history, this.manifests, this.patches);
		this.replayPreflight = new ReplayPreflight(this.history, this.patchApplication, this.reviewEvidence,
			this.semanticEvidence, this.pathTransitionFacts, this.pathTransitionValidation, this.sourceReviewValidation,
			json);
		this.replayVerification = new ReplayVerification(this.git, this.history, this.replayPreflight);
		this.reviewEvidenceImport = new ReviewEvidenceImport(this.manifests, this.reviewEvidence,
			this.sourceReviewValidation, this.originCandidates, this.originReviewValidation, this.pathTransitionFacts,
			this.pathTransitionValidation);
		this.replayManifestInitialization = new ReplayManifestInitialization(this.git, this.history, this.manifests);
		this.sourceBootstrap = new SourceBootstrap(this.sourcePatches, this.patches, this.patchApplication,
			this.manifests);
		this.sourceReconstructionRecording = new SourceReconstructionRecording(this.patches, this.patchApplication,
			this.manifests);
		this.reviewedPrefixImport = new ReviewedPrefixImport(json, this.manifests, this.sealedPrefixValidation,
			this.patchArtifactTransfer, this.patches, this.patchWorkflow, this.reviewEvidence);
		this.invocationManifest = new InvocationManifest(this.temporaryRoot);
		this.completedBatchManifest = new CompletedBatchManifest(this.manifests, this.outputPlan,
			this.outputWitnessValidation, this.sourceReviewValidation, this.originReviewFacts, this.reviewEvidence);
		this.declaredOutputAcceptance = new DeclaredOutputAcceptance(this.completedBatchManifest, this.reviewEvidence,
			this.semanticEvidence, this.outputGates);
		this.completion = new TopicReplayCompletion(this.git, this.history, this.manifests,
			this.completedBatchManifest, this.replayPreflight, this.replayExecution, this.replayVerification, this.backups,
			this.finalization);
	}

	/**
	 * Executes a version-5 topic-replay command.
	 *
	 * @param args the command and its arguments
	 * @throws IOException if a command cannot complete
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		new GitTopicReplayEditor(repositoryRoot(Path.of("."))).execute(args);
	}

	/**
	 * Resolves the root of the repository containing a working directory.
	 *
	 * @param directory a directory inside the repository
	 * @return the repository root
	 * @throws IOException if Git cannot identify the repository root
	 */
	static Path repositoryRoot(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		Path workingDirectory = directory.toAbsolutePath().normalize();
		return Path.of(new Git(workingDirectory).run("rev-parse", "--show-toplevel").trim());
	}

	/**
	 * Executes a version-5 topic-replay command in this editor's repository.
	 *
	 * @param args the command and its arguments
	 * @throws IOException if a command cannot complete
	 */
	public void execute(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 2 && args[0].equals("replay"))
		{
			replay(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("finalize"))
		{
			finalizeReplay(Path.of(args[1]));
			return;
		}
		if (args.length == 4 && args[0].equals("verify-finalization"))
		{
			verifyFinalization(Path.of(args[1]), args[2], args[3]);
			return;
		}
		if (args.length == 3 && args[0].equals("prepare"))
		{
			Path manifest = invocationManifest.create();
			prepare(manifest, args[1], args[2]);
			InvocationManifest.print(manifest);
			return;
		}
		if (args.length == 4 && args[0].equals("prepare-fixed-prefix"))
		{
			Path manifest = invocationManifest.create();
			prepareFixedPrefix(manifest, args[1], args[2], args[3]);
			InvocationManifest.print(manifest);
			return;
		}
		if (args.length == 4 && args[0].equals("initialize"))
		{
			initialize(Path.of(args[3]), args[1], args[2]);
			return;
		}
		if (args.length == 3 && args[0].equals("declare-topic-outputs"))
		{
			declareTopicOutputs(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length == 4 && args[0].equals("record-output"))
		{
			recordOutput(Path.of(args[1]), args[2], Path.of(args[3]));
			return;
		}
		if (args.length == 3 && args[0].equals("record-planned-output"))
		{
			recordPlannedOutput(Path.of(args[1]), args[2]);
			return;
		}
		if (args.length == 3 && args[0].equals("seal-output-plan"))
		{
			sealOutputPlan(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length == 3 && args[0].equals("validate-draft-declared-output-acceptance"))
		{
			validateDraftDeclaredOutputAcceptance(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length == 4 && args[0].equals("write-output-batch"))
		{
			writeOutputBatch(Path.of(args[1]), args[2], Path.of(args[3]));
			return;
		}
		if (args.length == 3 && args[0].equals("discard-output"))
		{
			discardOutput(Path.of(args[1]), args[2]);
			return;
		}
		if (args.length == 3 && args[0].equals("invalidate-output-suffix"))
		{
			invalidateOutputSuffix(Path.of(args[1]), args[2]);
			return;
		}
		if (args.length == 3 && args[0].equals("record-source-reviews"))
		{
			recordSourceReviews(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length >= 3 && args[0].equals("record-source-review-batches"))
		{
			recordSourceReviewBatches(Path.of(args[1]), List.of(args).subList(2, args.length).stream().map(Path::of).
				toList());
			return;
		}
		if (args.length == 5 && args[0].equals("verify-identity"))
		{
			verifyIdentity(args[1], args[2], args[3], args[4]);
			return;
		}
		if (args.length >= 4 && args[0].equals("run-gate"))
		{
			runGate(Path.of(args[1]), args[2], List.of(args).subList(3, args.length));
			return;
		}
		if (args.length >= 4 && args[0].equals("run-output-gate"))
		{
			if (args.length >= 6 && args[3].equals("--only-output"))
			{
				runOutputGate(Path.of(args[1]), args[2], args[4], List.of(args).subList(5, args.length));
				return;
			}
			runOutputGate(Path.of(args[1]), args[2], List.of(args).subList(3, args.length));
			return;
		}
		if (args.length == 2 && args[0].equals("run-declared-output-acceptance"))
		{
			runDeclaredOutputAcceptance(Path.of(args[1]), null);
			return;
		}
		if (args.length == 4 && args[0].equals("run-declared-output-acceptance") &&
			args[2].equals("--only-output"))
		{
			runDeclaredOutputAcceptance(Path.of(args[1]), args[3]);
			return;
		}
		if (args.length == 2 && args[0].equals("bootstrap-preserved-output-batches"))
		{
			bootstrapPreservedOutputBatches(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("validate-sealed-prefix"))
		{
			validateSealedPrefix(Path.of(args[1]));
			return;
		}
		if (args.length == 3 && args[0].equals("affected-suffix"))
		{
			writeAffectedSuffix(Path.of(args[1]), args[2]);
			return;
		}
		if (args.length >= 4 && args[0].equals("record-source-reconstruction"))
		{
			recordSourceReconstruction(Path.of(args[1]), args[2], List.of(args).subList(3, args.length));
			return;
		}
		if (args.length == 4 && args[0].equals("record-source-patch-reconstruction"))
		{
			recordSourcePatchReconstruction(Path.of(args[1]), args[2], Path.of(args[3]));
			return;
		}
		if (args.length == 2 && args[0].equals("origin-candidates"))
		{
			writeOriginCandidates(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("origin-review-scaffold"))
		{
			writeOriginReviewScaffold(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("origin-review-audit"))
		{
			writeOriginReviewAudit(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("path-transition-facts"))
		{
			writePathTransitionFacts(Path.of(args[1]));
			return;
		}
		if (args.length == 3 && args[0].equals("record-path-transition-decisions"))
		{
			recordPathTransitionDecisions(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length >= 3 && args[0].equals("record-path-transition-decision-batches"))
		{
			recordPathTransitionDecisionBatches(Path.of(args[1]), List.of(args).subList(2, args.length).stream().
				map(Path::of).toList());
			return;
		}
		if (args.length == 3 && args[0].equals("record-origin-reviews"))
		{
			recordOriginReviews(Path.of(args[1]), Path.of(args[2]));
			return;
		}
		if (args.length >= 3 && args[0].equals("record-origin-review-batches"))
		{
			recordOriginReviewBatches(Path.of(args[1]), List.of(args).subList(2, args.length).stream().map(Path::of).
				toList());
			return;
		}
		if (args.length == 2 && args[0].equals("preflight"))
		{
			preflight(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("exposure-inventory"))
		{
			writeExposureInventory(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("report-topics"))
		{
			writeFinalTopics(Path.of(args[1]));
			return;
		}
		if (args.length == 4 && args[0].equals("capture-source-patch"))
		{
			captureSourcePatch(Path.of(args[1]), args[2], Path.of(args[3]));
			return;
		}
		if (args.length >= 5 && args[0].equals("capture-source-path-patch"))
		{
			captureSourcePathPatch(Path.of(args[1]), args[2], Path.of(args[3]), List.of(args).subList(4, args.length));
			return;
		}
		if (args.length == 6 && args[0].equals("capture-source-hunk-patch"))
		{
			captureSourceHunkPatch(Path.of(args[1]), args[2], Path.of(args[3]), args[4], Integer.parseInt(args[5]));
			return;
		}
		if (args.length == 7 && args[0].equals("capture-target-adapted-path-patch"))
		{
			captureTargetAdaptedPathPatch(Path.of(args[1]), args[2], Path.of(args[3]), args[4], args[5],
				Path.of(args[6]));
			return;
		}
		if (args.length == 5 && args[0].equals("write-tree-path"))
		{
			writeTreePath(Path.of(args[1]), args[2], args[3], Path.of(args[4]));
			return;
		}
		if (args.length == 6 && args[0].equals("write-replacement-tree"))
		{
			writeReplacementTree(Path.of(args[1]), args[2], args[3], Path.of(args[4]), Path.of(args[5]));
			return;
		}
		if (args.length == 6 && args[0].equals("write-three-way-merge-candidate"))
		{
			writeThreeWayMergeCandidate(Path.of(args[1]), args[2], args[3], args[4], Path.of(args[5]));
			return;
		}
		if (args.length >= 6 && args[0].equals("capture-three-way-adapted-source-patch"))
		{
			captureThreeWayAdaptedSourcePatch(Path.of(args[1]), args[2], Path.of(args[3]), args[4],
				List.of(args).subList(5, args.length));
			return;
		}
		if (args.length == 7 && args[0].equals("capture-three-way-adapted-source-hunk-patch"))
		{
			captureThreeWayAdaptedSourceHunkPatch(Path.of(args[1]), args[2], Path.of(args[3]), args[4], args[5],
				Integer.parseInt(args[6]));
			return;
		}
		if (args.length == 4 && args[0].equals("write-draft-output-tree"))
		{
			System.out.println(writeDraftOutputTree(Path.of(args[1]), Path.of(args[2]), args[3]));
			return;
		}
		throw new IllegalArgumentException("Usage: GitTopicReplayEditor <version-5-command>");
	}

	/**
	 * Captures the exact binary diff of one reviewer-selected whole source commit as an immutable patch artifact.
	 *
	 * <p>
	 * This command neither selects the source nor assigns it to an output. It only captures a whole source after the
	 * reviewer has decided that the source does not need a hunk-level split.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @throws IOException if the source is not declared by the manifest, the output path is unsafe, or Git cannot render
	 *         the source diff
	 */
	public void captureSourcePatch(Path manifestFile, String source, Path patchFile) throws IOException
	{
		patchWorkflow.captureSourcePatch(manifestFile, source, patchFile);
	}

	/**
	 * Captures the exact binary diff for reviewer-selected paths from one source commit.
	 *
	 * <p>
	 * The caller supplies the paths after reviewing their placement. This command preserves Git's diff bytes but does
	 * not decide whether a path needs further hunk-level splitting or where its patch belongs.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @param paths the non-empty selected repository-relative paths, or empty to select the whole source
	 * @throws IOException if the source is not declared by the manifest, a path or output path is unsafe, or Git cannot
	 *         render the source diff
	 */
	public void captureSourcePathPatch(Path manifestFile, String source, Path patchFile, List<String> paths)
		throws IOException
	{
		patchWorkflow.captureSourcePathPatch(manifestFile, source, patchFile, paths);
	}

	/**
	 * Captures one reviewer-selected textual diff hunk from one path in a source commit.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @param path the reviewed repository-relative path
	 * @param hunkIndex the one-based hunk number within the path diff
	 * @throws IOException if the source, path, hunk, or output path is invalid
	 */
	public void captureSourceHunkPatch(Path manifestFile, String source, Path patchFile, String path,
		int hunkIndex)
		throws IOException
	{
		patchWorkflow.captureSourceHunkPatch(manifestFile, source, patchFile, path, hunkIndex);
	}

	/**
	 * Captures one selected source hunk as a patch for an earlier commit when Git can combine the two versions.
	 *
	 * <p>
	 * This is the hunk-level counterpart of {@link #captureThreeWayAdaptedSourcePatch(Path, String, Path, String,
	 * List)}. Use it when other hunks in the same source path belong to different outputs.
	 *
	 * @param manifestFile the version-5 manifest containing the source
	 * @param source the source commit containing the selected hunk
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the hunk must update
	 * @param path the repository-relative path containing the hunk
	 * @param hunkIndex the one-based hunk number within the path diff
	 * @throws IOException if an input is unsafe or Git cannot combine and verify the selected hunk
	 */
	public void captureThreeWayAdaptedSourceHunkPatch(Path manifestFile, String source, Path patchFile,
		String targetTree, String path, int hunkIndex) throws IOException
	{
		patchWorkflow.captureThreeWayAdaptedSourceHunkPatch(manifestFile, source, patchFile, targetTree, path, hunkIndex);
	}

	/**
	 * Captures a patch that updates one path changed by a source commit from a declared earlier version of that path.
	 *
	 * <p>
	 * The reviewer supplies the path's intended contents after the selected source change has been adapted to the
	 * declared target tree. This command creates an immutable Git patch from that target tree to those contents and
	 * proves the patch applies back to the same tree. It does not use the result as proof of the source's original tree
	 * transition; record that proof separately with {@link #recordSourcePatchReconstruction(Path, String, Path)}.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the source commit containing the selected change
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version of the path the patch updates
	 * @param path the repository-relative path changed by the source commit to adapt
	 * @param replacementFile the manifest-relative regular file containing the path's intended target version
	 * @throws IOException if an input is unsafe, the source did not change the path, or Git cannot create and verify the
	 *         target-relative patch
	 */
	public void captureTargetAdaptedPathPatch(Path manifestFile, String source, Path patchFile,
		String targetTree, String path, Path replacementFile) throws IOException
	{
		patchWorkflow.captureTargetAdaptedPathPatch(manifestFile, source, patchFile, targetTree, path, replacementFile);
	}

	/**
	 * Copies one path from a declared earlier commit into the manifest workspace for a reviewer to update.
	 *
	 * @param manifestFile the version-5 manifest that owns the workspace file
	 * @param targetTree the commit or tree containing the earlier file version
	 * @param path the repository-relative path to copy
	 * @param outputFile the new manifest-relative file that receives the earlier version
	 * @throws IOException if an input is unsafe or Git cannot read the selected file
	 */
	public void writeTreePath(Path manifestFile, String targetTree, String path, Path outputFile)
		throws IOException
	{
		patchWorkflow.writeTreePath(manifestFile, targetTree, path, outputFile);
	}

	/**
	 * Writes the tree identifier produced by replacing one path in an existing tree with reviewed contents.
	 *
	 * @param manifestFile the version-5 manifest that owns the workspace files
	 * @param baseTree the commit or tree to update
	 * @param path the repository-relative path to replace
	 * @param replacementFile the manifest-relative regular file containing the replacement contents
	 * @param outputFile the new manifest-relative file that receives the resulting tree identifier
	 * @throws IOException if an input is unsafe or Git cannot create the replacement tree
	 */
	public void writeReplacementTree(Path manifestFile, String baseTree, String path, Path replacementFile,
		Path outputFile) throws IOException
	{
		patchWorkflow.writeReplacementTree(manifestFile, baseTree, path, replacementFile, outputFile);
	}

	/**
	 * Writes Git's proposed merge of a source-path change into an earlier version of that path for reviewer approval.
	 *
	 * <p>
	 * The result combines the earlier file with the source's parent-to-source change. Unchanged or independently
	 * changed regions are retained automatically. Git inserts conflict markers only where the reviewer must choose the
	 * intended result; the caller must resolve them before creating a patch from this candidate.
	 *
	 * @param manifestFile the version-5 manifest that declares the source
	 * @param source the source commit that changed the path
	 * @param targetTree the commit or tree containing the earlier file version
	 * @param path the repository-relative changed path to merge
	 * @param outputFile the new manifest-relative file that receives the proposed result
	 * @throws IOException if an input is unsafe or Git cannot read or merge the path versions
	 */
	public void writeThreeWayMergeCandidate(Path manifestFile, String source, String targetTree, String path,
		Path outputFile) throws IOException
	{
		patchWorkflow.writeThreeWayMergeCandidate(manifestFile, source, targetTree, path, outputFile);
	}

	/**
	 * Captures selected paths from a source change as a patch for an earlier commit when Git can combine the versions.
	 *
	 * <p>
	 * Git compares the selected paths in the source commit, its parent, and the declared earlier version. The resulting
	 * patch changes only what the source adds beyond the earlier version. This preserves an earlier cleanup or correction
	 * when it does not overlap the selected source change. If Git cannot combine the versions, prepare the intended
	 * version of each changed file and use {@link #captureTargetAdaptedPathPatch(Path, String, Path, String, String,
	 * Path)} instead.
	 *
	 * @param manifestFile the version-5 manifest containing the source
	 * @param source the source commit containing the selected paths
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the source change must update
	 * @param paths the non-empty repository-relative paths to combine
	 * @throws IOException if an input is unsafe or Git cannot combine and verify the source change
	 */
	public void captureThreeWayAdaptedSourcePatch(Path manifestFile, String source, Path patchFile,
		String targetTree, List<String> paths) throws IOException
	{
		patchWorkflow.captureThreeWayAdaptedSourcePatch(manifestFile, source, patchFile, targetTree, paths);
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
	public String writeDraftOutputTree(Path manifestFile, Path planFile, String outputId) throws IOException
	{
		return outputPlanWorkflow.writeDraftOutputTree(manifestFile, planFile, outputId);
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
	public void recordOutput(Path manifestFile, String outputId, Path batchFile) throws IOException
	{
		outputPlanWorkflow.recordOutput(manifestFile, outputId, batchFile);
	}

	/**
	 * Records the next retained output directly from its sealed batch declarations.
	 *
	 * @param manifestFile the replay manifest containing a sealed output plan
	 * @param outputId the next retained output in final-history order
	 * @throws IOException if the output is not the plan's next retained decision or cannot be recorded
	 */
	public void recordPlannedOutput(Path manifestFile, String outputId) throws IOException
	{
		outputPlanWorkflow.recordPlannedOutput(manifestFile, outputId);
	}

	/**
	 * Validates and seals every retained-or-discarded output decision before replay writes an output witness.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param planFile the JSON array of ordered output dispositions and retained batch declarations
	 * @throws IOException if the plan is incomplete, contradicts a review, cannot recreate the captured final tree, or
	 *         the manifest already contains a replay decision
	 */
	public void sealOutputPlan(Path manifestFile, Path planFile) throws IOException
	{
		outputPlanWorkflow.sealOutputPlan(manifestFile, planFile);
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
	public void validateDraftDeclaredOutputAcceptance(Path manifestFile, Path planFile) throws IOException
	{
		outputPlanWorkflow.validateDraftDeclaredOutputAcceptance(manifestFile, planFile);
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
	public void exportOutputPlanInput(Path previousManifestFile, Path manifestFile, Path planFile)
		throws IOException
	{
		outputPlanWorkflow.exportOutputPlanInput(previousManifestFile, manifestFile, planFile);
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
	public void transferDraftOutputPlan(Path previousManifestFile, Path previousPlanFile, Path manifestFile,
		Path planFile) throws IOException
	{
		outputPlanWorkflow.transferDraftOutputPlan(previousManifestFile, previousPlanFile, manifestFile, planFile);
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
	public void writeOutputBatch(Path manifestFile, String outputId, Path batchFile) throws IOException
	{
		outputPlanWorkflow.writeOutputBatch(manifestFile, outputId, batchFile);
	}

	/**
	 * Records that one logical output is absorbed into earlier retained output rather than becoming a commit.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param outputId the next logical output to omit from the rewritten history
	 * @throws IOException if the output is unknown, already decided, or out of chronological order
	 */
	public void discardOutput(Path manifestFile, String outputId) throws IOException
	{
		outputPlanWorkflow.discardOutput(manifestFile, outputId);
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
	public void invalidateOutputSuffix(Path manifestFile, String outputId) throws IOException
	{
		outputPlanWorkflow.invalidateOutputSuffix(manifestFile, outputId);
	}

	/**
	 * Imports a sealed manifest prefix into a new manifest with the same source tree transitions.
	 *
	 * <p>
	 * A commit hash may change when an earlier history rewrite recreates an otherwise identical transition. This command
	 * matches sources only by their ordered parent-tree and resulting-tree pairs, copies the immutable patch artifacts,
	 * and rewrites the affected source references. It does not infer, alter, or extend any topic-placement decision.
	 *
	 * @param previousManifestFile the manifest whose sealed output prefix is reused
	 * @param newManifestFile the unreviewed manifest whose source prefix has the same tree transitions
	 * @throws IOException if either manifest is invalid, the source transitions differ, or imported artifacts are unsafe
	 */
	public void importReviewedPrefix(Path previousManifestFile, Path newManifestFile) throws IOException
	{
		reviewedPrefixImport.importPrefix(previousManifestFile, newManifestFile);
	}

	/**
	 * Revalidates the mechanically sealed prefix of the current replay without importing any reviewer judgment.
	 *
	 * <p>
	 * This command is for recovery within one active replay. It checks that each captured source still has its recorded
	 * parent and result trees, that every prefix patch still has its recorded digest, and that applying the prefix
	 * batches recreates each recorded output witness. It does not decide whether a prior placement remains semantically
	 * correct.
	 *
	 * @param manifestFile the manifest that owns the sealed prefix
	 * @throws IOException if no contiguous sealed prefix exists or any recorded mechanical fact no longer matches
	 */
	public void validateSealedPrefix(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		BatchManifest manifest = readPreparedBatchManifest(manifestFile);
		int length = sealedPrefixRecovery.verify(manifestFile, manifest);
		System.out.println("sealedOutputs=" + length);
	}

	/**
	 * Reports the chronological output suffix that must be reconsidered after a reviewer identifies its first affected
	 * output.
	 *
	 * <p>
	 * The caller supplies the first affected output because only review can determine whether a new hunk changes that
	 * output's responsibility. The command reports the mechanical suffix; it does not infer placement from paths or
	 * commit messages.
	 *
	 * @param manifestFile the replay manifest
	 * @param outputId the first output whose reconstructed state may change
	 * @throws IOException if the output is not declared by the manifest
	 */
	public void writeAffectedSuffix(Path manifestFile, String outputId) throws IOException
	{
		System.out.println("firstAffectedOutput=" + outputId);
		for (String affectedOutput : affectedSuffix(manifestFile, outputId))
			System.out.println("affectedOutput=" + affectedOutput);
	}

	/**
	 * Returns the output identifiers from a reviewer-selected first affected output through the end of the replay.
	 *
	 * @param manifestFile the replay manifest
	 * @param outputId the first output whose reconstructed state may change
	 * @return the non-empty chronological affected output identifiers
	 * @throws IOException if the output is not declared by the manifest
	 */
	public List<String> affectedSuffix(Path manifestFile, String outputId) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		if (!OutputManifestFacts.byId(manifest.outputs()).containsKey(outputId))
			throw new IOException("Topic-replay manifest does not contain output: " + outputId);
		int index = OutputManifestFacts.index(manifest.outputs(), outputId);
		return manifest.outputs().subList(index, manifest.outputs().size()).stream().map(Output::id).toList();
	}

	/**
	 * Imports prior reviewer decisions into a freshly bootstrapped manifest when their immutable Git facts still match.
	 *
	 * <p>
	 * This command transfers source, origin, and path-transition review evidence only. It does not copy batches,
	 * reconstructions, witnesses, discarded outputs, patches, or any sealed replay result. Consequently, an imported
	 * decision remains subject to the replacement manifest's ordinary preflight and replay checks.
	 *
	 * @param previousManifestFile the manifest that owns the prior review evidence
	 * @param newManifestFile the freshly bootstrapped manifest that receives matching evidence
	 * @throws IOException if either manifest is invalid, the source transitions differ, or either review state is unsafe
	 */
	public void importReviewEvidence(Path previousManifestFile, Path newManifestFile) throws IOException
	{
		importReviewEvidence(previousManifestFile, newManifestFile, Set.of());
	}

	/**
	 * Imports prior reviewer decisions except evidence derived from explicitly excluded source commits.
	 *
	 * @param previousManifestFile the manifest that owns the prior review evidence
	 * @param newManifestFile the freshly bootstrapped manifest that receives matching evidence
	 * @param excludedSources prior source commits whose decisions must be reviewed again
	 * @throws IOException if either manifest is invalid, the source transitions differ, or either review state is unsafe
	 */
	private void importReviewEvidence(Path previousManifestFile, Path newManifestFile, Set<String> excludedSources)
		throws IOException
	{
		requireThat(previousManifestFile, "previousManifestFile").isRegularFile();
		requireThat(newManifestFile, "newManifestFile").isRegularFile();
		BatchManifest previous = readUnfinalizedBatchManifest(previousManifestFile);
		BatchManifest replacement = readUnfinalizedBatchManifest(newManifestFile);
		reviewEvidenceImport.importEvidence(previousManifestFile, previous, newManifestFile, replacement,
			excludedSources);
	}

	/**
	 * Creates raw source-patch proofs for an unreviewed replay manifest.
	 *
	 * <p>
	 * This command performs no topic or placement judgment. It records each source's immutable raw reconstruction, but
	 * deliberately creates no output batches. A default source patch is a reconstruction proof, not a topic decision;
	 * reviewers must write every output batch after the behavior-first topic plan is sealed.
	 *
	 * @param manifestFile the new version-5 replay manifest
	 * @throws IOException if the manifest has already acquired replay state or its artifact directories are unsafe
	 */
	public void bootstrapPreservedOutputBatches(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		sourceBootstrap.bootstrap(manifestFile, manifest);
	}

	/**
	 * Records and verifies the complete ordered batch sequence that reconstructs one source commit.
	 *
	 * <p>
	 * The caller supplies the reviewed batch order. This method verifies the sequence against the source commit's
	 * captured parent and tree before persisting it; it does not infer missing batches or their order.
	 *
	 * @param manifestFile the version-5 manifest containing the source and batches
	 * @param source the full hash of the source commit to reconstruct
	 * @param batchIds the non-empty ordered batch identifiers selected for the source
	 * @throws IOException if a batch is missing, duplicated, assigned to another source, or cannot reconstruct the
	 *         source commit exactly
	 */
	public void recordSourceReconstruction(Path manifestFile, String source, List<String> batchIds)
		throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(batchIds, "batchIds").isNotEmpty();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		sourceReconstructionRecording.recordBatches(manifestFile, manifest, source, batchIds);
	}

	/**
	 * Records an original source patch that independently proves a target-adapted placement batch still accounts for a
	 * source commit exactly.
	 *
	 * @param manifestFile the version-5 manifest
	 * @param source the full source commit hash
	 * @param patchFile the patch from the source parent to the source commit
	 * @throws IOException if the patch is outside the manifest, cannot reconstruct the source, or the source is unknown
	 */
	public void recordSourcePatchReconstruction(Path manifestFile, String source, Path patchFile)
		throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isRegularFile();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		sourceReconstructionRecording.recordPatch(manifestFile, manifest, source, patchFile);
	}

	/**
	 * Atomically records the reviewer-authored source reviews for one captured replay range.
	 *
	 * <p>
	 * The reviewer supplies every semantic judgment, destination, and evidence in one JSON array. This command validates
	 * exact source coverage against the unchanged captured tip before recording any review, so an omitted or stale review
	 * cannot leave a partially updated manifest.
	 *
	 * @param manifestFile the manifest whose source review is being recorded
	 * @param reviewFile the reviewer-authored JSON array below the manifest directory
	 * @throws IOException if the manifest or review file is invalid, stale, incomplete, or cannot be written
	 */
	public void recordSourceReviews(Path manifestFile, Path reviewFile) throws IOException
	{
		sourceReviewRecording.record(manifestFile, reviewFile);
	}

	/**
	 * Atomically merges disjoint source-review files prepared by independent reviewers.
	 *
	 * @param manifestFile the replay manifest
	 * @param reviewFiles non-empty manifest-local reviewer files
	 * @throws IOException if a file, review, or merge is invalid
	 */
	public void recordSourceReviewBatches(Path manifestFile, List<Path> reviewFiles) throws IOException
	{
		sourceReviewRecording.recordBatches(manifestFile, reviewFiles);
	}

	/**
	 * Creates a mechanical topic-replay manifest template for an existing linear commit range.
	 *
	 * @param manifestFile the new manifest file
	 * @param rebaseBase the commit immediately before the range, or {@value #ROOT_BASE} for an orphan-root range
	 * @param tip the range's final commit
	 * @throws IOException if Git cannot read the range or the manifest cannot be written
	 */
	public void initialize(Path manifestFile, String rebaseBase, String tip) throws IOException
	{
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(tip, "tip").isNotBlank();
		requireThat(manifestFile, "manifestFile").isNotNull();
		replayManifestInitialization.initialize(manifestFile, rebaseBase, tip);
	}

	/**
	 * Declares the ordered topic outputs that the reviewer will populate from the captured source changes.
	 *
	 * @param manifestFile the unreviewed replay manifest
	 * @param outputFile the reviewer-authored JSON output declarations
	 * @throws IOException if the declarations are incomplete or replay state already exists
	 */
	public void declareTopicOutputs(Path manifestFile, Path outputFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(outputFile, "outputFile").isRegularFile();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		topicOutputDeclaration.declare(manifestFile, manifest, outputFile);
	}

	/**
	 * Captures one rewrite range for later review and replay.
	 *
	 * @param manifestFile the new replay manifest
	 * @param rebaseBase the commit immediately before the range, or {@value #ROOT_BASE} for an orphan-root range
	 * @param tip the range's final commit
	 * @throws IOException if Git state is unsafe or the manifest cannot be created
	 */
	public void prepare(Path manifestFile, String rebaseBase, String tip) throws IOException
	{
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(tip, "tip").isNotBlank();
		requireThat(manifestFile, "manifestFile").isNotNull();
		initialize(manifestFile, rebaseBase, tip);
	}

	/**
	 * Captures a range whose earlier, known-complete outputs remain fixed while later sources are reviewed.
	 *
	 * @param manifestFile the new replay manifest
	 * @param rebaseBase the commit immediately before the complete prefix
	 * @param fixedTip the final unchanged commit of the complete prefix
	 * @param tip the final commit of the extended range
	 * @throws IOException if the fixed prefix is not an unchanged prefix of the requested range
	 */
	public void prepareFixedPrefix(Path manifestFile, String rebaseBase, String fixedTip, String tip)
		throws IOException
	{
		requireThat(rebaseBase, "rebaseBase").isNotBlank();
		requireThat(fixedTip, "fixedTip").isNotBlank();
		requireThat(tip, "tip").isNotBlank();
		requireThat(manifestFile, "manifestFile").isNotNull();
		replayManifestInitialization.initializeFixedPrefix(manifestFile, rebaseBase, fixedTip, tip);
	}

	/**
	 * Verifies that every rewritten commit uses one approved author and committer identity.
	 *
	 * @param rebaseBase the commit immediately before the rewritten range
	 * @param rewrittenTip the rewritten range's final commit
	 * @param name the approved Git author and committer name
	 * @param email the approved Git author and committer email
	 * @throws IOException if Git cannot read the range or any identity differs from the approved policy
	 */
	public void verifyIdentity(String rebaseBase, String rewrittenTip, String name, String email)
		throws IOException
	{
		completion.verifyIdentity(rebaseBase, rewrittenTip, name, email);
		System.out.println("identity=" + name + " <" + email + ">");
	}

	/**
	 * Reads and validates a version-5 batch manifest without inferring any batch's purpose or destination.
	 *
	 * @param manifestFile the v4 batch manifest
	 * @return the parsed and validated manifest
	 * @throws IOException if the manifest is invalid
	 */
	private BatchManifest readBatchManifest(Path manifestFile) throws IOException
	{
		return completedBatchManifest.read(manifestFile);
	}

	/**
	 * Reads a manifest with a sealed, contiguous output prefix without requiring its suffix to be decided.
	 *
	 * <p>
	 * Prefix import reuses only mechanically verified patches and tree witnesses that occur before the first unresolved
	 * output. A newer source may extend the manifest without forcing the reviewer to recreate that sealed prefix.
	 *
	 * @param manifestFile the v4 batch manifest that supplies the sealed prefix
	 * @return the parsed and structurally validated manifest
	 * @throws IOException if the manifest is invalid
	 */
	private BatchManifest readPreparedBatchManifest(Path manifestFile) throws IOException
	{
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		sealedOutputPrefixLength(manifest);
		return manifest;
	}

	/**
	 * Validates the contiguous retained-or-discarded prefix that can be imported into an extended review.
	 *
	 * @param manifest the manifest whose prefix is being imported
	 * @return the number of sealed output positions
	 * @throws IOException if the prefix is missing, gapped, or mechanically inconsistent
	 */
	private int sealedOutputPrefixLength(BatchManifest manifest) throws IOException
	{
		return sealedPrefixValidation.length(manifest);
	}

	/**
	 * Reads the immutable facts from a version-5 manifest before reviewer-authored batches have been added.
	 *
	 * @param manifestFile the manifest file
	 * @return the parsed version-5 manifest
	 * @throws IOException if the file does not contain a version-5 manifest with source snapshots
	 */
	private BatchManifest readUnfinalizedBatchManifest(Path manifestFile) throws IOException
	{
		return manifests.readUnfinalized(manifestFile);
	}

	/**
	 * Creates one temporary directory below the CLI-owned temporary root.
	 *
	 * @param manifestFile the manifest that owns the current invocation
	 * @param prefix the directory-name prefix
	 * @return the new temporary directory
	 * @throws IOException if the manifest is outside the temporary root or the directory cannot be created
	 */
	private Path createTemporaryDirectory(Path manifestFile, String prefix) throws IOException
	{
		return manifests.createTemporaryDirectory(manifestFile, prefix);
	}



	/**
	 * Verifies that each reviewer-authored source batch sequence reconstructs its original source commit without
	 * modifying the caller's Git index.
	 *
	 * @param manifestFile the v4 replay manifest
	 * @throws IOException if a reconstruction is incomplete, cannot be applied, or produces a different source tree
	 */
	public void verifySourceReconstructions(Path manifestFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		replayPreflight.verifySourceReconstructions(manifestFile, manifest);
	}

	/**
	 * Verifies a complete frozen replay plan before it is allowed to rewrite Git history.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @throws IOException if any source, batch, output witness, or final tree is inconsistent
	 */
	public void preflight(Path manifestFile) throws IOException
	{
		completion.preflight(manifestFile);
	}

	/**
	 * Writes the user-selectable entry points exposed by the captured range.
	 *
	 * <p>
	 * This is immutable Git-derived evidence. Reviewers use it to ensure that each selectable behavior is represented
	 * by a candidate rather than inferring a family from its packaging.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @throws IOException if the manifest or its captured blobs cannot be read
	 */
	public void writeExposureInventory(Path manifestFile) throws IOException
	{
		writeExposureInventory(manifestFile, System.out);
	}

	/**
	 * Writes the user-selectable entry points exposed by the captured range.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param output the destination for the JSON inventory
	 * @throws IOException if the manifest or its captured blobs cannot be read
	 */
	void writeExposureInventory(Path manifestFile, OutputStream output) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(output, "output").isNotNull();
		json.writerWithDefaultPrettyPrinter().writeValue(output, semanticEvidence.exposedEntryPoints(
			readUnfinalizedBatchManifest(manifestFile)));
	}

	/**
	 * Writes the only final topic list that this workflow exposes.
	 *
	 * @param manifestFile the sealed version-5 replay manifest
	 * @throws IOException if the plan is not complete and preflighted
	 */
	public void writeFinalTopics(Path manifestFile) throws IOException
	{
		preflight(manifestFile);
		BatchManifest manifest = readBatchManifest(manifestFile);
		Map<String, PlannedOutput> plan = manifest.outputPlan().outputs().stream().collect(Collectors.toMap(
			PlannedOutput::outputId, Function.identity()));
		List<TopicOutputRequest> topics = manifest.outputs().stream().filter(output -> plan.get(output.id()).disposition().
			equals("retained")).map(output -> new TopicOutputRequest(output.id(), output.subject())).toList();
		json.writerWithDefaultPrettyPrinter().writeValue(System.out, topics);
	}

	/**
	 * Writes the exact-path predecessor candidates that require an origin review.
	 *
	 * <p>
	 * This command reports Git facts only. Reviewers decide whether another artifact is an equivalent predecessor and
	 * which dependency-complete output receives a correction.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @throws IOException if the manifest cannot be read or the candidates cannot be written
	 */
	public void writeOriginCandidates(Path manifestFile) throws IOException
	{
		writeOriginCandidates(manifestFile, System.out);
	}

	/**
	 * Writes immutable predecessor facts for origin-placement decisions.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param output the destination for the JSON candidate list
	 * @throws IOException if the manifest cannot be read or the candidates cannot be written
	 */
	public void writeOriginCandidates(Path manifestFile, OutputStream output) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(output, "output").isNotNull();
		originReviewReports.writeCandidates(readUnfinalizedBatchManifest(manifestFile), output);
	}

	/**
	 * Writes immutable origin-review facts with each predecessor's unambiguous logical output, when one exists.
	 *
	 * <p>
	 * Reviewers use this scaffold to decide semantic equivalence and placement. They must not recreate the Git-derived
	 * path or predecessor facts in their decision artifact. A predecessor output is present only when a default output or
	 * completed whole-source review identifies one unambiguously.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @throws IOException if the manifest cannot be read or the scaffold cannot be written
	 */
	public void writeOriginReviewScaffold(Path manifestFile) throws IOException
	{
		writeOriginReviewScaffold(manifestFile, System.out);
	}

	/**
	 * Writes immutable origin-review facts with each predecessor's unambiguous logical output, when one exists.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param output the destination for the JSON scaffold
	 * @throws IOException if the manifest cannot be read or the scaffold cannot be written
	 */
	public void writeOriginReviewScaffold(Path manifestFile, OutputStream output) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(output, "output").isNotNull();
		originReviewReports.writeScaffold(readUnfinalizedBatchManifest(manifestFile), output);
	}

	/**
	 * Writes every recorded origin decision beside the immutable facts it resolves.
	 *
	 * <p>
	 * This is a residual-coverage report for reviewers. It reports only exact-path facts and declared decisions; it
	 * does not infer whether a deletion, addition, or rename is semantically related.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @throws IOException if the manifest or recorded decisions cannot be read
	 */
	public void writeOriginReviewAudit(Path manifestFile) throws IOException
	{
		writeOriginReviewAudit(manifestFile, System.out);
	}

	/**
	 * Writes every recorded origin decision beside the immutable facts it resolves.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param output the destination for the JSON audit
	 * @throws IOException if the manifest or recorded decisions cannot be read
	 */
	public void writeOriginReviewAudit(Path manifestFile, OutputStream output) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(output, "output").isNotNull();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		originReviewReports.writeAudit(manifestFile, manifest, output);
	}

	/**
	 * Writes raw additions, deletions, and Git-reported rename records for semantic review.
	 *
	 * <p>
	 * Git rename detection is heuristic. These facts identify candidates for review but do not assert that a rename,
	 * deletion, or addition represents the same semantic unit.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @throws IOException if the manifest cannot be read or Git cannot inspect a source transition
	 */
	public void writePathTransitionFacts(Path manifestFile) throws IOException
	{
		writePathTransitionFacts(manifestFile, System.out);
	}

	/**
	 * Writes raw additions, deletions, and Git-reported rename records for semantic review.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param output the destination for the JSON facts
	 * @throws IOException if the manifest cannot be read or Git cannot inspect a source transition
	 */
	void writePathTransitionFacts(Path manifestFile, OutputStream output) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(output, "output").isNotNull();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		Set<String> capturedSourceHashes = manifest.sources().stream().map(SourceSnapshot::hash).
			collect(Collectors.toSet());
		Set<String> sourcesToReview = sourceReviewValidation.sourcesRequiringReview(manifest, capturedSourceHashes);
		List<PathTransitionFact> facts = pathTransitionFacts.read(manifest);
		json.writerWithDefaultPrettyPrinter().writeValue(output, facts.stream().filter(fact ->
			sourcesToReview.contains(fact.source())).toList());
	}

	/**
	 * Records and validates reviewer decisions for raw deletions and Git-reported renames.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param decisionFile the reviewer-authored transition-decision JSON file
	 * @throws IOException if the facts or decisions are incomplete or invalid
	 */
	public void recordPathTransitionDecisions(Path manifestFile, Path decisionFile) throws IOException
	{
		recordPathTransitionDecisionBatches(manifestFile, List.of(decisionFile));
	}

	/**
	 * Atomically merges disjoint path-transition decision files prepared by independent reviewers.
	 *
	 * @param manifestFile the replay manifest
	 * @param decisionFiles non-empty manifest-local reviewer files
	 * @throws IOException if a file, decision, or merge is invalid
	 */
	public void recordPathTransitionDecisionBatches(Path manifestFile, List<Path> decisionFiles) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(decisionFiles, "decisionFiles").isNotEmpty();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		pathTransitionRecording.record(manifestFile, manifest, pathTransitionFacts.read(manifest), decisionFiles);
	}

	/**
	 * Adds reviewer-selected destinations for changed existing paths with earlier exact-path predecessors.
	 * <p>
	 * Each invocation validates the supplied decisions and preserves previously recorded decisions.
	 * {@link #preflight(Path)} verifies complete coverage before replay begins.
	 *
	 * @param manifestFile the topic-replay manifest
	 * @param reviewFile the reviewer-authored origin-review JSON file
	 * @throws IOException if a review is invalid, duplicates an earlier decision, or cannot be recorded
	 */
	public void recordOriginReviews(Path manifestFile, Path reviewFile) throws IOException
	{
		recordOriginReviewBatches(manifestFile, List.of(reviewFile));
	}

	/**
	 * Atomically records disjoint reviewer-authored origin-review files.
	 *
	 * <p>
	 * Reviewers may prepare the files concurrently, but this method is the sole manifest writer. It validates the
	 * combined evidence before changing the manifest, so overlapping or invalid files cannot leave partial shared state.
	 *
	 * @param manifestFile the version-5 replay manifest
	 * @param reviewFiles the non-empty manifest-local reviewer files
	 * @throws IOException if a file is unsafe, duplicate, invalid, or cannot be recorded atomically
	 */
	public void recordOriginReviewBatches(Path manifestFile, List<Path> reviewFiles) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(reviewFiles, "reviewFiles").isNotEmpty();
		BatchManifest manifest = readUnfinalizedBatchManifest(manifestFile);
		originReviewRecording.record(manifestFile, manifest, originReviewFacts.candidates(manifest),
			originReviewFacts.sourceReviews(manifest),
			reviewFiles);
	}

	/**
	 * Creates and records a recovery branch for one frozen replay plan.
	 *
	 * @param manifestFile the preflighted replay manifest
	 * @throws IOException if the plan is incomplete, Git state is unsafe, or Git cannot create the backup
	 */
	public void createBackup(Path manifestFile) throws IOException
	{
		String backup = completion.createBackup(manifestFile);
		System.out.println("backup=" + backup);
	}

	/**
	 * Verifies a successful replay and deletes exactly its recorded backup branch.
	 *
	 * @param manifestFile the replay manifest
	 * @param rewrittenTip the successful replay's final commit
	 * @throws IOException if verification fails or the recorded backup cannot be removed
	 */
	public void finalizeBackup(Path manifestFile, String rewrittenTip) throws IOException
	{
		String backup = completion.finalizeBackup(manifestFile, rewrittenTip);
		System.out.println("deletedBackup=" + backup);
	}

	/**
	 * Runs one preflighted topic replay without changing the checked-out history until every output is verified.
	 *
	 * <p>
	 * The replay applies the reviewed patch batches to an isolated Git index, creates the replacement commits with Git
	 * plumbing, and atomically moves the current branch only after the final tree and every output witness match. This
	 * avoids interactive-rebase {@code exec} ordering and leaves the checked-out branch unchanged if the process stops
	 * before completion.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @throws IOException if the plan is unsafe, an output cannot be created, or the current branch cannot be advanced
	 */
	public void replay(Path manifestFile) throws IOException
	{
		String rewrittenTip = completion.replay(manifestFile);
		System.out.println("rewrittenTip=" + rewrittenTip);
	}

	/**
	 * Verifies a successful replay at {@code HEAD} and deletes only its manifest-recorded backup branch.
	 *
	 * @param manifestFile the sealed replay manifest
	 * @throws IOException if the rewritten history is invalid or its backup cannot be removed
	 */
	public void finalizeReplay(Path manifestFile) throws IOException
	{
		String rewrittenTip = gitLines("rev-parse", "HEAD").getFirst();
		completion.finalizeReplay(manifestFile, rewrittenTip);
	}

	/**
	 * Verifies a completed topic replay against its immutable finalization receipt.
	 *
	 * @param manifestFile the replay manifest
	 * @param rebaseBase the expected commit immediately before the replay range
	 * @param rewrittenTip the expected final replacement commit
	 * @throws IOException if the receipt is absent, stale, or does not bind the requested replacement chain
	 */
	public void verifyFinalization(Path manifestFile, String rebaseBase, String rewrittenTip) throws IOException
	{
		completion.verifyFinalization(manifestFile, rebaseBase, rewrittenTip);
	}

	/**
	 * Runs one reviewer-selected quality gate and preserves its complete output beside the replay manifest.
	 *
	 * @param manifestFile the replay manifest that owns the gate evidence
	 * @param gateName the stable name used for the gate's output file
	 * @param command the exact command and arguments selected for the gate
	 * @throws IOException if the gate name is unsafe, the command cannot run, or the gate fails
	 */
	public void runGate(Path manifestFile, String gateName, List<String> command) throws IOException
	{
		gates.run(manifestFile, gateName, command);
	}

	/**
	 * Runs one reviewer-selected quality gate in an isolated worktree for every retained replay output.
	 *
	 * <p>
	 * The gate proves that every reconstructed feature commit is independently buildable and testable. A failed
	 * worktree is retained for diagnosis; successful worktrees are removed immediately.
	 *
	 * @param manifestFile the finalized replay manifest that owns the output witnesses
	 * @param gateName the stable name used for the output-specific log files
	 * @param command the exact build or test command to run in every output worktree
	 * @throws IOException if the manifest is incomplete, an output cannot be resolved, or one gate fails
	 */
	public void runOutputGate(Path manifestFile, String gateName, List<String> command) throws IOException
	{
		runOutputGate(manifestFile, gateName, null, command);
	}

	/**
	 * Runs one reviewer-selected quality gate in isolated worktrees for the selected retained replay outputs.
	 *
	 * @param manifestFile the finalized replay manifest that owns the output witnesses
	 * @param gateName the stable name used for the output-specific log files
	 * @param outputId the one output to gate, or {@code null} for every retained output
	 * @param command the exact build or test command to run in every output worktree
	 * @throws IOException if the manifest is incomplete, an output cannot be resolved, or one gate fails
	 */
	void runOutputGate(Path manifestFile, String gateName, String outputId, List<String> command) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(gateName, "gateName").isNotBlank();
		requireThat(command, "command").isNotEmpty();
		if (outputId != null)
			requireThat(outputId, "outputId").isNotBlank();
		BatchManifest manifest = readBatchManifest(manifestFile);
		outputGates.run(manifestFile, gateName, selectOutputTargets(manifest.outputWitnesses(), outputId, command));
	}

	/**
	 * Selects chronological witnessed output trees for one command.
	 *
	 * @param witnesses the sealed output transitions
	 * @param outputId one selected output, or {@code null} for every retained output
	 * @param command the command assigned to each selected tree
	 * @return the selected output commands in witnessed order
	 * @throws IOException if the selected output has no retained witness
	 */
	public static List<OutputGates.Target> selectOutputTargets(List<OutputWitness> witnesses, String outputId,
		List<String> command) throws IOException
	{
		if (outputId != null)
		{
			witnesses = witnesses.stream().filter(witness -> witness.outputId().equals(outputId)).toList();
			if (witnesses.isEmpty())
			{
				throw new IOException("Topic-replay output gate cannot find retained output " + outputId +
					" in the sealed manifest");
			}
		}
		return witnesses.stream().map(witness ->
			new OutputGates.Target(witness.outputId(), witness.afterTree(), command)).toList();
	}

	/**
	 * Runs each topic's reviewer-declared behavior acceptance command in its sealed output worktree.
	 *
	 * @param manifestFile the replay manifest that owns the sealed output witnesses
	 * @param outputId the one output to verify, or {@code null} for every retained output
	 * @throws IOException if the plan omits an acceptance command or a declared command fails
	 */
	void runDeclaredOutputAcceptance(Path manifestFile, String outputId) throws IOException
	{
		declaredOutputAcceptance.run(manifestFile, outputId);
	}

	/**
	 * Runs Git and returns its standard-output lines.
	 *
	 * @param arguments the Git subcommand and arguments
	 * @return Git's standard-output lines
	 * @throws IOException if Git cannot run or rejects the request
	 */
	private List<String> gitLines(String... arguments) throws IOException
	{
		return git.lines(arguments);
	}
}

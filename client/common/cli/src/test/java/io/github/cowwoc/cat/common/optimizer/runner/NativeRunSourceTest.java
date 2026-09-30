package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import org.testng.Assert;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks native source authority and resource ownership without unrelated campaign or runner setup. */
public final class NativeRunSourceTest
{
	/** Creates a test instance. */
	public NativeRunSourceTest()
	{
	}

	/**
	 * An active project cannot enter the isolated-source boundary or create temporary source state.
	 *
	 * @throws IOException if the test directory cannot be created
	 */
	@Test
	public void rejectsActiveProjectSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-active-native-source-"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Path temporary = root.resolve("temporary");
			NestedRunRequest request = new NestedRunRequest("active", EvidencePurpose.DEVELOPMENT,
				new ProjectRunContext(new ProjectExecutionSnapshot(root.resolve("project"),
					new EvidenceRef("snapshot-manifests", "a".repeat(64))), ProjectWorkspaceMode.ACTIVE_WORKTREE,
					Optional.of(new EvidenceRef("confirmed-editable-roots", "b".repeat(64))),
					Optional.of(new EvidenceRef("live-checkpoints", "c".repeat(64)))), List.of("edit"),
				SessionMode.SINGLE_TURN, new ModelProfile("model", Optional.empty(), Map.of()), List.of());
			Assert.expectThrows(IOException.class, () -> NativeRunSource.prepare(request, store, temporary));
			Assert.assertFalse(Files.exists(temporary));
		}
	}

	/**
	 * A sterile evaluator uses exact retained input in a temporary source without installing CAT.
	 *
	 * @throws IOException if fixture or source materialization fails
	 * @throws NoSuchAlgorithmException if the test cannot hash its native turn
	 */
	@Test
	public void preparesSterileSourceAndCleansIt() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-sterile-source-"));
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			byte[] authority = "frozen evaluator".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			EvidenceRef authorityRef = store.appendImmutableBytes("sterile-inputs", authority);
			String turn = "judge retained trial";
			String turnHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				turn.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			SterileWorkspaceManifest manifest = new SterileWorkspaceManifest(1,
				SterileWorkspaceKind.EVALUATOR, new EvidenceRef("evaluator-requests", "b".repeat(64)),
				List.of(turnHash), List.of(new SterileWorkspaceEntry(Path.of("authority/evaluator.txt"),
					SterileWorkspaceInputRole.AUTHORITY, authorityRef, authorityRef.evidenceId())));
			EvidenceRef manifestRef = store.appendImmutableBytes("sterile-workspace-manifests", manifest.bytes());
			NestedRunRequest evaluator = new NestedRunRequest("evaluator", EvidencePurpose.DEVELOPMENT,
				new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR, manifestRef), List.of(turn),
				SessionMode.SINGLE_TURN, new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()),
				List.of());
			Path materialized;
			try (NativeRunSource source = NativeRunSource.prepare(evaluator, store, temporary))
			{
				materialized = source.project();
				requireThat(source.requiresCatInstallation(), "sterilePluginInstallation").isFalse();
				requireThat(materialized.startsWith(temporary), "ownedSource").isTrue();
				requireThat(Files.readAllBytes(materialized.resolve("authority/evaluator.txt")),
					"exactEvaluatorAuthority").isEqualTo(authority);
			}
			requireThat(Files.exists(materialized), "cleanedSource").isFalse();
			Path project = Files.createDirectory(root.resolve("project"));
			NestedRunRequest trial = new NestedRunRequest("trial", EvidencePurpose.DEVELOPMENT,
				new ProjectRunContext(new ProjectExecutionSnapshot(project,
					new EvidenceRef("snapshots", "a".repeat(64))), ProjectWorkspaceMode.ISOLATED_COPY,
					Optional.empty(), Optional.empty()), List.of("trial"), SessionMode.SINGLE_TURN,
				new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()), List.of());
			try (NativeRunSource source = NativeRunSource.prepare(trial, store, temporary))
			{
				requireThat(source.project(), "trialProject").isEqualTo(project);
				requireThat(source.requiresCatInstallation(), "trialPluginInstallation").isTrue();
			}
			requireThat(Files.isDirectory(project), "retainedTrialSource").isTrue();
			Path privateProject = Files.createDirectory(root.resolve("private"));
			PrivateGenerationContext generationContext = new PrivateGenerationContext(project, privateProject,
				new EvidenceRef("candidate-generation-requests", "d".repeat(64)),
				new EvidenceRef("generation-attempt-readiness", "e".repeat(64)),
				new EvidenceRef("confirmed-editable-roots", "f".repeat(64)));
			NestedRunRequest generation = new NestedRunRequest("generation", EvidencePurpose.DEVELOPMENT,
				generationContext, List.of("edit confirmed paths"), SessionMode.SINGLE_TURN,
				trial.modelProfile(), List.of());
			try (NativeRunSource source = NativeRunSource.prepare(generation, store, temporary))
			{
				requireThat(source.project(), "privateGenerationProject").isEqualTo(privateProject);
				requireThat(source.requiresCatInstallation(), "generationPluginInstallation").isTrue();
			}
			requireThat(privateProject, "retainedPrivateWorktree").isDirectory();
			NestedRunRequest missing = new NestedRunRequest("missing", EvidencePurpose.DEVELOPMENT,
				new SterileWorkspaceContext(SterileWorkspaceKind.EVALUATOR,
					new EvidenceRef("sterile-workspace-manifests", "c".repeat(64))), List.of(turn),
				SessionMode.SINGLE_TURN, new ModelProfile("gpt-6-sol", Optional.empty(), Map.of()),
				List.of());
			try
			{
				NativeRunSource.prepare(missing, store, temporary);
				throw new AssertionError("Missing evaluator authority was materialized");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingManifest").contains("missing");
			}
			try (Stream<Path> entries = Files.list(temporary))
			{
				requireThat(entries.count(), "noPartialSource").isEqualTo(0L);
			}
		}
	}
}

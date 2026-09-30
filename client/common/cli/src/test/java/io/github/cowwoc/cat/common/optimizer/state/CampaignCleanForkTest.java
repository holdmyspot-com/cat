package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks new-campaign Git admission before campaign state is published. */
public final class CampaignCleanForkTest
{
	/** Creates clean-fork tests. */
	public CampaignCleanForkTest()
	{
	}

	/**
	 * A clean fork retains nested tracked paths and rejects an extra file even when Git ignores it.
	 *
	 * @throws IOException if fixture Git or worktree verification fails
	 */
	@Test
	public void rejectsIgnoredPathInCleanFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("clean-fork-ignored-path-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			git(base, "config", "user.email", "test@example.invalid");
			git(base, "config", "user.name", "Fork Test");
			Files.writeString(base.resolve(".gitignore"), "ignored.md\n");
			Files.writeString(Files.createDirectory(base.resolve("source")).resolve("target.md"), "original\n");
			git(base, "add", ".");
			git(base, "commit", "-m", "initial");
			Path workflow = Files.createDirectory(root.resolve("workflow"));
			VerifiedCleanFork.Identity identity = VerifiedCleanFork.preflight(
				new CampaignLookupKey(base, Path.of("source", "target.md")), workflow.resolve("campaign-clean"));
			VerifiedCleanFork.ensure(identity);
			requireThat(Files.readString(identity.optimizationRoot().resolve("source/target.md")),
				"retainedNestedSource").isEqualTo("original\n");

			Files.writeString(identity.optimizationRoot().resolve("ignored.md"), "unowned\n");
			try
			{
				VerifiedCleanFork.ensure(identity);
				throw new AssertionError("Clean fork must reject ignored unowned paths");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unownedPathFailure").contains("unowned paths");
			}
		}
	}

	/**
	 * Staged, unstaged, and untracked edits leave no campaign and tell the user how to retry.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsDirtyBaseBeforeRegistration() throws IOException
	{
		for (String kind : List.of("staged", "unstaged", "untracked"))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory("clean-fork-admission-")).toRealPath();
				Path base = Files.createDirectory(root.resolve("base"));
				git(base, "init");
				git(base, "config", "user.email", "test@example.invalid");
				git(base, "config", "user.name", "Fork Test");
				Files.writeString(base.resolve("target.md"), "original\n");
				git(base, "add", ".");
				git(base, "commit", "-m", "initial");
				if (kind.equals("untracked"))
					Files.writeString(base.resolve("new.md"), "untracked\n");
				else
				{
					Files.writeString(base.resolve("target.md"), kind + "\n");
					if (kind.equals("staged"))
						git(base, "add", "target.md");
				}

				Path workflow = Files.createDirectory(root.resolve("workflow"));
				CampaignLocator locator = new CampaignLocator(workflow);
				CampaignLookupKey key = new CampaignLookupKey(base, Path.of("target.md"));
				try
				{
					locator.createOrFind(key, new CampaignCreationRequest("dirty",
						ProspectiveCampaignState.profile()));
					throw new AssertionError("Dirty source must be rejected");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "retryInstruction").contains("retry");
				}
				requireThat(Files.notExists(workflow.resolve("campaign-dirty")), "noCampaign").isTrue();
			}
		}
	}

	/**
	 * Source capture after a base edit still uses the source committed at clean campaign start.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 */
	@Test
	public void capturesCleanForkAfterBaseChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("clean-fork-source-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			git(base, "config", "user.email", "test@example.invalid");
			git(base, "config", "user.name", "Fork Test");
			Files.writeString(base.resolve("target.md"), "original\n");
			Path external = Files.writeString(root.resolve("external.md"), "outside original\n");
			Files.createSymbolicLink(base.resolve("linked.md"), external);
			Files.setPosixFilePermissions(base.resolve("target.md"), Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
			git(base, "add", ".");
			git(base, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			CampaignLookupKey key = new CampaignLookupKey(base, Path.of("target.md"));
			CampaignHandle handle = locator.createOrFind(key, new CampaignCreationRequest("clean",
				ProspectiveCampaignState.profile())).handle();
			Files.writeString(base.resolve("target.md"), "later\n");
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			InitialTargetCaptureService sources = new InitialTargetCaptureService(new CampaignLocator(workflow), store);
			EvidenceRef captureRef = sources.capture(store.binding());
			InitialTargetCapture captured = sources.load(store.binding(), captureRef);
			LiveGitInventory regularInventory = SnapshotJson.mapper().readValue(store.readImmutableJson(
				captured.inventoryRef()), LiveGitInventory.class);
			requireThat(regularInventory.paths().stream().filter(entry -> entry.path().equals(Path.of("target.md"))).
				findFirst().orElseThrow().kind(), "retainedRegularKind").isEqualTo(
				LiveGitInventory.Entry.Kind.REGULAR);
			JsonNode regularCapture = SnapshotJson.mapper().readTree(store.readImmutableJson(captureRef));
			requireThat(regularCapture.path("resolvedSourcePath").isString(), "regularRelationshipRetained").
				isTrue();
			requireThat(regularCapture.path("resolvedSourcePath").stringValue(), "regularResolvedSource").isEqualTo(
				"target.md");
			requireThat(regularCapture.path("selectedLinkTarget").stringValue(), "regularLinkTarget").isEmpty();
			Path fork = locator.optimizationRoot(store.binding());
			requireThat(fork.equals(base), "separateOptimizationRoot").isFalse();
			requireThat(Files.isSymbolicLink(fork.resolve("linked.md")), "forkedLink").isTrue();
			requireThat(Files.readSymbolicLink(fork.resolve("linked.md")), "linkedTarget").isEqualTo(external);
			requireThat(Files.isExecutable(fork.resolve("target.md")), "forkedMode").isTrue();
			requireThat(Files.getPosixFilePermissions(fork.resolve("target.md")), "exactForkMode").isEqualTo(
				Files.getPosixFilePermissions(base.resolve("target.md")));
			Path originalProject = sources.materializeProjectSnapshot(store.binding(), captureRef);
			requireThat(Files.isSymbolicLink(originalProject.resolve("linked.md")),
				"inspectionLink").isTrue();
			requireThat(Files.readSymbolicLink(originalProject.resolve("linked.md")),
				"inspectionLinkedTarget").isEqualTo(external);
			requireThat(Files.isExecutable(originalProject.resolve("target.md")), "inspectionMode").isTrue();
			LiveGitInventory inventory = SnapshotJson.mapper().readValue(store.readImmutableJson(
				captured.inventoryRef()), LiveGitInventory.class);
			requireThat(inventory.paths().stream().anyMatch(entry -> entry.path().equals(Path.of("linked.md")) &&
				entry.kind() == LiveGitInventory.Entry.Kind.SYMLINK), "linkedInventory").isTrue();
			String original = new String(new PromptSnapshotRepository(store.directory()).load(
				captured.snapshotId()).entries().get(Path.of("target.md")).bytes(), StandardCharsets.UTF_8);
			requireThat(original, "forkedSource").isEqualTo("original\n");
			requireThat(Files.readString(base.resolve("target.md")), "liveBase").isEqualTo("later\n");
		}
	}

	/**
	 * A selected in-project link resolves to the original file in the pinned fork and inspection copy.
	 *
	 * @throws IOException if fixture Git or source capture fails
	 */
	@Test
	public void capturesInProjectLinkedTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("clean-fork-linked-target-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			git(base, "config", "user.email", "test@example.invalid");
			git(base, "config", "user.name", "Fork Test");
			Files.writeString(base.resolve("actual.md"), "original\n");
			Files.createSymbolicLink(base.resolve("target.md"), Path.of("actual.md"));
			git(base, "add", ".");
			git(base, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			CampaignLookupKey key = new CampaignLookupKey(base, Path.of("target.md"));
			CampaignHandle handle = locator.createOrFind(key, new CampaignCreationRequest("linked",
				ProspectiveCampaignState.profile())).handle();
			Files.writeString(base.resolve("actual.md"), "later\n");
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			Path fork = locator.optimizationRoot(store.binding());
			LiveGitInventory observed = LiveGitInventory.capture(fork);
			LiveGitInventory.Entry link = observed.paths().stream().filter(entry -> entry.path().equals(
				Path.of("target.md"))).findFirst().orElseThrow();
			LiveGitInventory.Entry resolved = observed.paths().stream().filter(entry -> entry.path().equals(
				Path.of("actual.md"))).findFirst().orElseThrow();
			requireThat(link.kind(), "selectedLinkKind").isEqualTo(LiveGitInventory.Entry.Kind.SYMLINK);
			requireThat(link.resolvedTarget(), "selectedResolvedTarget").isEqualTo(fork.resolve("actual.md").
				toString());
			requireThat(resolved.kind(), "resolvedKind").isEqualTo(LiveGitInventory.Entry.Kind.REGULAR);
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			EvidenceRef captureRef = sources.capture(store.binding());
			CampaignStore restarted = CampaignStore.open(handle.campaignDirectory());
			InitialTargetCapture captured = new InitialTargetCaptureService(new CampaignLocator(workflow),
				restarted).load(restarted.binding(), captureRef);
			LiveGitInventory linkedInventory = SnapshotJson.mapper().readValue(restarted.readImmutableJson(
				captured.inventoryRef()), LiveGitInventory.class);
			LiveGitInventory.Entry retainedLink = linkedInventory.paths().stream().filter(entry -> entry.path().equals(
				Path.of("target.md"))).findFirst().orElseThrow();
			requireThat(retainedLink.kind(), "retainedLinkKind").isEqualTo(LiveGitInventory.Entry.Kind.SYMLINK);
			requireThat(retainedLink.linkTarget(), "retainedLinkTarget").isEqualTo("actual.md");
			requireThat(retainedLink.resolvedTarget(), "retainedResolvedTarget").isEqualTo(
				fork.resolve("actual.md").toString());
			JsonNode linkedCapture = SnapshotJson.mapper().readTree(restarted.readImmutableJson(captureRef));
			requireThat(linkedCapture.path("resolvedSourcePath").isString(), "linkedRelationshipRetained").
				isTrue();
			requireThat(linkedCapture.path("resolvedSourcePath").stringValue(), "linkedResolvedSource").isEqualTo(
				"actual.md");
			requireThat(linkedCapture.path("selectedLinkTarget").stringValue(), "linkedTargetText").isEqualTo(
				"actual.md");
			requireThat(linkedCapture.path("sourceSha256").stringValue(), "linkedSourceHash").isEqualTo(
				resolved.contentSha256());
			requireThat(Files.readString(fork.resolve("target.md")), "forkLinkedTarget").isEqualTo(
				"original\n");
			Path inspection = sources.materializeProjectSnapshot(store.binding(), captureRef);
			requireThat(Files.readString(inspection.resolve("target.md")), "inspectionLinkedTarget").isEqualTo(
				"original\n");
			String retained = new String(new PromptSnapshotRepository(store.directory()).load(
				captured.snapshotId()).entries().get(Path.of("actual.md")).bytes(), StandardCharsets.UTF_8);
			requireThat(retained, "retainedLinkedTarget").isEqualTo("original\n");
		}
	}

	/**
	 * Restart finishes the same pinned fork even when the original base changed after intent publication.
	 *
	 * @throws IOException if fixture Git or campaign recovery fails
	 */
	@Test
	public void recoversPinnedForkAfterBaseChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("clean-fork-recovery-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			git(base, "config", "user.email", "test@example.invalid");
			git(base, "config", "user.name", "Fork Test");
			Files.writeString(base.resolve("target.md"), "original\n");
			git(base, "add", ".");
			git(base, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			Path directory = workflow.resolve("campaign-restart");
			CampaignLookupKey key = new CampaignLookupKey(base, Path.of("target.md"));
			ModelProfile profile = ProspectiveCampaignState.profile();
			VerifiedCleanFork.Identity pinned = VerifiedCleanFork.preflight(key, directory);
			Files.write(workflow.resolve("campaign-fork-intent.json"), VerifiedCleanFork.encode(pinned));
			Files.write(workflow.resolve("campaign-create-intent.json"), JsonMapper.builder().build().
				writeValueAsBytes(new CreationIntentFixture(1, base.toString(), "target.md", "restart",
					directory.toString(), profile)));
			CampaignStore.initialize(directory, "restart", profile);
			Files.writeString(base.resolve("target.md"), "later\n");

			CampaignLocator restarted = new CampaignLocator(workflow);
			CampaignHandle handle = restarted.find(key).getFirst();
			CampaignBinding binding = CampaignStore.open(handle.campaignDirectory()).binding();
			requireThat(Files.readString(restarted.optimizationRoot(binding).resolve("target.md")),
				"recoveredForkSource").isEqualTo("original\n");
			requireThat(Files.readString(base.resolve("target.md")), "laterBase").isEqualTo("later\n");
			requireThat(Files.notExists(workflow.resolve("campaign-create-intent.json")), "recoveredCreateIntent").
				isTrue();
			requireThat(Files.notExists(workflow.resolve("campaign-fork-intent.json")), "recoveredForkIntent").
				isTrue();
		}
	}

	/**
	 * Restart refuses a changed pre-registration fork instead of replacing it from the later base.
	 *
	 * @throws IOException if fixture Git or campaign recovery fails
	 */
	@Test
	public void rejectsChangedForkDuringRecovery() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-fork-recovery-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			Files.writeString(base.resolve("target.md"), "original\n");
			git(base, "add", ".");
			git(base, "-c", "user.email=test@example.invalid", "-c", "user.name=Fork Test",
				"commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			Path directory = workflow.resolve("campaign-changed");
			CampaignLookupKey key = new CampaignLookupKey(base, Path.of("target.md"));
			ModelProfile profile = ProspectiveCampaignState.profile();
			VerifiedCleanFork.Identity pinned = VerifiedCleanFork.preflight(key, directory);
			Files.write(workflow.resolve("campaign-fork-intent.json"), VerifiedCleanFork.encode(pinned));
			Files.write(workflow.resolve("campaign-create-intent.json"), JsonMapper.builder().build().
				writeValueAsBytes(new CreationIntentFixture(1, base.toString(), "target.md", "changed",
					directory.toString(), profile)));
			CampaignStore.initialize(directory, "changed", profile);
			VerifiedCleanFork.publish(pinned, directory);
			VerifiedCleanFork.ensure(pinned);
			Files.writeString(pinned.optimizationRoot().resolve("target.md"), "outside edit\n");
			Files.writeString(base.resolve("target.md"), "later base edit\n");

			try
			{
				new CampaignLocator(workflow).find(key);
				throw new AssertionError("Changed fork must not be reconstructed");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedFork").contains("conflicts");
			}
			requireThat(Files.readString(pinned.optimizationRoot().resolve("target.md")),
				"preservedOutsideEdit").isEqualTo("outside edit\n");
			requireThat(Files.exists(workflow.resolve("campaign-create-intent.json")), "pendingRecovery").isTrue();
		}
	}

	/**
	 * A mode-only change is visible in the retained-source inventory before a candidate can be admitted.
	 *
	 * @throws IOException if fixture Git or inventory capture fails
	 */
	@Test
	public void inventoryDetectsModeOnlyChange() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("mode-inventory-")).toRealPath();
			git(project, "init");
			Path target = Files.writeString(project.resolve("target.md"), "same bytes\n");
			git(project, "add", ".");
			git(project, "-c", "user.email=test@example.invalid", "-c", "user.name=Fork Test",
				"commit", "-m", "initial");
			LiveGitInventory before = LiveGitInventory.capture(project);
			Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
			LiveGitInventory after = LiveGitInventory.capture(project);
			requireThat(after.equals(before), "modeChangeDetected").isFalse();
		}
	}

	/**
	 * A relative link to a sibling source resolves to the same external file in the optimization fork.
	 *
	 * @throws IOException if fixture Git or fork verification fails
	 */
	@Test
	public void preservesRelativeExternalLinkTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("relative-link-fork-")).toRealPath();
			Path external = Files.writeString(root.resolve("external.md"), "outside\n");
			Path base = Files.createDirectory(root.resolve("base"));
			git(base, "init");
			Files.writeString(base.resolve("target.md"), "inside\n");
			Files.createSymbolicLink(base.resolve("linked.md"), Path.of("../external.md"));
			git(base, "add", ".");
			git(base, "-c", "user.email=test@example.invalid", "-c", "user.name=Fork Test",
				"commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			CampaignHandle handle = locator.createOrFind(new CampaignLookupKey(base, Path.of("target.md")),
				new CampaignCreationRequest("relative", ProspectiveCampaignState.profile())).handle();
			Path fork = locator.optimizationRoot(CampaignStore.open(handle.campaignDirectory()).binding());
			requireThat(fork.resolve("linked.md").toRealPath(), "resolvedForkTarget").isEqualTo(external);
		}
	}

	/**
	 * Runs one fixture Git operation.
	 *
	 * @param directory fixture worktree
	 * @param arguments Git arguments
	 * @throws IOException if Git fails
	 */
	private static void git(Path directory, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(directory.toFile()).
			redirectErrorStream(true).start())
		{
			byte[] output = process.getInputStream().readAllBytes();
			try
			{
				if (process.waitFor() != 0)
					throw new IOException("Git fixture failed: " + new String(output, StandardCharsets.UTF_8));
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Git fixture interrupted", e);
			}
		}
	}

	private record CreationIntentFixture(int schemaVersion, String projectRoot, String targetPath,
		String campaignId, String campaignDirectory, ModelProfile optimizationProfile)
	{
	}
}

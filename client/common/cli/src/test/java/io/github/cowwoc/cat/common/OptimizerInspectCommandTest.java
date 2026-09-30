package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignCreationRequest;
import io.github.cowwoc.cat.common.optimizer.state.CallSlot;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLookupKey;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStopInbox;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.InspectionCallCoordinator;
import io.github.cowwoc.cat.common.optimizer.state.SchedulerLease;
import io.github.cowwoc.cat.common.optimizer.state.StartedInspectionCall;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the inspection command's host authority before its native call.
 */
public final class OptimizerInspectCommandTest
{
	/**
	 * Creates the inspection command fixture.
	 */
	public OptimizerInspectCommandTest()
	{
	}

	/**
	 * The public inspection operation rejects each missing collaborator before processing command arguments.
	 *
	 * @throws IOException if test-owned paths cannot be prepared
	 */
	@Test
	public void rejectsNullCollaboratorsBeforeProcessing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-inspect-null-"));
			Path artifact = root.resolve("artifact");
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			BiFunction<CampaignStore, CampaignBinding, NestedAgent> agentFactory = (store, binding) ->
			{
				throw new AssertionError("Agent factory must not be invoked");
			};

			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(null,
				root, artifact, manager, agentFactory, output));
			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(new String[0],
				null, artifact, manager, agentFactory, output));
			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(new String[0],
				root, null, manager, agentFactory, output));
			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(new String[0],
				root, artifact, null, agentFactory, output));
			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(new String[0],
				root, artifact, manager, null, output));
			Assert.expectThrows(NullPointerException.class, () -> OptimizerInspectCommand.run(new String[0],
				root, artifact, manager, agentFactory, null));

			requireThat(Files.exists(root.resolve(".cat")), "noCatDirectory").isFalse();
			requireThat(output.size(), "emptyOutput").isEqualTo(0);
		}
	}

	/**
	 * A direct command without the current host turn cannot create a campaign.
	 *
	 * @throws IOException if fixture setup fails
	 */
	@Test
	public void rejectsMissingHostTurnBeforeCreation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-inspect-host-").toRealPath());
			Path artifact = Files.createDirectory(project.resolve("artifact"));
			HarnessRunManager manager = new HarnessRunManager(project.resolve("runs"), Clock.systemUTC());
			try
			{
				OptimizerInspectCommand.run(new String[]{"--target", "prompt.md"}, project, artifact,
					manager, (store, binding) -> new NestedAgent(new RejectingAdapter(new AtomicInteger()),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Missing host identity must be rejected");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "missingHostTurn").contains("--host-session-id");
			}
			requireThat(Files.exists(project.resolve(".cat/work/optimizer")), "noCampaignFiles").isFalse();
		}
	}

	/**
	 * An unsupported campaign action is rejected before creating an optimizer campaign.
	 *
	 * @throws IOException if fixture setup fails
	 */
	@Test
	public void rejectsUnknownActionBeforeCreation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-inspect-action-").toRealPath());
			Path artifact = Files.createDirectory(project.resolve("artifact"));
			HarnessRunManager manager = new HarnessRunManager(project.resolve("runs"), Clock.systemUTC());
			try
			{
				OptimizerInspectCommand.run(new String[]{"--action", "retry",
					"--host-session-id", "host-session", "--host-turn-id", "host-turn",
					"--host-tool-use-id", "tool-call", "--host-model-id", "test-model",
					"--target", "prompt.md"}, project, artifact, manager,
					(store, binding) -> new NestedAgent(new RejectingAdapter(new AtomicInteger()),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Unsupported action must be rejected");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "invalidAction").contains("Unsupported inspection action");
			}
			requireThat(Files.exists(project.resolve(".cat/work/optimizer")), "noCampaignFiles").isFalse();
		}
	}

	/**
	 * Requesting resume for an unowned target reports absence without creating a campaign.
	 *
	 * @throws IOException if test-owned paths cannot be prepared
	 */
	@Test
	public void resumeWithoutCampaignDoesNotCreateOne() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-inspect-empty-resume-").toRealPath());
			Files.createDirectory(project.resolve(".cat"));
			Files.writeString(project.resolve("prompt.md"), "initial prompt\n");
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			git(project, "add", "prompt.md");
			git(project, "commit", "-m", "initial");
			Path artifact = Files.createDirectory(project.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(project.resolve("runs"), Clock.systemUTC());
			try
			{
				OptimizerInspectCommand.run(new String[]{"--action", "resume",
					"--host-session-id", "host-session", "--host-turn-id", "host-turn",
					"--host-tool-use-id", "tool-call", "--host-model-id", "test-model",
					"--target", "prompt.md"}, project, artifact, manager,
					(store, binding) -> new NestedAgent(new RejectingAdapter(new AtomicInteger()),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Resume without a campaign must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingCampaign").contains("No active optimizer campaign");
			}
			requireThat(new CampaignLocator(project.resolve(".cat/work/optimizer")).find(
				new CampaignLookupKey(project, Path.of("prompt.md"))).isEmpty(),
				"noCreatedCampaign").isTrue();
		}
	}

	/**
	 * The host turn is durably registered before a native inspection attempt starts.
	 *
	 * @throws IOException if fixture setup or inspection state fails
	 */
	@Test
	public void registersHostTurnBeforeNativeStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-inspect-start-").toRealPath());
			Path project = Files.createDirectory(root.resolve("project"));
			Files.createDirectory(project.resolve(".cat"));
			Files.writeString(project.resolve("prompt.md"), "initial prompt\n");
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			git(project, "add", "prompt.md");
			git(project, "commit", "-m", "initial");
			Path artifact = Files.createDirectory(cleanup.add(Files.createTempDirectory(
				"optimizer-inspect-artifact-" ).toRealPath()).resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(project.resolve(".cat/work/temp/runs"),
				Clock.systemUTC());
			AtomicInteger nativeStarts = new AtomicInteger();
			try
			{
				OptimizerInspectCommand.run(new String[]{"--host-session-id", "host-session",
					"--host-turn-id", "host-turn", "--host-tool-use-id", "tool-call",
					"--host-model-id", "test-model", "--target", "prompt.md"}, project,
					artifact, manager, (store, binding) -> new NestedAgent(new RejectingAdapter(nativeStarts),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Controlled native start must fail");
			}
			catch (IOException expected)
			{
				if (!expected.getMessage().contains("controlled start failure"))
					throw expected;
				requireThat(expected.getMessage(), "nativeFailure").contains("controlled start failure");
			}
			requireThat(nativeStarts.get(), "nativeStarts").isEqualTo(1);
			CampaignLocator locator = new CampaignLocator(project.resolve(".cat/work/optimizer"));
			requireThat(locator.findOpenInvocationByHostTurn("host-session", "host-turn").isPresent(),
				"retainedHostInvocation").isTrue();
			requireThat(locator.find(new CampaignLookupKey(project, Path.of("prompt.md"))).size(),
				"registeredCampaigns").isEqualTo(1);
		}
	}

	/**
	 * Inspection keeps the selected link path as the campaign target while its source resolves inside the fork.
	 *
	 * @throws IOException if fixture setup or inspection state fails
	 */
	@Test
	public void registersLinkedTargetPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-inspect-linked-").toRealPath());
			Path project = Files.createDirectory(root.resolve("project"));
			Files.createDirectory(project.resolve(".cat"));
			Files.writeString(project.resolve("actual.md"), "initial prompt\n");
			Files.createSymbolicLink(project.resolve("prompt.md"), Path.of("actual.md"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			git(project, "add", "actual.md", "prompt.md");
			git(project, "commit", "-m", "initial");
			Path artifact = cleanup.add(Files.createTempDirectory("optimizer-inspect-linked-artifact-").toRealPath());
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(project.resolve(".cat/work/temp/runs"),
				Clock.systemUTC());
			AtomicInteger nativeStarts = new AtomicInteger();
			try
			{
				OptimizerInspectCommand.run(new String[]{"--host-session-id", "host-session",
					"--host-turn-id", "host-turn", "--host-tool-use-id", "tool-call",
					"--host-model-id", "test-model", "--target", "prompt.md"}, project,
					artifact, manager, (store, binding) -> new NestedAgent(new RejectingAdapter(nativeStarts),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Controlled native start must fail");
			}
			catch (IOException expected)
			{
				if (!expected.getMessage().contains("controlled start failure"))
					throw expected;
			}
			requireThat(nativeStarts.get(), "nativeStarts").isEqualTo(1);
			CampaignLocator locator = new CampaignLocator(project.resolve(".cat/work/optimizer"));
			requireThat(locator.find(new CampaignLookupKey(project, Path.of("prompt.md"))).size(),
				"selectedLinkCampaigns").isEqualTo(1);
		}
	}

	/**
	 * A dirty starting base fails before the host turn is registered or a native call begins.
	 *
	 * @throws IOException if fixture Git or command setup fails
	 */
	@Test
	public void rejectsDirtyBaseBeforeHostRegistration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-inspect-dirty-").toRealPath());
			Files.writeString(project.resolve("prompt.md"), "initial prompt\n");
			git(project, "init");
			git(project, "-c", "user.email=test@example.invalid", "-c", "user.name=Inspection Test",
				"add", "prompt.md");
			git(project, "-c", "user.email=test@example.invalid", "-c", "user.name=Inspection Test",
				"commit", "-m", "initial");
			Files.writeString(project.resolve("prompt.md"), "dirty prompt\n");
			Path artifact = cleanup.add(Files.createTempDirectory("optimizer-inspect-dirty-artifact-").toRealPath());
			HarnessRunManager manager = new HarnessRunManager(project.resolve(".cat/work/temp/runs"),
				Clock.systemUTC());
			AtomicInteger nativeStarts = new AtomicInteger();
			try
			{
				OptimizerInspectCommand.run(new String[]{"--host-session-id", "host-session",
					"--host-turn-id", "host-turn", "--host-tool-use-id", "tool-call",
					"--host-model-id", "test-model", "--target", "prompt.md"}, project,
					artifact, manager, (store, binding) -> new NestedAgent(new RejectingAdapter(nativeStarts),
						store::readImmutableJson), new ByteArrayOutputStream());
				throw new AssertionError("Dirty source must stop inspection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "cleanRetry").contains("retry");
			}
			requireThat(nativeStarts.get(), "noNativeStart").isEqualTo(0);
			CampaignLocator locator = new CampaignLocator(project.resolve(".cat/work/optimizer"));
			requireThat(locator.findOpenInvocationByHostTurn("host-session", "host-turn").isEmpty(),
				"noHostRegistration").isTrue();
			requireThat(locator.find(new CampaignLookupKey(project, Path.of("prompt.md"))).isEmpty(),
				"noCampaign").isTrue();
		}
	}

	/**
	 * A retained native failure is adopted once before the invocation closes.
	 *
	 * @throws IOException if fixture setup or command execution fails
	 */
	@Test
	public void adoptsNativeFailureBeforeClosingHostTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-inspect-complete-").toRealPath());
			Path project = Files.createDirectory(root.resolve("project"));
			Files.createDirectory(project.resolve(".cat"));
			Files.writeString(project.resolve("prompt.md"), "initial prompt\n");
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			git(project, "add", "prompt.md");
			git(project, "commit", "-m", "initial");
			Path artifact = Files.createDirectory(cleanup.add(Files.createTempDirectory(
				"optimizer-inspect-completion-artifact-").toRealPath()).resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(project.resolve(".cat/work/temp/runs"),
				Clock.systemUTC());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			OptimizerInspectCommand.run(new String[]{"--host-session-id", "host-session",
				"--host-turn-id", "host-turn", "--host-tool-use-id", "tool-call",
				"--host-model-id", "test-model", "--target", "prompt.md"}, project,
				artifact, manager, (store, binding) -> new NestedAgent(new CompletingAdapter(),
					store::readImmutableJson), output);

			CampaignLocator locator = new CampaignLocator(project.resolve(".cat/work/optimizer"));
			requireThat(output.toString(java.nio.charset.StandardCharsets.UTF_8), "commandResult").
				contains("status=FAILED\n");
			requireThat(locator.findOpenInvocationByHostTurn("host-session", "host-turn").isEmpty(),
				"closedHostInvocation").isTrue();
			CampaignStore store = CampaignStore.open(locator.find(new CampaignLookupKey(project,
				Path.of("prompt.md"))).getFirst().campaignDirectory());
			requireThat(store.load().scheduler().slots().getFirst().state(), "adoptedCall").
				isEqualTo(CallSlot.State.ADOPTED);

			AtomicInteger repeatedStarts = new AtomicInteger();
			ByteArrayOutputStream resumedOutput = new ByteArrayOutputStream();
			OptimizerInspectCommand.run(new String[]{"--action", "resume", "--host-session-id", "next-session",
				"--host-turn-id", "next-turn", "--host-tool-use-id", "next-tool-call",
				"--host-model-id", "test-model", "--target", "prompt.md"}, project,
				artifact, manager, (resumedStore, binding) -> new NestedAgent(new RejectingAdapter(repeatedStarts),
					resumedStore::readImmutableJson), resumedOutput);
			requireThat(repeatedStarts.get(), "repeatedNativeStarts").isEqualTo(0);
			requireThat(resumedOutput.toString(java.nio.charset.StandardCharsets.UTF_8), "resumedResult").
				isEqualTo(output.toString(java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	/**
	 * Resuming an active inspection awaits its original native call, adopts it once, and closes both host turns.
	 *
	 * @throws IOException if test-owned campaign or Git state cannot be prepared
	 * @throws NestedRunException if the controlled call cannot close
	 */
	@Test
	public void resumesRetainedActiveInspectionOnce() throws IOException, NestedRunException
	{
		resumeRetainedInspection(ReconciliationMode.ACTIVE);
	}

	/**
	 * Resuming a settled inspection adopts its retained outcome and closes the original host turn.
	 *
	 * @throws IOException if test-owned campaign or Git state cannot be prepared
	 * @throws NestedRunException if the controlled call cannot close
	 */
	@Test
	public void closesOriginalTurnAfterSettledResume() throws IOException,
		NestedRunException
	{
		resumeRetainedInspection(ReconciliationMode.SETTLED);
	}

	/**
	 * An uncertain native disposition remains available for later reconciliation without a second start.
	 *
	 * @throws IOException if test-owned campaign or Git state cannot be prepared
	 * @throws NestedRunException if the controlled call cannot close
	 */
	@Test
	public void retainsUnknownInspectionForRecovery() throws IOException, NestedRunException
	{
		resumeRetainedInspection(ReconciliationMode.UNKNOWN);
	}

	/**
	 * Runs the retained inspection through one native reconciliation disposition.
	 *
	 * @param mode native state reported after the original owner exits
	 * @throws IOException if test-owned campaign or Git state cannot be prepared
	 * @throws NestedRunException if the controlled call cannot close
	 */
	private static void resumeRetainedInspection(ReconciliationMode mode) throws IOException,
		NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("optimizer-inspect-active-resume-").toRealPath());
			Path project = Files.createDirectory(root.resolve("project"));
			Files.createDirectories(project.resolve(".cat/work"));
			Files.writeString(project.resolve("prompt.md"), "initial prompt\n");
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Inspection Test");
			git(project, "add", "prompt.md");
			git(project, "commit", "-m", "initial");
			Path artifact = Files.createDirectory(cleanup.add(Files.createTempDirectory(
				"optimizer-inspect-active-artifact-").toRealPath()).resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(project.resolve(".cat/work/temp/runs"),
				Clock.systemUTC());
			CampaignLocator locator = new CampaignLocator(project.resolve(".cat/work/optimizer"));
			CampaignStore store = CampaignStore.open(locator.createOrFind(new CampaignLookupKey(project,
				Path.of("prompt.md")), new CampaignCreationRequest("retained-active",
				new ModelProfile("test-model", Optional.empty(), Map.of()))).handle().campaignDirectory());
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "first-invocation");
			inbox.publishHostObservation(binding, "first-invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new OptimizerInspectCommand.HostObservation(
					1, "a".repeat(64), "first-session", "first-turn")));
			RecoveringAdapter adapter = new RecoveringAdapter(mode);
			try (SchedulerLease first = locator.openSchedulerLease(binding).acquire(binding, "first-invocation"))
			{
				StartedInspectionCall started = new InspectionCallCoordinator(locator, store).startFirst(first,
					new NestedAgent(adapter, store::readImmutableJson), manager, artifact);
				started.call().close();
			}
			if (mode != ReconciliationMode.UNKNOWN)
				adapter.settle();

			ByteArrayOutputStream output = new ByteArrayOutputStream();
			String[] args = {"--action", "resume", "--host-session-id", "resumed-session",
				"--host-turn-id", "resumed-turn", "--host-tool-use-id", "resumed-tool",
				"--host-model-id", "test-model", "--target", "prompt.md"};
			if (mode == ReconciliationMode.UNKNOWN)
			{
				try
				{
					OptimizerInspectCommand.run(args, project, artifact, manager,
						(resumedStore, _) -> new NestedAgent(adapter, resumedStore::readImmutableJson), output);
					throw new AssertionError("Unknown native state must be reported");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unknownNativeDisposition").
						contains("Retained inspection state is unknown");
				}
				requireThat(adapter.starts.get(), "nativeStarts").isEqualTo(1);
				requireThat(adapter.reconciles.get(), "nativeReconciliations").isEqualTo(1);
				requireThat(CampaignStore.open(store.directory()).load().scheduler().slots().getFirst().state(),
					"retainedCall").isEqualTo(CallSlot.State.ACTIVE);
				requireThat(locator.findOpenInvocationByHostTurn("resumed-session", "resumed-turn").isPresent(),
					"retainedSuccessorHostTurn").isTrue();
				adapter.settle();
				adapter.reconcileAs(ReconciliationMode.SETTLED);
				OptimizerInspectCommand.run(new String[]{"--action", "resume", "--host-session-id",
					"final-session", "--host-turn-id", "final-turn", "--host-tool-use-id", "final-tool",
					"--host-model-id", "test-model", "--target", "prompt.md"}, project, artifact, manager,
					(resumedStore, _) -> new NestedAgent(adapter, resumedStore::readImmutableJson), output);
				requireThat(adapter.starts.get(), "startsAfterSettlement").isEqualTo(1);
				requireThat(adapter.reconciles.get(), "reconciliationsAfterSettlement").isEqualTo(2);
				requireThat(locator.findOpenInvocationByHostTurn("first-session", "first-turn"),
					"closedOriginalHostTurn").isEqualTo(Optional.empty());
				requireThat(locator.findOpenInvocationByHostTurn("resumed-session", "resumed-turn"),
					"closedUnknownHostTurn").isEqualTo(Optional.empty());
				requireThat(locator.findOpenInvocationByHostTurn("final-session", "final-turn"),
					"closedFinalHostTurn").isEqualTo(Optional.empty());
				return;
			}
			OptimizerInspectCommand.run(args, project, artifact, manager,
				(resumedStore, _) -> new NestedAgent(adapter, resumedStore::readImmutableJson), output);
			requireThat(output.toString(java.nio.charset.StandardCharsets.UTF_8), "resumedResult").
				contains("status=FAILED\n");
			requireThat(adapter.starts.get(), "nativeStarts").isEqualTo(1);
			requireThat(adapter.reconciles.get(), "nativeReconciliations").isEqualTo(1);
			requireThat(CampaignStore.open(store.directory()).load().scheduler().slots().getFirst().state(),
				"adoptedCall").isEqualTo(CallSlot.State.ADOPTED);
			requireThat(locator.findOpenInvocationByHostTurn("first-session", "first-turn"),
				"closedOriginalHostTurn").isEqualTo(Optional.empty());
			requireThat(locator.findOpenInvocationByHostTurn("resumed-session", "resumed-turn"),
				"closedResumedHostTurn").isEqualTo(Optional.empty());
		}
	}

	/**
	 * Runs one test-owned Git command.
	 *
	 * @param project test project root
	 * @param arguments Git subcommand and arguments
	 * @throws IOException if Git rejects the fixture setup
	 */
	private static void git(Path project, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(Arrays.asList(arguments));
		Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
		try (process)
		{
			String output = new String(process.getInputStream().readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
			if (process.waitFor() != 0)
				throw new IOException("Git fixture setup failed: " + output);
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during Git fixture setup", exception);
		}
	}

	/**
	 * Rejects native startup after the campaign has published its host authority.
	 *
	 * @param nativeStarts number of native start requests
	 */
	private record RejectingAdapter(AtomicInteger nativeStarts) implements Adapter
	{
		@Override
		public AdapterCall start(NestedRunRequest request, String runId) throws IOException
		{
			nativeStarts.incrementAndGet();
			throw new IOException("controlled start failure");
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			throw new AssertionError("No prior native call exists");
		}
	}

	/**
	 * Completes the assigned logical call with a controlled native transport failure.
	 */
	private record CompletingAdapter() implements Adapter
	{
		@Override
		public AdapterCall start(NestedRunRequest request, String runId)
		{
			NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
				request.logicalCallId(), Optional.of(runId), "controlled transport failure", List.of(),
				Optional.empty(), Optional.empty());
			return new CompletedCall(runId, CompletableFuture.completedFuture(
				new NestedRunCompletion.Failed(failure)));
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			throw new AssertionError("No prior native call exists");
		}
	}

	/** Retained native states exposed to the resumed command. */
	private enum ReconciliationMode
	{
		ACTIVE,
		SETTLED,
		UNKNOWN
	}

	/**
	 * A retained native call that settles after its first command owner exits.
	 */
	private static final class RecoveringAdapter implements Adapter
	{
		private ReconciliationMode mode;
		private final AtomicInteger starts = new AtomicInteger();
		private final AtomicInteger reconciles = new AtomicInteger();
		private final CompletableFuture<NestedRunCompletion> completion = new CompletableFuture<>();
		private String runId;
		private String logicalCallId;

		/**
		 * Selects the retained native state exposed to the successor.
		 *
		 * @param mode native state reported during reconciliation
		 */
		private RecoveringAdapter(ReconciliationMode mode)
		{
			this.mode = mode;
		}

		/**
		 * Makes a later reconciliation observe the same call's settled outcome.
		 *
		 * @param nextState later native state
		 */
		private void reconcileAs(ReconciliationMode nextState)
		{
			mode = nextState;
		}

		@Override
		public AdapterCall start(NestedRunRequest request, String suppliedRunId)
		{
			starts.incrementAndGet();
			runId = suppliedRunId;
			logicalCallId = request.logicalCallId();
			return new CompletedCall(runId, completion);
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			reconciles.incrementAndGet();
			requireThat(knownRunId, "retainedNativeRunId").isEqualTo(Optional.of(runId));
			requireThat(request.logicalCallId(), "retainedLogicalCallId").isEqualTo(logicalCallId);
			if (mode == ReconciliationMode.SETTLED)
				return new AdapterCallReconciliation.Settled(completion.join());
			if (mode == ReconciliationMode.UNKNOWN)
				return new AdapterCallReconciliation.Unknown(new NestedRunFailure(
					NestedRunFailureKind.NATIVE_STATE_UNKNOWN, logicalCallId, Optional.of(runId),
					"retained outcome is not yet verifiable", List.of(), Optional.empty(), Optional.empty()));
			return new AdapterCallReconciliation.Active(new CompletedCall(runId, completion));
		}

		/**
		 * Makes the retained native failure observable to the successor.
		 */
		private void settle()
		{
			completion.complete(new NestedRunCompletion.Failed(new NestedRunFailure(
				NestedRunFailureKind.TRANSPORT_FAILED, logicalCallId, Optional.of(runId),
				"controlled retained failure", List.of(), Optional.empty(), Optional.empty())));
		}
	}

	/**
	 * A controlled terminal handle whose settlement is already observable.
	 *
	 * @param runId assigned native run identity
	 * @param completion terminal native result
	 */
	private record CompletedCall(String runId, CompletionStage<NestedRunCompletion> completion)
		implements AdapterCall
	{
		@Override
		public void interrupt()
		{
		}

		@Override
		public void close()
		{
		}
	}
}

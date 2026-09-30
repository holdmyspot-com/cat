package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignCreationRequest;
import io.github.cowwoc.cat.common.optimizer.state.CampaignHandle;
import io.github.cowwoc.cat.common.optimizer.state.CampaignInterruptionService;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLookupKey;
import io.github.cowwoc.cat.common.optimizer.state.CampaignRegistration;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStopInbox;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CallSlot;
import io.github.cowwoc.cat.common.optimizer.state.ClaimedSlot;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCaptureService;
import io.github.cowwoc.cat.common.optimizer.state.InspectionCallCoordinator;
import io.github.cowwoc.cat.common.optimizer.state.InspectionCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.state.InspectionCompletionService;
import io.github.cowwoc.cat.common.optimizer.state.InvocationStopRegistration;
import io.github.cowwoc.cat.common.optimizer.state.SchedulerLease;
import io.github.cowwoc.cat.common.optimizer.state.StartedInspectionCall;
import io.github.cowwoc.cat.common.optimizer.state.StopRequest;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the first prompt-system inspection from a retained host tool call.
 */
public final class OptimizerInspectCommand
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Runs one inspected target through the campaign's retained native call.
	 *
	 * @param args inspection command arguments
	 * @param workingDirectory current project directory
	 * @param artifact installed native plugin artifact
	 * @param manager owned native run manager
	 * @param agentFactory creates the native-call adapter for the registered campaign
	 * @param output structured command result
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if inspection state or output fails
	 */
	public static void run(String[] args, Path workingDirectory, Path artifact, HarnessRunManager manager,
		BiFunction<CampaignStore, CampaignBinding, NestedAgent> agentFactory, OutputStream output)
		throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(artifact, "artifact");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(agentFactory, "agentFactory");
		Objects.requireNonNull(output, "output");

		Options options = Options.parse(args);
		Path project = workingDirectory.toRealPath();
		try (ToolScope scope = ToolScope.forWorkingDirectory(project))
		{
			Path projectRoot = scope.catWork().getParent().getParent().toRealPath();
			Path target = project.resolve(options.target()).normalize();
			Path resolvedTarget = target.toRealPath();
			if (!target.startsWith(projectRoot) || !resolvedTarget.startsWith(projectRoot))
				throw new IOException("Inspection target is outside the selected project: " + target);
			CampaignLookupKey key = new CampaignLookupKey(projectRoot, projectRoot.relativize(target));
			Files.createDirectories(scope.catWork());
			CampaignLocator locator = new CampaignLocator(scope.catWork().resolve("optimizer"));
			CampaignHandle handle;
			if (options.action() == Action.START)
			{
				CampaignRegistration registration = locator.createOrFind(key, new CampaignCreationRequest(
					UUID.randomUUID().toString(), new ModelProfile(options.model(), Optional.empty(), Map.of())));
				if (registration.status() == CampaignRegistration.Status.EXISTING_CAMPAIGN_ACTION_REQUIRED)
					throw new IOException("An existing optimizer campaign requires resume or cancel: " +
						registration.handle().campaignId());
				handle = registration.handle();
			}
			else
				handle = locator.findActive(key).orElseThrow(() ->
					new IOException("No active optimizer campaign owns target: " + target));
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			String invocationId = UUID.randomUUID().toString();
			EvidenceRef registrationRef = inbox.beginInvocation(binding, invocationId);
			inbox.publishHostObservation(binding, invocationId, registrationRef,
				JSON.writeValueAsBytes(new HostObservation(1, digest(options.toolUse()),
					options.session(), options.turn())));
			if (options.action() == Action.START)
				new InitialTargetCaptureService(locator, store).capture(binding);

			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding, invocationId))
			{
				InspectionCallCoordinator coordinator = new InspectionCallCoordinator(locator, store);
				StartedInspectionCall started;
				if (options.action() == Action.RESUME)
				{
					InspectionCallReconciliation recovered = coordinator.reconcileFirst(lease,
						agentFactory.apply(store, binding));
					if (recovered instanceof InspectionCallReconciliation.Settled settled)
					{
						EvidenceRef resultRef = coordinator.adoptedFirstResultRef(lease);
						finishInspectionInvocations(inbox, store, lease, settled.logicalCallId());
						writeResult(output, handle, resultRef, settled.completion());
						return;
					}
					if (recovered instanceof InspectionCallReconciliation.Active active)
						started = active.started();
					else
					{
						InspectionCallReconciliation.Unknown unknown = (InspectionCallReconciliation.Unknown) recovered;
						throw new IOException("Retained inspection state is unknown: " + unknown.failure().reason());
					}
				}
				else
				{
					try
					{
						started = coordinator.startFirst(lease, agentFactory.apply(store, binding), manager, artifact);
					}
					catch (NestedRunException failure)
					{
						throw new IOException("Native inspection did not start: " + failure.getMessage(), failure);
					}
				}
				try (ActiveCall call = started.call())
				{
					NestedRunCompletion completion = awaitCompletion(lease, coordinator, started, call,
						locator, store);
					EvidenceRef resultRef = new InspectionCompletionService(locator, store).adopt(lease,
						started, completion);
					transferStop(lease, coordinator, started, locator, store);
					finishInspectionInvocations(inbox, store, lease, started.logicalCallId());
					writeResult(output, handle, resultRef, completion);
				}
				catch (NestedRunException failure)
				{
					throw new IOException("Native inspection cleanup failed: " + failure.getMessage(), failure);
				}
			}
		}
	}

	/**
	 * Closes host turns bound to the first inspection through its adopted recovery fence.
	 *
	 * @param inbox registered host invocation authority
	 * @param store pinned campaign evidence store
	 * @param lease current campaign owner
	 * @param logicalCallId adopted native call identity
	 * @throws IOException if a registration or retained claim is invalid
	 */
	private static void finishInspectionInvocations(CampaignStopInbox inbox, CampaignStore store,
		SchedulerLease lease, String logicalCallId) throws IOException
	{
		CampaignBinding binding = lease.binding();
		ClaimedSlot claimed = store.readCallSlotClaim(binding, logicalCallId);
		if (claimed.slot().state() != CallSlot.State.ADOPTED)
			throw new IOException("Inspection call is not adopted: " + logicalCallId);
		long claimFence = claimed.claim().claimFence();
		for (InvocationStopRegistration registration : store.load().scheduler().invocations())
			if (registration.binding().equals(binding) && registration.boundFence().isPresent() &&
				registration.boundFence().orElseThrow() >= claimFence &&
				registration.boundFence().orElseThrow() <= lease.fence())
				inbox.finishInvocation(binding, registration.invocationId());
	}

	/**
	 * Writes the adopted inspection identity and terminal native outcome.
	 *
	 * @param output command result stream
	 * @param handle active campaign
	 * @param resultRef adopted inspection result
	 * @param completion native terminal result
	 * @throws IOException if command output cannot be written
	 */
	private static void writeResult(OutputStream output, CampaignHandle handle, EvidenceRef resultRef,
		NestedRunCompletion completion) throws IOException
	{
		String status;
		if (completion instanceof NestedRunCompletion.Succeeded)
			status = "SUCCEEDED";
		else
			status = "FAILED";
		output.write(("campaign_id=" + handle.campaignId() + "\ninspection_result=" +
			resultRef.evidenceId() + "\nstatus=" + status + "\n").getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Waits for native completion while transferring a host interruption into the active handle.
	 *
	 * @param lease current campaign owner
	 * @param coordinator inspection call coordinator
	 * @param started active native inspection
	 * @param call caller-owned native handle
	 * @param locator durable campaign locator
	 * @param store pinned campaign store
	 * @return validated terminal native result
	 * @throws IOException if native completion or stop transfer fails
	 */
	private static NestedRunCompletion awaitCompletion(SchedulerLease lease,
		InspectionCallCoordinator coordinator, StartedInspectionCall started, ActiveCall call,
		CampaignLocator locator,
		CampaignStore store) throws IOException
	{
		CompletableFuture<NestedRunCompletion> completion = call.completion().toCompletableFuture();
		while (true)
		{
			transferStop(lease, coordinator, started, locator, store);
			try
			{
				return completion.get(100, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException _)
			{
				// The host stop inbox is polled until this owned native call settles.
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Inspection wait was interrupted; retained call requires reconciliation", failure);
			}
			catch (ExecutionException failure)
			{
				throw new IOException("Native inspection completion failed", failure);
			}
		}
	}

	/**
	 * Commits an exact pending host stop after its native cancellation request.
	 *
	 * @param lease current campaign owner
	 * @param coordinator inspection call coordinator
	 * @param started active native inspection
	 * @param locator durable campaign locator
	 * @param store pinned campaign store
	 * @throws IOException if stop authority or native interruption fails
	 */
	private static void transferStop(SchedulerLease lease, InspectionCallCoordinator coordinator,
		StartedInspectionCall started, CampaignLocator locator, CampaignStore store) throws IOException
	{
		Optional<StopRequest> stop = coordinator.transferPendingStop(lease, started);
		if (stop.isPresent())
			new CampaignInterruptionService(locator, store).commitHostStop(lease, stop.orElseThrow(),
				Optional.of(started.logicalCallId()));
	}

	/**
	 * Hashes the one-use host tool identity without storing its raw value in campaign evidence.
	 *
	 * @param toolUse exact current host tool-call identity
	 * @return SHA-256 digest in lowercase hexadecimal
	 */
	private static String digest(String toolUse)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				toolUse.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new AssertionError("The JDK does not support SHA-256", failure);
		}
	}

	/**
	 * Validated command options for one current host tool call.
	 *
	 * @param target selected prompt path
	 * @param session current host session
	 * @param turn current host turn
	 * @param toolUse current host tool call
	 * @param model active host model
	 * @param action whether to start a new campaign or resume its active owner
	 */
	private record Options(String target, String session, String turn, String toolUse, String model,
		Action action)
	{
		/**
		 * Parses one command with required, unique options.
		 *
		 * @param args raw command arguments
		 * @return validated inspection options
		 */
		private static Options parse(String[] args)
		{
			Map<String, String> values = new HashMap<>();
			for (int index = 0; index < args.length; index += 2)
			{
				String name = args[index];
				if (index + 1 == args.length || !name.equals("--target") && !name.equals("--action") &&
					!name.equals("--host-session-id") && !name.equals("--host-turn-id") &&
					!name.equals("--host-tool-use-id") && !name.equals("--host-model-id"))
					throw new IllegalArgumentException("Unsupported or valueless inspection option: " + name);
				String value = args[index + 1];
				if (value.isBlank() || values.putIfAbsent(name, value) != null)
					throw new IllegalArgumentException("Blank or duplicate inspection option: " + name);
			}
			for (String required : new String[]{"--host-session-id", "--host-turn-id",
				"--host-tool-use-id", "--host-model-id", "--target"})
				if (!values.containsKey(required))
					throw new IllegalArgumentException("Missing inspection option: " + required);
			Action action = switch (values.getOrDefault("--action", "start"))
			{
				case "start" -> Action.START;
				case "resume" -> Action.RESUME;
				default -> throw new IllegalArgumentException("Unsupported inspection action: " +
					values.get("--action"));
			};
			return new Options(values.get("--target"), values.get("--host-session-id"),
				values.get("--host-turn-id"), values.get("--host-tool-use-id"),
				values.get("--host-model-id"), action);
		}
	}

	/**
	 * Closed set of campaign actions accepted from the untyped command line.
	 */
	private enum Action
	{
		START,
		RESUME
	}

	/**
	 * Canonical durable subset of the observed host tool call.
	 *
	 * @param schemaVersion observation schema
	 * @param tokenDigest one-use tool-call identity digest
	 * @param hostSessionId current host session
	 * @param hostTurnId current host turn
	 */
	public record HostObservation(int schemaVersion, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	/**
	 * Prevents construction.
	 */
	private OptimizerInspectCommand()
	{
	}
}

package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Tests publication of a verified runner identity for a claimed campaign call. */
public final class CallPreStartPublisherTest
{
	/** Creates the test fixture. */
	public CallPreStartPublisherTest()
	{
	}

	/**
	 * A retry reuses the same indexed native run ID and packaged runtime hash.
	 *
	 * @throws IOException if the test-owned campaign or artifact cannot be written
	 */
	@Test
	public void retainsVerifiedIdentityAcrossRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-call-prestart-publisher-")).toRealPath();
			CampaignStore store = new CampaignStore(root.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(UTF_8));
			Path artifact = Files.createDirectory(root.resolve("artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.OwnerAttempt owner = ProspectiveCampaignState.publishOwnerAttempt(store,
					lease, "call");
				EvidenceRef first = CallPreStartPublisher.publish(store, binding, "call",
					ProducerAttemptKind.EVALUATOR, owner.attemptRef(), owner.ownerRequestRef(), manager, artifact);
				EvidenceRef retry = CallPreStartPublisher.publish(store, binding, "call",
					ProducerAttemptKind.EVALUATOR, owner.attemptRef(), owner.ownerRequestRef(), manager, artifact);
				CallPreStartRecord retained = new CampaignStore(store.directory()).
					readCallPreStartRecord(binding, "call").orElseThrow();
				requireThat(retry, "retry").isEqualTo(first);
				requireThat(retained.runtimeManifestSha256(), "runtimeHash").isEqualTo(
					RunnerRuntimeManifest.fingerprint(artifact));
				requireThat(retained.runId(), "runId").isNotBlank();
				CallSlot starting = ProspectiveCampaignState.lease(store).prepareCallStart(lease, "call",
					ProducerAttemptKind.EVALUATOR, owner.attemptRef(), owner.ownerRequestRef(), manager, artifact);
				requireThat(starting.state(), "state").isEqualTo(CallSlot.State.STARTING);
				requireThat(starting.preStartRef(), "preStartRef").isEqualTo(Optional.of(first));
				try
				{
					ProspectiveCampaignState.lease(store).prepareCallStart(lease, "call",
						ProducerAttemptKind.EVALUATOR, owner.attemptRef(), owner.ownerRequestRef(), manager, artifact);
					throw new AssertionError("A started call must be reconciled before any further dispatch");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "restartBoundary").contains("reconcile");
				}
				try
				{
					CallPreStartPublisher.publish(store, binding, "call", ProducerAttemptKind.EVALUATOR,
						new EvidenceRef("trial-transport-attempts", "e".repeat(64)),
						owner.ownerRequestRef(), manager, artifact);
					throw new AssertionError("A changed owner attempt cannot reuse the call identity");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "attemptMismatch").contains("differs");
				}
				Files.createDirectories(artifact.resolve("client"));
				Files.writeString(artifact.resolve("client/changed"), "change");
				try
				{
					CallPreStartPublisher.publish(store, binding, "call", ProducerAttemptKind.EVALUATOR,
						owner.attemptRef(), owner.ownerRequestRef(), manager, artifact);
					throw new AssertionError("A changed runner cannot reuse the call identity");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "runnerMismatch").contains("does not match");
				}
			}
		}
	}

	/**
	 * A known runner observed before a confirmed call must match the artifact indexed at pre-start.
	 *
	 * @throws IOException if campaign or packaged runner evidence cannot be written
	 */
	@Test
	public void rejectsRunnerChangedAfterObservation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("runner-prestart-change-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			Path observedArtifact = Files.createDirectory(root.resolve("observed-artifact"));
			Path changedArtifact = Files.createDirectory(root.resolve("changed-artifact"));
			Files.createDirectories(observedArtifact.resolve("hooks"));
			Files.createDirectories(changedArtifact.resolve("hooks"));
			Files.writeString(observedArtifact.resolve("hooks/runner.txt"), "observed");
			Files.writeString(changedArtifact.resolve("hooks/runner.txt"), "changed");
			RunnerRuntimeManifest.write(observedArtifact);
			RunnerRuntimeManifest.write(changedArtifact);
			EvidenceRef observedRunner = store.appendImmutableJson("measurement-values",
				JsonMapper.builder().build().writeValueAsBytes(
					RunnerRuntimeManifest.fingerprint(observedArtifact)));
			Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
				new EnumMap<>(MeasurementConditions.Kind.class);
			for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
				values.put(kind, new MeasurementConditions.Value.Unknown("Test host did not expose this condition"));
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Known(observedRunner));
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "f".repeat(64),
					"session-f", "turn-f")));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			try (SchedulerLease lease = scheduler.acquire(binding, "invocation"))
			{
				locator.openMeasurementObserver(binding).beforeCall(lease, "call",
					new MeasurementConditions(values));
				EvidenceRef request = store.appendImmutableJson("run-requests",
					"{\"logicalCallId\":\"call\"}".getBytes(UTF_8));
				scheduler.claimOrResumeCall(lease, "call", request, Optional.empty(), Optional.empty());
				ProspectiveCampaignState.OwnerAttempt owner = ProspectiveCampaignState.publishOwnerAttempt(store,
					lease, "call");
				try
				{
					CallPreStartPublisher.publish(store, binding, "call", ProducerAttemptKind.EVALUATOR,
						owner.attemptRef(), owner.ownerRequestRef(), manager, changedArtifact);
					throw new AssertionError("Changed runner must not acquire pre-start authority");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "runnerChanged").contains("observed runner");
				}
				requireThat(store.readCallPreStartIndex(binding, "call"), "noChangedPreStart").
					isEqualTo(Optional.empty());
				EvidenceRef accepted = CallPreStartPublisher.publish(store, binding, "call",
					ProducerAttemptKind.EVALUATOR, owner.attemptRef(), owner.ownerRequestRef(), manager,
					observedArtifact);
				requireThat(store.readCallPreStartIndex(binding, "call"), "matchingPreStart").
					isEqualTo(Optional.of(accepted));
			}
		}
	}

	/**
	 * Canonical host observation fixture for one scheduler invocation.
	 *
	 * @param schemaVersion the host observation schema
	 * @param tokenDigest the invoking session token digest
	 * @param hostSessionId the host session identity
	 * @param hostTurnId the host turn identity
	 */
	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}

package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignCreationRequest;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLookupKey;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStopInbox;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.SchedulerLease;
import io.github.cowwoc.cat.common.optimizer.state.StopRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises host-turn interruption delivery through a registered campaign.
 */
public final class CodexOptimizerHostHookTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/** Creates an isolated host hook check. */
	public CodexOptimizerHostHookTest()
	{
	}

	/**
	 * Only the active host turn submits one durable stop to its bound campaign invocation.
	 *
	 * @throws IOException if fixture creation or stop publication fails
	 */
	@Test
	public void submitsOnlyMatchingHostTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-host-hook-").toRealPath());
			Files.createDirectory(project.resolve(".cat"));
			Files.createDirectories(project.resolve(".cat/work/optimizer"));
			Files.writeString(project.resolve("target.md"), "inspect\n");
			Path workflow = project.resolve(".cat/work/optimizer");
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, Path.of("target.md")),
				new CampaignCreationRequest("inspection", new ModelProfile("test-model", Optional.empty(),
					Map.of())));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-inspection"));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation-a");
			ObjectNode observation = json.createObjectNode();
			observation.put("schemaVersion", 1);
			observation.put("tokenDigest", "a".repeat(64));
			observation.put("hostSessionId", "session-1");
			observation.put("hostTurnId", "turn-1");
			inbox.publishHostObservation(binding, "invocation-a", registration,
				json.writeValueAsBytes(observation));
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding, "invocation-a"))
			{
				CodexOptimizerHostHook.run(event("Interrupt", "session-1", "turn-2", project), project);
				requireThat(store.load().scheduler().stops(), "unrelatedTurnStops").isEmpty();

				CodexOptimizerHostHook.run(event("Interrupt", "session-1", "turn-1", project), project);
				StopRequest stop = store.load().scheduler().stops().getFirst();
				requireThat(stop.sourceInvocationId(), "sourceInvocation").isEqualTo("invocation-a");
				requireThat(stop.sourceFence(), "boundFence").isEqualTo(Optional.of(lease.fence()));
				CodexOptimizerHostHook.run(event("Interrupt", "session-1", "turn-1", project), project);
				requireThat(store.load().scheduler().stops().size(), "oneStop").isEqualTo(1);
			}
		}
	}

	/**
	 * A different hook event cannot submit an optimizer stop.
	 *
	 * @throws IOException if host event reading fails
	 */
	@Test
	public void refusesAnotherHookEvent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("optimizer-host-event-").toRealPath());
			try
			{
				CodexOptimizerHostHook.run(event("Stop", "session-1", "turn-1", project), project);
				throw new AssertionError("Only an Interrupt event may submit a host stop");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "wrongEvent").contains("Interrupt");
			}
		}
	}

	/**
	 * Encodes one controlled host hook event.
	 *
	 * @param name hook event name
	 * @param session host session identity
	 * @param turn host turn identity
	 * @param project host working directory
	 * @return input stream for the host hook operation
	 */
	private ByteArrayInputStream event(String name, String session, String turn, Path project)
	{
		ObjectNode event = json.createObjectNode();
		event.put("hook_event_name", name);
		event.put("session_id", session);
		event.put("turn_id", turn);
		event.put("cwd", project.toString());
		byte[] bytes = json.writeValueAsBytes(event);
		return new ByteArrayInputStream(bytes);
	}
}

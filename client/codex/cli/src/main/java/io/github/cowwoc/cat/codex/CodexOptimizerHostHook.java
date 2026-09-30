package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.InvocationStopRegistration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a Codex host interruption to the matching optimizer campaign invocation.
 */
public final class CodexOptimizerHostHook
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Handles one Codex interrupt hook event.
	 *
	 * @param args empty hook arguments
	 * @throws IOException if host event or campaign stop delivery fails
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 0)
			throw new IllegalArgumentException("Optimizer interrupt hook takes no arguments");
		run(System.in, Path.of(System.getProperty("user.dir")));
	}

	/**
	 * Submits the original invocation's stop when the host interrupts its active turn.
	 *
	 * @param input one host interrupt event
	 * @param workingDirectory project working directory reported by the host
	 * @throws IOException if event validation or durable stop delivery fails
	 */
	public static void run(InputStream input, Path workingDirectory) throws IOException
	{
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		JsonNode event = JSON.readTree(input);
		if (event == null || !event.isObject() ||
			!requiredText(event, "hook_event_name").equals("Interrupt"))
			throw new IOException("Optimizer host hook requires an Interrupt event");
		String sessionId = requiredText(event, "session_id");
		String turnId = requiredText(event, "turn_id");
		Path reportedDirectory;
		try
		{
			reportedDirectory = Path.of(requiredText(event, "cwd")).toRealPath();
		}
		catch (RuntimeException failure)
		{
			throw new IOException("Host interruption has an invalid working directory", failure);
		}
		if (!reportedDirectory.equals(workingDirectory.toRealPath()))
			throw new IOException("Host interruption working directory differs from the hook process");
		Path workflowRoot = ToolScope.forWorkingDirectory(reportedDirectory).catWork().resolve("optimizer");
		if (Files.notExists(workflowRoot, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isDirectory(workflowRoot, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Optimizer workflow root is not a directory: " + workflowRoot);
		CampaignLocator locator = new CampaignLocator(workflowRoot);
		Optional<InvocationStopRegistration> registration = locator.findOpenInvocationByHostTurn(sessionId,
			turnId);
		if (registration.isEmpty())
			return;
		InvocationStopRegistration source = registration.orElseThrow();
		locator.openStopInbox(source.binding()).submit(source.binding(), source.invocationId(),
			"HOST_INTERRUPTION");
	}

	/**
	 * Reads a non-blank host event string before it selects a campaign.
	 *
	 * @param event Codex hook event
	 * @param name required field name
	 * @return exact non-blank field value
	 * @throws IOException if the field is absent or malformed
	 */
	private static String requiredText(JsonNode event, String name) throws IOException
	{
		JsonNode value = event.path(name);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Host interruption is missing " + name);
		return value.asString();
	}

	/**
	 * Prevents construction.
	 */
	private CodexOptimizerHostHook()
	{
	}
}

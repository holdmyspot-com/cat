package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request to compare startup hooks with the broker's selected Codex installation. The archive carries workspace
 * copies and staged plugins. The broker supplies the original project, native installation and command policy.
 *
 * @param workspace the directory holding the two workspace copies
 * @param plugins the staged plugin inputs in order
 */
public record HookComparisonRequest(Path workspace, List<TrialPluginInput> plugins)
{
	/**
	 * Identifies the operation in the request server's inbox.
	 */
	public static final String PREFIX = "hook-comparison-";
	static final String SESSION_DIRECTORY = "session";
	static final String TRIAL_DIRECTORY = "trial";
	static final String PROJECT_DIRECTORY = "project";

	/**
	 * Creates the request.
	 *
	 * @param workspace the directory holding the two workspace copies
	 * @param plugins the staged plugin inputs in order
	 * @throws NullPointerException if an argument or a list element is null
	 */
	public HookComparisonRequest
	{
		Objects.requireNonNull(workspace);
		plugins = List.copyOf(plugins);
	}

	/**
	 * Returns the full project copy that represents the production session.
	 *
	 * @return its workspace
	 */
	public Path production()
	{
		return workspace.resolve(SESSION_DIRECTORY).resolve(PROJECT_DIRECTORY);
	}

	/**
	 * Returns the restricted project copy that represents a trial.
	 *
	 * @return its workspace
	 */
	public Path trial()
	{
		return workspace.resolve(TRIAL_DIRECTORY).resolve(PROJECT_DIRECTORY);
	}

	/**
	 * Publishes the workspace copies and plugin inputs from inside the caller's sandbox.
	 *
	 * @param json the request mapper
	 * @param requests the owner's request inbox
	 * @return the identifier for the response
	 * @throws IOException if the request cannot be archived or published
	 */
	public String publish(JsonMapper json, Path requests) throws IOException
	{
		String id = PREFIX + UUID.randomUUID();
		RequestArchive.packHookComparison(json, requests.resolve(id + RequestArchive.EXTENSION), this);
		return id;
	}

	/**
	 * Sends the captured inputs and waits for the owner's reply on an already authenticated channel.
	 *
	 * @param json the request and response mapper
	 * @param channel the owner's request channel, which the caller closes
	 * @param pause paces response polling
	 * @return the comparison observations
	 * @throws IOException if the broker refuses or ends, or the request or response cannot be read
	 */
	public HookComparisonResult compare(JsonMapper json, WorktreeRequestClient.Channel channel, Pause pause)
		throws IOException
	{
		Path requests = channel.requests().toAbsolutePath();
		String id = publish(json, requests);
		Path directory = requests.resolveSibling("responses").resolve(id);
		try
		{
			while (true)
			{
				Optional<HookComparisonResult> result = HookComparisonResult.read(json, directory);
				if (result.isPresent())
					return result.orElseThrow();
				if (!channel.isServed())
				{
					return HookComparisonResult.read(json, directory).orElseThrow(() ->
						new IOException("The request server ended before it answered the hook comparison"));
				}
				pause.pause();
			}
		}
		finally
		{
			if (channel.removesResponses() &&
				Files.exists(directory.resolve(WorktreeResponse.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
			{
				TemporaryDirectories.deleteRecursively(directory);
			}
		}
	}

	/**
	 * Reads a request from the broker's extracted copy.
	 *
	 * @param json the request mapper
	 * @param inputs the broker's private directory holding the extracted archive
	 * @return the captured workspaces and plugins
	 * @throws IOException if the manifest or archive layout is invalid
	 */
	public static HookComparisonRequest read(JsonMapper json, Path inputs) throws IOException
	{
		return RequestArchive.readHookComparison(json, inputs);
	}
}

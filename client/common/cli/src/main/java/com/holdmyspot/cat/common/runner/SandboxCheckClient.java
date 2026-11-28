package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks whatever serves the requests of the session that runs a command whether the session runs its commands in a
 * sandbox, as a {@link SandboxCheckRequest}, because the session's commands cannot tell from inside it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SandboxCheckClient
{
	private final WorktreeRequestClient.Router router;
	private final JsonMapper json;
	private final Pause pause;

	/**
	 * Creates a client.
	 *
	 * @param router chooses where the request goes
	 * @param json   the document mapper
	 * @param pause  paces the checks for an answer
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SandboxCheckClient(WorktreeRequestClient.Router router, JsonMapper json, Pause pause)
	{
		requireThat(router, "router").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(pause, "pause").isNotNull();
		this.router = router;
		this.json = json;
		this.pause = pause;
	}

	/**
	 * Returns why the session cannot run what needs a sandbox, as the receiver of its requests reads it.
	 *
	 * @return the reason, which names the cause and the fix, or empty if the session's commands run in a sandbox, or
	 *         nothing serves the session's requests
	 * @throws IOException if the request cannot be sent, or the receiver stops before it answers, fails, or answers
	 *                     with a malformed response
	 */
	public Optional<String> refusal() throws IOException
	{
		Optional<WorktreeRequestClient.Channel> opened = router.open();
		if (opened.isEmpty())
			return Optional.empty();
		WorktreeResponse response;
		try (WorktreeRequestClient.Channel channel = opened.get())
		{
			Path requests = channel.requests().toAbsolutePath();
			String id = SandboxCheckRequest.publish(json, requests);
			Path directory = requests.getParent().resolve("responses").resolve(id);
			response = await(channel, directory);
			if (channel.removesResponses())
				TemporaryDirectories.deleteRecursively(directory);
		}
		return switch (response.status())
		{
			case WorktreeRequest.DONE -> Optional.empty();
			case WorktreeRequest.REFUSED -> Optional.of(response.reason());
			default -> throw new IOException("CAT could not tell whether this session has a sandbox: " +
				response.reason());
		};
	}

	/**
	 * Waits for the answer to the request.
	 *
	 * @param channel   the channel that the request went through
	 * @param directory the request's response directory
	 * @return the answer
	 * @throws IOException if the receiver stops serving before it answers, or the answer cannot be read
	 */
	private WorktreeResponse await(WorktreeRequestClient.Channel channel, Path directory) throws IOException
	{
		while (true)
		{
			Optional<WorktreeResponse> response = WorktreeResponse.read(json, directory);
			if (response.isPresent())
				return response.get();
			if (!channel.isServed())
			{
				// The receiver may have answered just before it ended
				response = WorktreeResponse.read(json, directory);
				if (response.isPresent())
					return response.get();
				throw new IOException("The request server ended before it answered the sandbox check request: " +
					directory.getFileName());
			}
			pause.pause();
		}
	}
}

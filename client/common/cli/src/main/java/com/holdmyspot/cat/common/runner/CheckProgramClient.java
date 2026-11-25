package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks whatever serves the requests of the session that runs a command to run a check program, as a
 * {@link CheckProgramRequest}, because a program's own sandbox cannot start inside the session's sandbox.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CheckProgramClient
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
	public CheckProgramClient(WorktreeRequestClient.Router router, JsonMapper json, Pause pause)
	{
		requireThat(router, "router").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(pause, "pause").isNotNull();
		this.router = router;
		this.json = json;
		this.pause = pause;
	}

	/**
	 * Sends a run and waits for its result.
	 *
	 * @param request the run
	 * @return the program's result
	 * @throws NullPointerException    if {@code request} is null
	 * @throws SessionRefusedException if the receiver refuses to run the program; the message says why
	 * @throws IOException             if nothing serves the session's requests, the request cannot be sent, the
	 *                                 receiver cannot run the program or stops before it answers, or the answer is
	 *                                 malformed
	 */
	public CheckProgram.Result run(CheckProgramRequest request) throws IOException
	{
		requireThat(request, "request").isNotNull();
		WorktreeRequestClient.Channel opened = router.open().orElseThrow(() ->
			new IOException("No request server runs the check programs of the session that runs this command"));
		WorktreeResponse response;
		try (WorktreeRequestClient.Channel channel = opened)
		{
			Path requests = channel.requests().toAbsolutePath();
			String id = request.publish(json, requests);
			Path directory = requests.getParent().resolve("responses").resolve(id);
			response = await(channel, directory);
			if (channel.removesResponses())
				TemporaryDirectories.deleteRecursively(directory);
		}
		return switch (response.status())
		{
			case WorktreeRequest.DONE -> CheckProgramRequest.decode(json, response.result().orElseThrow(() ->
				new IOException("The answer to the check program request holds no result")));
			case WorktreeRequest.REFUSED -> throw new SessionRefusedException(response.reason());
			default -> throw new IOException("CAT could not run the check program: " + response.reason());
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
				throw new IOException("The request server ended before it answered the check program request: " +
					directory.getFileName());
			}
			pause.pause();
		}
	}
}

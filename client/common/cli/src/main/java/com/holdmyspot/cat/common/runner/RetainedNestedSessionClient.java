package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Discards the interrupted nested sessions that a command's server retains: it sends each discard as a {@link
 * DiscardRequest} to whatever serves the command's launches, by the route of its worktree requests, and waits for the
 * answer. A command that nothing serves started no nested session that a server could retain, so it has nothing to
 * discard.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RetainedNestedSessionClient
{
	private final WorktreeRequestClient.Router router;
	private final JsonMapper json;
	private final Pause pause;

	/**
	 * Creates a client.
	 *
	 * @param router chooses where each request goes
	 * @param json   the document mapper
	 * @param pause  paces the checks for an answer
	 * @throws NullPointerException if any of the arguments are null
	 */
	public RetainedNestedSessionClient(WorktreeRequestClient.Router router, JsonMapper json, Pause pause)
	{
		requireThat(router, "router").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(pause, "pause").isNotNull();
		this.router = router;
		this.json = json;
		this.pause = pause;
	}

	/**
	 * Discards the interrupted nested session that records a harness session, emptying its slot once the processes that
	 * it recorded have stopped.
	 *
	 * @param sessionId the harness session that the nested session records
	 * @return {@code true} if a retained nested session recorded the session and was discarded, or {@code false} if none
	 *         did
	 * @throws NullPointerException     if {@code sessionId} is null
	 * @throws IllegalArgumentException if {@code sessionId} is not shaped like a harness session identifier
	 * @throws SessionRefusedException  if the server refused the request, such as while a launch resumes the nested
	 *                                  session or a process that the nested session recorded stays alive
	 * @throws IOException              if the request cannot be sent, the server fails to carry it out or ends before
	 *                                  it answers, or its answer cannot be read
	 */
	public boolean discard(String sessionId) throws IOException
	{
		DiscardRequest request = new DiscardRequest(sessionId);
		Optional<WorktreeRequestClient.Channel> opened = router.open();
		if (opened.isEmpty())
			return false;
		WorktreeResponse response;
		try (WorktreeRequestClient.Channel channel = opened.get())
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
			case WorktreeRequest.DONE -> response.result().equals(Optional.of(DiscardRequest.DISCARDED));
			case WorktreeRequest.REFUSED -> throw new SessionRefusedException(response.reason());
			default -> throw new IOException("CAT could not discard the interrupted nested session " + sessionId +
				": " + response.reason());
		};
	}

	/**
	 * Waits for the answer to a request.
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
				throw new IOException("The request server ended before it answered the discard request: " +
					directory.getFileName());
			}
			pause.pause();
		}
	}
}

package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inspects and discards interrupted nested sessions through the authenticated route that serves the command's
 * launches and worktree requests.
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
			response = await(channel, directory, "discard request", () -> WorktreeResponse.read(json, directory));
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
	 * Sends a retained trial status or discard request and waits for the broker's answer. After a lost discard reply,
	 * the caller can send the same request again with its original launch identifier and observed identity.
	 *
	 * @param request the operation bound to its original trial launch
	 * @return the broker's answer that matches the request
	 * @throws NullPointerException if {@code request} is null
	 * @throws IOException          if there is no authenticated channel, the request cannot be sent, or its response
	 *                              cannot be read
	 */
	public RetainedTrialResponse request(RetainedTrialRequest request) throws IOException
	{
		requireThat(request, "request").isNotNull();
		WorktreeRequestClient.Channel opened = router.open().orElseThrow(() ->
			new IOException("No request server can settle the retained trial's original launch"));
		try (WorktreeRequestClient.Channel channel = opened)
		{
			Path requests = channel.requests().toAbsolutePath();
			String id = request.publish(json, requests);
			Path directory = requests.getParent().resolve("responses").resolve(id);
			RetainedTrialResponse response = await(channel, directory, "retained trial request",
				() -> RetainedTrialResponse.read(json, directory, request));
			if (channel.removesResponses())
				TemporaryDirectories.deleteRecursively(directory);
			return response;
		}
	}

	/**
	 * Copies and replays the published evidence of a verified successor without opening a harness session.
	 *
	 * @param predecessor the checkpoint from which the correction resumed
	 * @param successor the settled checkpoint observed by the caller
	 * @param harnessResults the private results directory for this harness
	 * @param output where to replay the prefix in bridge format
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the broker cannot prove or export this transition
	 */
	public void exportSuccessor(RetainedTrialClaim predecessor, RetainedTrialClaim successor, Path harnessResults,
		Writer output) throws IOException
	{
		requireThat(predecessor, "predecessor").isNotNull();
		requireThat(successor, "successor").isNotNull();
		requireThat(harnessResults, "harnessResults").isNotNull();
		requireThat(output, "output").isNotNull();

		RetainedTrialRequest request = RetainedTrialRequest.exportSuccessor(predecessor);
		WorktreeRequestClient.Channel opened = router.open().orElseThrow(() ->
			new IOException("No request server can export the retained author correction"));
		try (WorktreeRequestClient.Channel channel = opened)
		{
			Path requests = channel.requests().toAbsolutePath();
			String id = request.publish(json, requests);
			Path directory = requests.getParent().resolve("responses").resolve(id);
			RetainedTrialResponse response = await(channel, directory, "retained correction export",
				() -> RetainedTrialResponse.read(json, directory, request));
			try
			{
				if (response.status() != RetainedTrialResponse.Status.READY ||
					!response.requestId().equals(successor.requestId()) ||
					!response.identity().equals(Optional.of(successor.identity())) ||
					response.completedTurns().orElse(-1) != successor.completedTurns())
					throw new IOException("The broker could not export the observed author correction");

				String sessionId = successor.identity().session().orElseThrow().sessionId();
				RetainedTrialPrefix prefix = RetainedTrialPrefix.open(directory, successor.completedTurns());
				Path session = Files.createDirectories(harnessResults.resolve(sessionId));
				if (!ResultsCopy.copyTurn(prefix.directory(), session).isEmpty())
					throw new IOException("The recovered author correction could not copy all its project evidence");
				prefix.replay(json, sessionId, output);
			}
			finally
			{
				if (channel.removesResponses())
					TemporaryDirectories.deleteRecursively(directory);
			}
		}
	}

	/**
	 * Waits for the answer to a request.
	 *
	 * @param <T>         the response type
	 * @param channel     the channel that the request went through
	 * @param directory   the request's response directory
	 * @param requestKind the operation's description for diagnostic messages
	 * @param reader      the operation that reads the answer once it is available
	 * @return the answer
	 * @throws IOException if the receiver stops serving before it answers, or the answer cannot be read
	 */
	private <T> T await(WorktreeRequestClient.Channel channel, Path directory, String requestKind,
		Reader<T> reader) throws IOException
	{
		while (true)
		{
			Optional<T> response = reader.read();
			if (response.isPresent())
				return response.get();
			if (!channel.isServed())
			{
				// The receiver may have answered just before it ended
				response = reader.read();
				if (response.isPresent())
					return response.get();
				throw new IOException("The request server ended before it answered the " + requestKind + ": " +
					directory.getFileName());
			}
			pause.pause();
		}
	}

	/**
	 * Reads the response of a particular request.
	 *
	 * @param <T> the response type
	 */
	@FunctionalInterface
	private interface Reader<T>
	{
		/**
		 * Reads the response if the broker published it.
		 *
		 * @return the answer, or empty while publication is pending
		 * @throws IOException if the answer cannot be read
		 */
		Optional<T> read() throws IOException;
	}
}

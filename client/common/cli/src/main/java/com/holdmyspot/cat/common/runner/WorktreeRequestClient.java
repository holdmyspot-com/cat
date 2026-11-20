package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link WorktreeRegistrar} of a command that a harness's session runs: it sends each operation as a
 * {@link WorktreeRequest} to whatever serves the session's launches, which carries it out outside the session's
 * sandbox, and waits for the answer. A command that no harness runs carries out the operations itself.
 * <p>
 * A repair whose links are intact sends no request.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class WorktreeRequestClient implements WorktreeRegistrar
{
	private final Router router;
	private final WorktreeRegistrar inPlace;
	private final JsonMapper json;
	private final Pause pause;

	/**
	 * Creates a client.
	 *
	 * @param router  chooses where each request goes
	 * @param inPlace carries out the operations of a command that no harness runs
	 * @param json    the document mapper
	 * @param pause   paces the checks for an answer
	 * @throws NullPointerException if any of the arguments are null
	 */
	public WorktreeRequestClient(Router router, WorktreeRegistrar inPlace, JsonMapper json, Pause pause)
	{
		requireThat(router, "router").isNotNull();
		requireThat(inPlace, "inPlace").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(pause, "pause").isNotNull();
		this.router = router;
		this.inPlace = inPlace;
		this.json = json;
		this.pause = pause;
	}

	@Override
	public void add(Path project, Path campaign) throws IOException
	{
		Optional<WorktreeResponse> response = send(WorktreeRequest.Operation.ADD, campaign);
		if (response.isEmpty())
			inPlace.add(project, campaign);
	}

	@Override
	public void recreate(Path project, Path campaign) throws IOException
	{
		Optional<WorktreeResponse> response = send(WorktreeRequest.Operation.RECREATE, campaign);
		if (response.isEmpty())
			inPlace.recreate(project, campaign);
	}

	@Override
	public void remove(Path project, Path campaign) throws IOException
	{
		Optional<WorktreeResponse> response = send(WorktreeRequest.Operation.REMOVE, campaign);
		if (response.isEmpty())
			inPlace.remove(project, campaign);
	}

	@Override
	public boolean repair(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		if (GitWorktreeRegistrar.isLinked(project, campaign))
			return true;
		Optional<WorktreeResponse> response = send(WorktreeRequest.Operation.REPAIR, campaign);
		if (response.isEmpty())
			return inPlace.repair(project, campaign);
		return response.get().result().equals(Optional.of(WorktreeRequest.REPAIRED));
	}

	/**
	 * Sends a request and waits for its answer.
	 *
	 * @param operation the operation
	 * @param campaign  the campaign directory
	 * @return the answer of a request that was done, or empty if the command carries out the operation itself
	 * @throws NullPointerException     if {@code campaign} is null
	 * @throws WorktreeRefusedException if the request is refused, or nothing serves the session's requests
	 * @throws WorkspaceException       if the operation fails
	 * @throws IOException              if the request cannot be sent or its answer cannot be read
	 */
	private Optional<WorktreeResponse> send(WorktreeRequest.Operation operation, Path campaign) throws IOException
	{
		requireThat(campaign, "campaign").isNotNull();
		Path name = campaign.toAbsolutePath().normalize().getFileName();
		if (name == null)
			throw new IllegalArgumentException("campaign names no campaign directory: " + campaign);
		Optional<Channel> opened = router.open();
		if (opened.isEmpty())
			return Optional.empty();
		WorktreeResponse response;
		try (Channel channel = opened.get())
		{
			WorktreeRequest request = new WorktreeRequest(operation, name.toString());
			Path requests = channel.requests().toAbsolutePath();
			String id = request.publish(json, requests);
			Path directory = requests.getParent().resolve("responses").resolve(id);
			response = await(channel, directory);
			if (channel.removesResponses())
				TemporaryDirectories.deleteRecursively(directory);
		}
		return switch (response.status())
		{
			case WorktreeRequest.DONE -> Optional.of(response);
			case WorktreeRequest.REFUSED -> throw new WorktreeRefusedException(response.reason());
			default -> throw new WorkspaceException("CAT could not " + operation.label() + " the optimization " +
				"worktree of campaign " + name + ": " + response.reason());
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
	private WorktreeResponse await(Channel channel, Path directory) throws IOException
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
				throw new IOException("The request server ended before it answered the worktree request: " +
					directory.getFileName());
			}
			pause.pause();
		}
	}

	/**
	 * Chooses where the requests of a command go.
	 */
	@FunctionalInterface
	public interface Router
	{
		/**
		 * Opens the channel that the command's requests go through.
		 *
		 * @return the channel, or empty if the command carries out the operations itself
		 * @throws WorktreeRefusedException if the command may not send requests, such as when no request server serves
		 *                                  its session; the message says why and what the user can do
		 * @throws IOException              if the channel cannot be opened
		 */
		Optional<Channel> open() throws IOException;
	}

	/**
	 * Where a command's requests go.
	 */
	public interface Channel extends Closeable
	{
		/**
		 * Returns the directory into which the command writes its requests, beside the {@code responses} directory
		 * that receives their answers.
		 *
		 * @return the directory
		 */
		Path requests();

		/**
		 * Indicates whether the receiver still serves the requests.
		 *
		 * @return {@code false} if the receiver has ended, so that no answer will come
		 * @throws IOException if the receiver's state cannot be read
		 */
		boolean isServed() throws IOException;

		/**
		 * Indicates whether the command removes a request's response directory once it has read the answer. A request
		 * server keeps a response directory until its requester removes it; a nested session's runner removes the nested
		 * session's responses with the nested session.
		 *
		 * @return {@code true} if the command removes it
		 */
		boolean removesResponses();
	}
}

package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import com.holdmyspot.cat.common.runner.WorktreeRequest;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the client through which a harness session's command sends the operations on its optimization worktree's
 * registration.
 */
public final class WorktreeRequestClientTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Verifies that the client reports a failed operation with the receiver's reason, a repair that found no
	 * registration as {@code false}, and a refusal as a refusal.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reportsReceiversAnswers() throws IOException
	{
		// Requirement: R-W17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("worktree-client-"));
			Path requests = Files.createDirectories(root.resolve("requests"));
			Path campaign = Files.createDirectories(root.resolve("project/.cat/sessions/s1/optimizer/c1"));
			Path project = root.resolve("project");
			List<ObjectNode> answers = new ArrayList<>(List.of(answer("failed", "Git broke"),
				answer("done", "").put("result", WorktreeRequest.UNREPAIRABLE), answer("refused", "Not yours")));
			WorktreeRegistrar client = new WorktreeRequestClient(() -> Optional.of(new Channel(requests, true)),
				new Recording(new CopyOnWriteArrayList<>()), JSON, receiver(requests, answers));

			WorkspaceException failure = expectThrows(WorkspaceException.class, () -> client.add(project, campaign));
			boolean repaired = client.repair(project, campaign);
			Exception refusal = expectThrows(IOException.class, () -> client.remove(project, campaign));

			requireThat(failure.getMessage(), "failure").contains("could not add").contains("c1").
				contains("Git broke");
			requireThat(repaired, "repaired").isFalse();
			requireThat(refusal.getClass().getSimpleName(), "refusalType").isEqualTo("WorktreeRefusedException");
			requireThat(refusal.getMessage(), "refusal").isEqualTo("Not yours");
			try (DirectoryStream<Path> responses = Files.newDirectoryStream(root.resolve("responses")))
			{
				requireThat(responses.iterator().hasNext(), "responses").isFalse();
			}
		}
	}

	/**
	 * Verifies that a request fails, instead of waiting forever, once its receiver has stopped serving without
	 * answering.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void failsOnceReceiverEnds() throws IOException
	{
		// Requirement: R-W17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("worktree-client-"));
			Path requests = Files.createDirectories(root.resolve("requests"));
			Path campaign = Files.createDirectories(root.resolve("project/optimizer/c1"));
			WorktreeRegistrar client = new WorktreeRequestClient(() -> Optional.of(new Channel(requests, false)),
				new Recording(new CopyOnWriteArrayList<>()), JSON, () ->
				{
					throw new AssertionError("The client waited for a receiver that ended");
				});

			IOException failure = expectThrows(IOException.class, () -> client.add(root.resolve("project"),
				campaign));

			requireThat(failure.getMessage(), "failure").contains("ended before it answered");
		}
	}

	/**
	 * Verifies that a command that no harness runs carries out each operation itself and sends no request.
	 *
	 * @throws IOException if an operation fails
	 */
	@Test
	public void runsInPlaceWithoutChannel() throws IOException
	{
		// Requirement: R-W17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("worktree-client-"));
			Path campaign = project.resolve("optimizer/c1");
			List<String> calls = new CopyOnWriteArrayList<>();
			WorktreeRegistrar client = new WorktreeRequestClient(Optional::empty, new Recording(calls), JSON, () ->
			{
				throw new AssertionError("The client waited for an answer");
			});

			client.add(project, campaign);
			client.recreate(project, campaign);
			boolean repaired = client.repair(project, campaign);
			client.remove(project, campaign);

			requireThat(calls, "calls").isEqualTo(List.of("add", "recreate", "repair", "remove"));
			requireThat(repaired, "repaired").isTrue();
		}
	}

	/**
	 * Returns a receiver's answer.
	 *
	 * @param status its status
	 * @param reason its reason
	 * @return the answer
	 */
	private static ObjectNode answer(String status, String reason)
	{
		return JSON.createObjectNode().put("format_version", 1).put("status", status).put("reason", reason);
	}

	/**
	 * Returns a pause that answers each request in a requests directory with the next of a list of answers, as a
	 * receiver does while the client waits.
	 *
	 * @param requests the requests directory
	 * @param answers  the answers, in order
	 * @return the pause
	 */
	private static Pause receiver(Path requests, List<ObjectNode> answers)
	{
		return () ->
		{
			try (DirectoryStream<Path> archives = Files.newDirectoryStream(requests))
			{
				for (Path archive : archives)
				{
					String name = archive.getFileName().toString();
					String id = name.substring(0, name.length() - ".tar".length());
					requireThat(id, "id").startsWith(WorktreeRequest.PREFIX);
					Path response = Files.createDirectories(requests.resolveSibling("responses").resolve(id));
					Files.write(response.resolve("response.json"), JSON.writeValueAsBytes(answers.removeFirst().
						put("id", id)));
					Files.delete(archive);
				}
			}
		};
	}

	/**
	 * A channel of a test.
	 *
	 * @param requests the requests directory
	 * @param served   whether the receiver still serves the requests
	 */
	private record Channel(Path requests, boolean served) implements WorktreeRequestClient.Channel
	{
		@Override
		public boolean isServed()
		{
			return served;
		}

		@Override
		public boolean removesResponses()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * A registrar that records the operations that it is asked to carry out, and carries out none.
	 *
	 * @param calls the operations, in order
	 */
	private record Recording(List<String> calls) implements WorktreeRegistrar
	{
		@Override
		public void add(Path project, Path campaign)
		{
			calls.add("add");
		}

		@Override
		public void recreate(Path project, Path campaign)
		{
			calls.add("recreate");
		}

		@Override
		public void remove(Path project, Path campaign)
		{
			calls.add("remove");
		}

		@Override
		public boolean repair(Path project, Path campaign)
		{
			calls.add("repair");
			return true;
		}
	}
}

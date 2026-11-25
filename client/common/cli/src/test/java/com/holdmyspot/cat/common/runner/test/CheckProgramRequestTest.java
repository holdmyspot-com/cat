package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.Commands;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.execution.contract.test.CheckProgramFixtures;
import com.holdmyspot.cat.common.runner.CheckProgramClient;
import com.holdmyspot.cat.common.runner.CheckProgramRequest;
import com.holdmyspot.cat.common.runner.NestedSessionChannel;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.WorktreeResponse;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the request to run a check program outside the sandbox of the session that makes it, which a nested session's
 * runner serves.
 */
public final class CheckProgramRequestTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CheckProgramRequestTest()
	{
	}

	/**
	 * Verifies that a nested session's runner runs a program under the policy that it applied to the session, so that a
	 * path that the session may not read stays unreadable to the program, and that it leaves nothing of the run in the
	 * session's responses.
	 *
	 * @throws IOException          if a file cannot be written or the program cannot run
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void nestedRunnerRunsProgramUnderItsPolicy() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		CheckProgramFixtures.requireBubblewrap();
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("check-request-policy")).toRealPath();
			Path secret = Files.createDirectory(root.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "do not read\n");
			ParentPolicy owner = new ChildPolicy(List.of(root.resolve("session")), List.of(root.resolve("kept")),
				List.of(secret), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), new FileLocks(), 1,
				owner))
			{
				CheckProgram.Result result = CheckProgramFixtures.runDeniedRead(client(parent.requests()), json,
					Files.createDirectory(root.resolve("run")), secretFile, List.of());

				requireThat(result.outcome(), "outcome").withContext(result.observed(), "observed").
					isEqualTo(CheckProgram.Outcome.PASS);
				requireThat(result.observed(), "observed").contains("service after").contains("secret readable: false");
				try (Stream<Path> responses = Files.list(parent.responses()))
				{
					for (Path response : responses.toList())
					{
						requireThat(Files.exists(response.resolve("inputs")), "hasInputs").isFalse();
						requireThat(Files.exists(response.resolve("scratch")), "hasScratch").isFalse();
					}
				}
			}
		}
	}

	/**
	 * Verifies that a path that the request names as held out stays hidden from the program even when the session's
	 * policy lets the session read it, and that the same program reads the path when the request names none.
	 *
	 * @throws IOException          if a file cannot be written or the program cannot run
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void nestedRunnerHidesHeldOutPaths() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		CheckProgramFixtures.requireBubblewrap();
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("check-request-held-out")).toRealPath();
			Path secret = Files.createDirectory(root.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "held out\n");
			ParentPolicy owner = new ChildPolicy(List.of(root.resolve("session")), List.of(root.resolve("kept")),
				List.of(), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), new FileLocks(), 1,
				owner))
			{
				CheckProgramClient client = client(parent.requests());

				CheckProgram.Result visible = CheckProgramFixtures.runDeniedRead(client, json,
					Files.createDirectory(root.resolve("visible")), secretFile, List.of());
				CheckProgram.Result hidden = CheckProgramFixtures.runDeniedRead(client, json,
					Files.createDirectory(root.resolve("hidden")), secretFile, List.of(secret));

				requireThat(visible.outcome(), "visible.outcome").withContext(visible.observed(), "observed").
					isEqualTo(CheckProgram.Outcome.FAIL);
				requireThat(visible.observed(), "visible.observed").contains("secret readable: true");
				requireThat(hidden.outcome(), "hidden.outcome").withContext(hidden.observed(), "observed").
					isEqualTo(CheckProgram.Outcome.PASS);
			}
		}
	}

	/**
	 * Verifies that the contract tool, which runs in a contract author session's sandbox, has the nested session's runner
	 * run its check programs: here the tool's own search path leads only to a Bubblewrap that refuses to start, as the
	 * one inside a nested session's sandbox does, and the runner's policy denies a path that the program tries to read.
	 *
	 * @throws IOException          if a file cannot be written or the program cannot run
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void contractToolRunsProgramsThroughItsRunner() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		CheckProgramFixtures.requireBubblewrap();
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("check-request-contract")).toRealPath();
			Path secret = Files.createDirectory(root.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "do not read\n");
			Path refusingBin = CheckProgramFixtures.refusingBubblewrap(root);
			ParentPolicy owner = new ChildPolicy(List.of(root.resolve("session")), List.of(root.resolve("kept")),
				List.of(secret), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), new FileLocks(), 1,
				owner))
			{
				Map<String, String> environment = Map.of(SessionRequestAccess.REQUESTS_VARIABLE,
					parent.requests().toString(), "PATH", refusingBin.toString());
				CheckProgramEvaluation programs = Commands.contractCheckPrograms(json, Platform.current(), environment,
					Thread::yield);
				PromptTest test = CheckProgramFixtures.promptTest(CheckProgramFixtures.DENIED_READ_PROGRAM);
				TrialRecord record = CheckProgramFixtures.deniedReadRecord(Files.createDirectory(root.resolve("trial")),
					secretFile);

				CheckProgram.Result result = programs.run(test, CheckProgramFixtures.testCase(test), record, 1).
					get(CheckProgramFixtures.CHECK).getFirst();

				requireThat(result.outcome(), "outcome").withContext(result.observed(), "observed").
					isEqualTo(CheckProgram.Outcome.PASS);
				requireThat(result.observed(), "observed").contains("secret readable: false");
			}
		}
	}

	/**
	 * Verifies that a nested session's runner refuses to run a program when it applied no policy that it can state,
	 * rather than running the program unbounded.
	 *
	 * @throws IOException          if a file cannot be written
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 60_000)
	public void nestedRunnerWithoutPolicyRefuses() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("check-request-silent")).toRealPath();
			Path secretFile = Files.writeString(root.resolve("token.txt"), "unused\n");
			try (RequestingNestedSession silent = new RequestingNestedSession(root.resolve("silent"), new FileLocks(), 1))
			{
				try
				{
					CheckProgramFixtures.runDeniedRead(client(silent.requests()), json,
						Files.createDirectory(root.resolve("run")), secretFile, List.of());
					throw new AssertionError("The runner ran a program without a policy to bound it");
				}
				catch (SessionRefusedException e)
				{
					requireThat(e.getMessage(), "message").contains("states no policy");
				}
			}
		}
	}

	/**
	 * Returns requests that choose more than a program run may: another field, a program outside the input, and an
	 * entry beside the input.
	 *
	 * @return the manifest and the extra entry of each request
	 */
	@DataProvider
	public static Object[][] overreachingRequests()
	{
		String program = "\"program\":\"checks/Check.java\"";
		String base = "\"format_version\":1,\"workspace\":\"workspace\",\"arguments\":[],\"held_out\":[]";
		return new Object[][]
		{
			{"{" + base + "," + program + ",\"environment\":{\"PATH\":\"/tmp\"}}", "", "may not choose environment"},
			{"{" + base + ",\"program\":\"../Check.java\"}", "", "normalized relative path"},
			{"{" + base + ",\"program\":\"/etc/passwd\"}", "", "normalized relative path"},
			{"{" + base + "," + program + "}", "extra.txt", "holds only its input"}
		};
	}

	/**
	 * Verifies that the runner refuses a request that names more than the input, a program inside it, arguments and
	 * held-out paths, so that a command cannot choose the executable, the environment or what else the program reads.
	 *
	 * @param manifest the request's manifest
	 * @param extra    the name of an extra entry beside the input, or an empty string
	 * @param reason   what the refusal says
	 * @throws IOException if a file cannot be written
	 */
	@Test(dataProvider = "overreachingRequests", timeOut = 60_000)
	public void runnerRefusesOverreachingRequest(String manifest, String extra, String reason) throws IOException
	{
		// Requirement: R-L21
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path root = scope.add(Files.createTempDirectory("check-request-overreach")).toRealPath();
			ParentPolicy owner = new ChildPolicy(List.of(root.resolve("session")), List.of(root.resolve("kept")),
				List.of(), List.of(), Set.of("api.example.com"), false).asParentPolicy();
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), new FileLocks(), 1,
				owner))
			{
				String id = "check-program-00000000-0000-0000-0000-000000000001";
				Path requests = parent.requests();
				Path temporary = requests.resolve(".tmp-" + id + ".tar");
				try (OutputStream bytes = Files.newOutputStream(temporary);
					TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					archive.putArchiveEntry(new TarArchiveEntry("workspace/"));
					archive.closeArchiveEntry();
					byte[] content = manifest.getBytes(StandardCharsets.UTF_8);
					TarArchiveEntry entry = new TarArchiveEntry("request.json");
					entry.setSize(content.length);
					archive.putArchiveEntry(entry);
					archive.write(content);
					archive.closeArchiveEntry();
					if (!extra.isEmpty())
					{
						TarArchiveEntry file = new TarArchiveEntry(extra);
						file.setSize(0);
						archive.putArchiveEntry(file);
						archive.closeArchiveEntry();
					}
				}
				Files.move(temporary, requests.resolve(id + ".tar"), StandardCopyOption.ATOMIC_MOVE);

				WorktreeResponse response = awaitResponse(parent.responses().resolve(id));

				requireThat(response.status(), "status").isEqualTo("refused");
				requireThat(response.reason(), "reason").contains(reason);
			}
		}
	}

	/**
	 * Verifies that a result that is written as an answer is read back as it was.
	 *
	 * @throws IOException if the result cannot be read
	 */
	@Test
	public void resultRoundTrips() throws IOException
	{
		// Requirement: R-L21
		CheckProgram.Result result = new CheckProgram.Result(CheckProgram.Outcome.ERROR, "line one\nline \"two\"",
			Duration.ofMillis(1_234));

		String document = CheckProgramRequest.encode(json, result);

		requireThat(CheckProgramRequest.decode(json, document), "decoded").
			isEqualTo(result);
	}

	/**
	 * Returns documents that are not the answer that a receiver writes.
	 *
	 * @return the documents
	 */
	@DataProvider
	public static Object[][] malformedResults()
	{
		return new Object[][]
		{
			{"not json"},
			{"{\"format_version\":2,\"outcome\":\"PASS\",\"observed\":\"\",\"elapsed_nanos\":1}"},
			{"{\"format_version\":1,\"outcome\":\"MAYBE\",\"observed\":\"\",\"elapsed_nanos\":1}"},
			{"{\"format_version\":1,\"outcome\":\"PASS\",\"elapsed_nanos\":1}"}
		};
	}

	/**
	 * Verifies that a command refuses a document that is not the answer that a receiver writes, rather than deciding a
	 * trial by a result that it guessed.
	 *
	 * @param document the document
	 */
	@Test(dataProvider = "malformedResults")
	public void malformedResultIsRefused(String document)
	{
		// Requirement: R-L21
		try
		{
			CheckProgramRequest.decode(json, document);
			throw new AssertionError("The document was read: " + document);
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("check program result");
		}
	}

	/**
	 * Waits for the runner to answer a request.
	 *
	 * @param directory the request's response directory
	 * @return the answer
	 * @throws IOException if the answer cannot be read
	 */
	private WorktreeResponse awaitResponse(Path directory) throws IOException
	{
		while (true)
		{
			Optional<WorktreeResponse> response = WorktreeResponse.read(json, directory);
			if (response.isPresent())
				return response.get();
			Thread.yield();
		}
	}

	/**
	 * Creates a client that sends its requests into a nested session's requests directory.
	 *
	 * @param requests the nested session's requests directory
	 * @return the client
	 */
	private CheckProgramClient client(Path requests)
	{
		return new CheckProgramClient(() -> Optional.of(new NestedSessionChannel(requests)), json, Thread::yield);
	}
}

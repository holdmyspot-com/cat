package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorMetadata;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorRuntime;
import com.holdmyspot.cat.common.optimizer.cli.PeerTrialSessions;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import com.holdmyspot.cat.common.optimizer.port.test.FakeProfileInventory;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.RetainedNestedSessionClient;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder.Route;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the owner's typed exchange with a fake installed evaluator.
 */
public final class PeerTrialSessionsTest
{
	/**
	 * Creates the test.
	 */
	public PeerTrialSessionsTest()
	{
	}

	/**
	 * Verifies that the owner sends a retained trial through its broker and acknowledges its observer's evidence-based
	 * decision before returning the evaluator's terminal result.
	 *
	 * @throws IOException          if the test's files or streams fail
	 * @throws ExecutionException   if the evaluator exchange fails
	 * @throws InterruptedException if the test is interrupted
	 * @throws TimeoutException     if the exchange does not end
	 */
	@Test
	public void preservesClaimAndDecidesFromPeerEvidence()
		throws IOException, ExecutionException, InterruptedException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "peer-trial-")).toRealPath();
			Path artifact = project.resolve("installed-claude");
			Path requests = project.resolve("owner/requests");
			Path results = project.resolve("results");
			Path staged = Files.createDirectory(project.resolve("staged"));
			UUID requestId = UUID.randomUUID();
			UUID generation = UUID.randomUUID();
			UUID sessionId = UUID.randomUUID();
			RetainedTrialClaim claim = new RetainedTrialClaim(requestId,
				new Identity(generation, Optional.of(new HarnessSession("claude", sessionId.toString()))), 1);
			JsonMapper json = jvm.json();
			Path retained = results.resolve("claude").resolve(sessionId.toString());
			Path transcript = evidence(retained);
			EvaluatorProcess peer = new EvaluatorProcess(peerRecords(json, retained, transcript));
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(Thread::yield,
				Clock.systemUTC(), builder ->
				{
					String launcher = "prompt-optimizer";
					if (Platform.current().isWindows())
						launcher += ".cmd";
					requireThat(builder.command(), "command").isEqualTo(List.of(
						artifact.resolve("bin").resolve(launcher).toString(), "evaluator-trial"));
					requireThat(builder.directory().toPath(), "directory").isEqualTo(project);
					requireThat(builder.environment().containsKey(SessionRequestAccess.REQUESTS_VARIABLE),
						"routeInEnvironment").isFalse();
					return peer;
				});
			try (TestJvmScope scope = new TestJvmScope(jvm.temporaryDirectory(), executables))
			{
				RetainedNestedSessionClient retainedTrials = new RetainedNestedSessionClient(Optional::empty,
					scope.json(), Thread::yield);
				PeerTrialSessions sessions = new PeerTrialSessions(scope, artifact, metadata(), Route.SERVER, requests,
					project, results, List::of, HarnessCapacity.unlimited(), retainedTrials);
				SessionRequest request = new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, staged,
					List.of("first", "second"), List.of(), Duration.ofSeconds(20), new TrialProfile("model", "low")).
					withTurnObserver(new TurnObserver()
					{
						@Override
						public boolean endAfter(int turn, SessionResult.Completed result) throws IOException
						{
							requireThat(turn, "turn").isEqualTo(1);
							requireThat(result.responses(), "replies").isEqualTo(List.of(List.of("first reply")));
							SessionEvidence observed = sessions.readEvidence(result.transcript());
							requireThat(observed.entries(), "nativeActivity").isEqualTo(List.of(
								new SessionEvidence.Hook(1, "root", "SessionStart", "succeeded")));
							return true;
						}

						@Override
						public Map<String, Integer> settledAssertions()
						{
							return Map.of("reply", 1);
						}
					});

				SessionHandle handle = sessions.startTrial(request, requestId, Optional.of(claim));
				SessionResult result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

				requireThat(result instanceof SessionResult.Completed, "completed").isTrue();
				SessionResult.Completed complete = (SessionResult.Completed) result;
				requireThat(complete.turnsNotRun(), "skipped").isEqualTo(List.of(2));
				requireThat(complete.settledAssertions(), "settled").isEqualTo(Map.of("reply", 1));
				List<String> messages = peer.received.toString(UTF_8).lines().toList();
				requireThat(messages.size(), "messages").isEqualTo(2);
				JsonNode start = json.readTree(messages.getFirst());
				requireThat(start.path("request_id").asString(), "originalRequest").isEqualTo(requestId.toString());
				requireThat(start.path("requests").asString(), "ownerChannel").isEqualTo(requests.toString());
				requireThat(start.path("route").asString(), "ownerRoute").isEqualTo("SERVER");
				requireThat(start.path("harness").asString(), "peerHarness").isEqualTo("claude");
				requireThat(start.path("claim").path("slot_generation").asString(), "generation").
					isEqualTo(generation.toString());
				requireThat(start.path("claim").path("session_id").asString(), "retainedSession").
					isEqualTo(sessionId.toString());
				JsonNode decision = json.readTree(messages.get(1));
				requireThat(decision.path("type").asString(), "decisionType").isEqualTo("decision");
				requireThat(decision.path("progress").path("ended_early").asBoolean(), "earlyDecision").isTrue();
			}
		}
	}

	/**
	 * Provides the evaluator's trial metadata.
	 *
	 * @return the selected evaluator
	 */
	private static EvaluatorMetadata metadata()
	{
		FakeProfileInventory profiles = new FakeProfileInventory();
		return new EvaluatorMetadata(Harness.CLAUDE,
			new ProfileEvidence(profiles.inventory(), profiles.ranking(), Map.of()), profiles.roleProfile(), List.of(),
			"0".repeat(64), List.of(), new EvaluatorRuntime("test-1.0", 1, "1.0", 1, true, HostLogin.UNKNOWN),
			Optional.empty());
	}

	/**
	 * Writes one retained trial's effects and activity before its turn is exposed.
	 *
	 * @param retained the session results folder
	 * @return the retained transcript
	 * @throws IOException if a fixture file cannot be created
	 */
	private static Path evidence(Path retained) throws IOException
	{
		Files.createDirectories(retained.resolve("project"));
		Files.createDirectories(retained.resolve("initial-project"));
		Path artifacts = Files.createDirectories(retained.resolve("artifacts"));
		Path transcript = Files.writeString(artifacts.resolve("events.jsonl"), "{}\n");
		Files.writeString(artifacts.resolve("evaluator-evidence.json"), """
			{"format_version":1,
			"entries":[{"type":"hook","entry":{"turn":1,"agent":"root","hook":"SessionStart","status":"succeeded"}}],
			"received":[]}
			""");
		return transcript;
	}

	/**
	 * Describes the evaluator's first completed turn and then its result after the owner ends it early.
	 *
	 * @param json       the fixture mapper
	 * @param retained   the session's results folder
	 * @param transcript its event archive
	 * @return the evaluator's event stream
	 */
	private static String peerRecords(JsonMapper json, Path retained, Path transcript)
	{
		ObjectNode completed = json.createObjectNode().put("type", "completed").put("transcript", transcript.toString()).
			put("effects", retained.resolve("project").toString()).
			put("initial_project", retained.resolve("initial-project").toString()).
			put("prompt_sources", "none").put("requested_turns", 2).put("ended_early", false);
		completed.putArray("responses").addArray().add("first reply");
		completed.putArray("windows");
		completed.putArray("tool_calls");
		completed.putNull("usage");
		completed.putObject("settled_assertions");
		ObjectNode turn = json.createObjectNode().put("type", "turn").put("turn", 1);
		turn.set("session", completed);
		ObjectNode result = json.createObjectNode().put("type", "result");
		ObjectNode terminal = completed.deepCopy().put("ended_early", true);
		terminal.putObject("settled_assertions").put("reply", 1);
		result.set("session", terminal);
		return json.writeValueAsString(turn) + '\n' + json.writeValueAsString(result) + '\n';
	}

	/**
	 * Replays deterministic event bytes and captures the records the owner sends.
	 */
	private static final class EvaluatorProcess extends Process
	{
		private final ByteArrayOutputStream received = new ByteArrayOutputStream();
		private final InputStream replies;

		/**
		 * Creates a replay process.
		 *
		 * @param records the evaluator's replies
		 */
		private EvaluatorProcess(String records)
		{
			replies = new ByteArrayInputStream(records.getBytes(UTF_8));
		}

		@Override
		public OutputStream getOutputStream()
		{
			return received;
		}

		@Override
		public InputStream getInputStream()
		{
			return replies;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			return 0;
		}

		@Override
		public int exitValue()
		{
			return 0;
		}

		@Override
		public void destroy()
		{
		}
	}
}

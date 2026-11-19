package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract.Inputs;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionProtocolContract.Observation;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared requested-session protocol contract through the production harness bridge.
 */
public final class HarnessRunnerBridgeProtocolContractTest
{
	/**
	 * Verifies the bridge completes ordered turns and retains the project and transcript named by its receipt.
	 *
	 * @throws IOException if the bridge or retained evidence cannot be read
	 */
	@Test
	public void completesNormalRequestedSession() throws IOException
	{
		RequestedSessionProtocolContract.verifiesNormalRequestedSession(
			HarnessRunnerBridgeProtocolContractTest::runBridgeSession);
	}

	/**
	 * Runs the controlled session through the options-aware production bridge with a real run manager.
	 *
	 * @param inputs the controlled session inputs
	 * @return the session evidence
	 * @throws IOException if the bridge fails
	 */
	private static Observation runBridgeSession(Inputs inputs) throws IOException
	{
		Path runRoot = Files.createDirectories(inputs.runRoot());
		FileLocks fileLocks = new FileLocks();
		HarnessRunManager manager = new HarnessRunManager(fileLocks, runRoot, Clock.systemUTC(),
			Yielding.fileReplacer());
		HarnessRun run = manager.create("codex");
		Path project = Files.createDirectories(run.directory().resolve("project"));
		manager.initialize(run, inputs.artifact(), project, "scripted", List.of("scripted"));
		Files.writeString(project.resolve("result.txt"), "result");
		Files.writeString(Files.createDirectories(run.directory().resolve("home")).resolve("login.json"), "login");
		HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact", inputs.artifact().toString(),
			"--results", runRoot.toString(), "--project", project.toString(), "--model", "model-a", "--effort",
			"effort-a", "--session-requests", "1", "--output", inputs.transcript().toString(),
			"--receipt", inputs.receipt().toString()}, "codex", HarnessTemporaryRoot.of(inputs.root()));
		List<String> receivedPrompts = new ArrayList<>();
		List<Turn> script = inputs.replies().stream().map(Turn::replies).toList();
		StringWriter output = new StringWriter();
		JsonMapper json = JsonMapper.builder().build();

		HarnessRunnerBridge.serve(() -> new HarnessRunSession(run,
			new ScriptedProcess(script, receivedPrompts), new TestHarnessRunnerScope()), manager, options, json,
			Yielding.fileReplacer(), new StringReader(inputs.records()), output,
			(_, _, _) ->
			{
				throw new AssertionError("The normal bridge session made an unexpected child request");
			});

		return new Observation(inputs.transcript(), inputs.receipt(), run.directory(), receivedPrompts);
	}

	/**
	 * Creates the test.
	 */
	public HarnessRunnerBridgeProtocolContractTest()
	{
	}
}

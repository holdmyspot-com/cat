package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.optimization.ReproductionState;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a typed harness evaluation through its native bridge launcher.
 */
public final class NativeHarnessEvaluationRunner implements HarnessEvaluationRunner
{
	private final JsonMapper json;

	/**
	 * Creates a native adapter that reads retained-evidence receipts with the supplied operation-owned JSON mapper.
	 *
	 * @param json the operation-owned JSON mapper
	 */
	public NativeHarnessEvaluationRunner(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Starts one isolated harness bridge, supplies its complete JSONL input, and reads the retained-evidence receipt.
	 *
	 * @param request the complete harness evaluation request
	 * @return the retained evidence and native-process timing
	 * @throws IOException if the bridge cannot start, does not complete, or omits retained evidence
	 */
	@Override
	public HarnessEvaluationExecution execute(HarnessEvaluationRequest request) throws IOException
	{
		requireThat(request, "request").isNotNull();
		Path transcriptParent = request.transcript().getParent();
		if (transcriptParent == null)
			throw new IOException("Evaluation transcript has no parent directory: " + request.transcript());
		Path receiptParent = request.receipt().getParent();
		if (receiptParent == null)
			throw new IOException("Evaluation receipt has no parent directory: " + request.receipt());
		Files.createDirectories(transcriptParent);
		Files.createDirectories(receiptParent);
		List<String> command = command(request);
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(request.project().toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(request.transcript().toFile());
		try (Process process = builder.start())
		{
			long startedAtNanos = System.nanoTime();
			try
			{
				try (OutputStream input = process.getOutputStream())
				{
					input.write(request.input().getBytes(StandardCharsets.UTF_8));
				}
				WaitResult waitResult = waitFor(process);
				try
				{
					if (waitResult.exitCode() != 0)
					{
						throw new IOException("Harness evaluation failed with exit code " + waitResult.exitCode() + ": " +
							command);
					}
					return execution(request, startedAtNanos, waitResult);
				}
				catch (IOException e)
				{
					if (waitResult.interrupted())
						throw new HarnessEvaluationFailure(e.getMessage(), e, true);
					throw e;
				}
			}
			finally
			{
				if (process.isAlive())
					HarnessProcessTermination.terminateImmediately(process);
			}
		}
	}

	/**
	 * Creates the harness-specific bridge command from one typed evaluation request.
	 *
	 * @param request the complete harness evaluation request
	 * @return the shell-free bridge command
	 */
	private static List<String> command(HarnessEvaluationRequest request)
	{
		List<String> result = new ArrayList<>(List.of(request.launcher().toString(), "--artifact",
			request.artifact().toString(), "--project", request.project().toString(), "--run-root",
			request.project().resolve("runner-runs").toString(), "--model", request.model(), "--effort",
			request.effort(), "--output", request.transcript().toString(), "--receipt", request.receipt().toString(),
			"--keep-run"));
		if (request.installCat())
		{
			result.add("--install-cat");
			result.add("--exclude-user-rules");
		}
		for (Path evidence : request.readOnlyEvidence())
		{
			result.add("--read-only-evidence");
			result.add(evidence.toString());
		}
		return List.copyOf(result);
	}

	/**
	 * Reads the preferred complete receipt, or projects a legacy receipt into non-certifiable execution evidence.
	 *
	 * @param request the completed evaluation request
	 * @param startedAtNanos the monotonic start time
	 * @param waitResult the terminal process result
	 * @return complete execution evidence
	 * @throws IOException if no valid receipt exists
	 */
	private HarnessEvaluationExecution execution(HarnessEvaluationRequest request, long startedAtNanos,
		WaitResult waitResult) throws IOException
	{
		try
		{
			HarnessRunReceipt.Receipt receipt = HarnessRunReceipt.readComplete(json, request.receipt());
			return new HarnessEvaluationExecution(receipt.result(), startedAtNanos, System.nanoTime(),
				waitResult.interrupted(), receipt.harnessVersion(), receipt.runnerVersion(), receipt.usage(),
				receipt.reproductionState(), receipt.evidenceFingerprints());
		}
		catch (IOException completeFailure)
		{
			try
			{
				HarnessEvaluationResult result = HarnessRunReceipt.read(json, request.receipt());
				return new HarnessEvaluationExecution(result, startedAtNanos, System.nanoTime(), waitResult.interrupted(),
					"unknown", "unknown", ProviderUsage.unavailable(), ReproductionState.UNAVAILABLE,
					Map.of("retained_project", Fingerprints.ofArtifact(result.retainedProject()), "raw_result",
						Fingerprints.ofFile(result.rawResult())));
			}
			catch (IOException legacyFailure)
			{
				legacyFailure.addSuppressed(completeFailure);
				throw legacyFailure;
			}
		}
	}

	/**
	 * Waits for one runner process to finish even when its owner requests interruption.
	 *
	 * @param process the owned harness bridge process
	 * @return the bridge exit code and whether interruption was requested while it ran
	 */
	private static WaitResult waitFor(Process process)
	{
		boolean interrupted = false;
		while (true)
		{
			try
			{
				return new WaitResult(process.waitFor(), interrupted);
			}
			catch (InterruptedException _)
			{
				interrupted = true;
			}
		}
	}

	/**
	 * Retains the terminal process result and interruption request until the operation receipt is durable.
	 *
	 * @param exitCode the harness bridge exit code
	 * @param interrupted whether the operation owner requested interruption while the bridge was active
	 */
	private record WaitResult(int exitCode, boolean interrupted)
	{
	}
}

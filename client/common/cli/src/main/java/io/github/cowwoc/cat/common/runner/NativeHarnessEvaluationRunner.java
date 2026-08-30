package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
				int exitCode = waitFor(process, request.launcher());
				if (exitCode != 0)
					throw new IOException("Harness evaluation failed with exit code " + exitCode + ": " + command);
				HarnessEvaluationResult result = HarnessRunReceipt.read(json, request.receipt());
				return new HarnessEvaluationExecution(result, startedAtNanos, System.nanoTime());
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
	 * Waits for one runner process and terminates it when the evaluator is interrupted.
	 *
	 * @param process the owned harness bridge process
	 * @param launcher the launcher used in a failure diagnostic
	 * @return the bridge exit code
	 * @throws IOException if waiting is interrupted
	 */
	private static int waitFor(Process process, Path launcher) throws IOException
	{
		try
		{
			return process.waitFor();
		}
		catch (InterruptedException e)
		{
			HarnessProcessTermination.terminateImmediately(process);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for harness evaluation: " + launcher, e);
		}
	}
}

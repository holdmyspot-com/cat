package com.holdmyspot.cat.common.test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * A fixture child that announces readiness and follows one selected EOF lifecycle.
 * It starts no other process and consumes no harness protocol.
 */
public final class LifecycleProcess
{
	/**
	 * The line emitted once the child is running its own code.
	 */
	public static final String READY = "ready";

	/**
	 * Announces readiness and follows the selected EOF lifecycle.
	 *
	 * @param args one {@link ProcessEofContract.EofScenario} name
	 * @throws IOException          if stdin cannot be read
	 * @throws InterruptedException if the keep-alive fixture is interrupted
	 */
	public static void main(String[] args) throws IOException, InterruptedException
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Expected one lifecycle scenario");
		System.out.println(READY);
		System.out.flush();
		if (args[0].equals("COMPLETION_STATUS"))
		{
			String status = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
			System.exit(Integer.parseInt(status));
		}
		ProcessEofContract.EofScenario scenario = ProcessEofContract.EofScenario.valueOf(args[0]);
		if (scenario == ProcessEofContract.EofScenario.OUTPUT_EOF_BEFORE_EXIT)
			System.out.close();
		System.in.transferTo(OutputStream.nullOutputStream());
		if (scenario == ProcessEofContract.EofScenario.INPUT_EOF_KEEP_ALIVE)
			new CountDownLatch(1).await();
	}

	/**
	 * Prevents construction of the fixture entry point.
	 */
	private LifecycleProcess()
	{
	}
}

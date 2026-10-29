package com.example.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs background jobs.
 */
public final class JobScheduler implements AutoCloseable
{
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	/**
	 * Creates a scheduler that runs the server's nightly jobs.
	 *
	 * @param database   the database the jobs read
	 * @param mailSender the service the jobs send mail with
	 */
	public JobScheduler(Database database, MailSender mailSender)
	{
		executor.scheduleAtFixedRate(() -> mailSender.send("ops@example.com", "Nightly report", database.toString()),
			1, 1, TimeUnit.DAYS);
	}

	@Override
	public void close()
	{
		executor.shutdownNow();
	}
}

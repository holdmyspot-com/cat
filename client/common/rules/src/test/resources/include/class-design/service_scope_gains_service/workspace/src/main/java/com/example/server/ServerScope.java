package com.example.server;

/**
 * Creates the server's services, gives them to the rest of the server, and closes them when the server stops.
 */
public final class ServerScope implements AutoCloseable
{
	private final Database database;
	private final MailSender mailSender;
	private final JobScheduler jobScheduler;

	/**
	 * Creates the services.
	 *
	 * @param databaseUrl the address of the database
	 * @param smtpHost    the host that sends mail
	 */
	public ServerScope(String databaseUrl, String smtpHost)
	{
		this.database = new Database(databaseUrl);
		this.mailSender = new MailSender(smtpHost);
		this.jobScheduler = new JobScheduler(database, mailSender);
	}

	/**
	 * @return the database
	 */
	public Database database()
	{
		return database;
	}

	/**
	 * @return the service that sends mail
	 */
	public MailSender mailSender()
	{
		return mailSender;
	}

	/**
	 * @return the service that runs background jobs
	 */
	public JobScheduler jobScheduler()
	{
		return jobScheduler;
	}

	/**
	 * Closes the services, each before the services it uses.
	 */
	@Override
	public void close()
	{
		jobScheduler.close();
		mailSender.close();
		database.close();
	}
}

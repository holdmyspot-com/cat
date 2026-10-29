package com.example.server;

/**
 * Sends mail.
 */
public final class MailSender implements AutoCloseable
{
	private final String smtpHost;

	/**
	 * Creates a sender.
	 *
	 * @param smtpHost the host that sends mail
	 */
	public MailSender(String smtpHost)
	{
		this.smtpHost = smtpHost;
	}

	/**
	 * Sends a message.
	 *
	 * @param to      the recipient's address
	 * @param subject the subject
	 * @param body    the text
	 */
	public void send(String to, String subject, String body)
	{
		System.out.println("Mail via " + smtpHost + " to " + to + ": " + subject);
	}

	@Override
	public void close()
	{
	}
}

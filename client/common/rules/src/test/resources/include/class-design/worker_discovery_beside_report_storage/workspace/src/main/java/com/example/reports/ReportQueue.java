package com.example.reports;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores reports until they have been delivered to every worker.
 */
public final class ReportQueue
{
	private static final String PENDING = ".pending";
	private static final String DELIVERED = ".delivered";

	private final Path storeDirectory;
	private final List<URI> workers;
	private final HttpClient client;

	/**
	 * Creates a queue.
	 *
	 * @param storeDirectory the directory that holds the reports
	 * @param workers        the addresses of the workers that receive each report
	 * @param client         the client that sends reports to the workers
	 */
	public ReportQueue(Path storeDirectory, List<URI> workers, HttpClient client)
	{
		this.storeDirectory = storeDirectory;
		this.workers = List.copyOf(workers);
		this.client = client;
	}

	/**
	 * Stores a report until it is delivered.
	 *
	 * @param id     the report's identifier, unique within the queue
	 * @param report the report's content
	 * @throws IOException if the report cannot be stored
	 */
	public void submit(String id, String report) throws IOException
	{
		Files.writeString(storeDirectory.resolve(id + PENDING), report);
	}

	/**
	 * Sends each pending report to every worker, then marks it delivered.
	 *
	 * @throws IOException          if a report cannot be read or moved, or a worker refuses it
	 * @throws InterruptedException if the thread is interrupted while sending
	 */
	public void deliverPending() throws IOException, InterruptedException
	{
		for (Path pending : pendingReports())
		{
			String report = Files.readString(pending);
			for (URI worker : workers)
				send(worker, report);
			markDelivered(pending);
		}
	}

	/**
	 * @return the stored reports that are not yet delivered, in order of their identifiers
	 * @throws IOException if the store cannot be listed
	 */
	private List<Path> pendingReports() throws IOException
	{
		try (Stream<Path> files = Files.list(storeDirectory))
		{
			return files.filter(file -> file.getFileName().toString().endsWith(PENDING)).sorted().toList();
		}
	}

	/**
	 * Marks a report delivered.
	 *
	 * @param pending the pending report
	 * @throws IOException if the report cannot be moved
	 */
	private void markDelivered(Path pending) throws IOException
	{
		String name = pending.getFileName().toString();
		String id = name.substring(0, name.length() - PENDING.length());
		Files.move(pending, pending.resolveSibling(id + DELIVERED), StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Sends a report to a worker.
	 *
	 * @param worker the worker's address
	 * @param report the report's content
	 * @throws IOException          if the request fails or the worker refuses the report
	 * @throws InterruptedException if the thread is interrupted while sending
	 */
	private void send(URI worker, String report) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(worker).POST(BodyPublishers.ofString(report)).build();
		HttpResponse<Void> response = client.send(request, BodyHandlers.discarding());
		if (response.statusCode() != 200)
			throw new IOException(worker + " refused the report with status " + response.statusCode());
	}
}

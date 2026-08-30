package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Owns durable append-only evaluation-attempt ledgers and their compact lookup indexes.
 */
public final class AttemptLedger
{
	/**
	 * Prevents instantiation.
	 */
	private AttemptLedger()
	{
	}

	/**
	 * Reads the ledger records in their recorded order.
	 *
	 * @param run the evaluation record that names the ledger
	 * @param runPath the evaluation-record path
	 * @return the parsed attempt records
	 * @throws IOException if the ledger is absent, contains a blank record, or contains malformed JSON
	 */
	public static ArrayNode read(ObjectNode run, Path runPath) throws IOException
	{
		Path ledger = ledgerPath(run, runPath);
		if (!Files.isRegularFile(ledger))
			throw new IOException("Attempt ledger is absent: " + ledger);

		ArrayNode result = PromptEvaluation.newArray();
		try (BufferedReader reader = Files.newBufferedReader(ledger, StandardCharsets.UTF_8))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (line.isBlank())
					throw new IOException("Attempt ledger contains a blank record: " + ledger);
				try
				{
					result.add(PromptEvaluation.object(PromptEvaluation.parseJson(line), "attempt ledger record"));
				}
				catch (JacksonException e)
				{
					throw new IOException("Attempt ledger contains invalid JSON: " + ledger, e);
				}
			}
		}
		return result;
	}

	/**
	 * Returns the run's compact trial-and-testcase lookup index.
	 *
	 * @param run the evaluation record
	 * @param runPath the evaluation-record path
	 * @return the lookup index
	 * @throws IOException if the record lacks an object-valued index
	 */
	public static ObjectNode index(ObjectNode run, Path runPath) throws IOException
	{
		if (!run.path("attempt_index").isObject())
			throw new IOException("Evaluation record lacks its required attempt index: " + runPath);
		return PromptEvaluation.object(run.path("attempt_index"), "attempt index");
	}

	/**
	 * Appends one fully constructed attempt and forces the bytes to durable storage.
	 *
	 * @param run the evaluation record that names the ledger
	 * @param runPath the evaluation-record path
	 * @param attempt the immutable attempt record
	 * @throws IOException if the ledger cannot be appended
	 */
	public static void append(ObjectNode run, Path runPath, ObjectNode attempt) throws IOException
	{
		Path ledger = ledgerPath(run, runPath);
		byte[] encoded = (PromptEvaluation.jsonText(attempt) + System.lineSeparator()).
			getBytes(StandardCharsets.UTF_8);
		try (FileChannel channel = FileChannel.open(ledger, CREATE, APPEND, WRITE))
		{
			channel.write(ByteBuffer.wrap(encoded));
			channel.force(true);
		}
	}

	/**
	 * Atomically replaces the ledger after an evaluator-owned durable-path rewrite.
	 *
	 * @param run the evaluation record that names the ledger
	 * @param runPath the evaluation-record path
	 * @param attempts the rewritten attempt records
	 * @throws IOException if the replacement cannot be persisted
	 */
	public static void rewrite(ObjectNode run, Path runPath, ArrayNode attempts) throws IOException
	{
		Path ledger = ledgerPath(run, runPath);
		Path parent = ledger.getParent();
		if (parent == null)
			throw new IOException("Attempt ledger has no parent directory: " + ledger);
		Path temporary = Files.createTempFile(parent, ".prompt-evaluation-attempts-", ".jsonl");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, WRITE))
			{
				for (JsonNode attempt : attempts)
					channel.write(ByteBuffer.wrap((PromptEvaluation.jsonText(attempt) +
						System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
				channel.force(true);
			}
			Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Resolves the ledger path declared by an evaluation record.
	 *
	 * @param run the evaluation record that names the ledger
	 * @param runPath the evaluation-record path
	 * @return the normalized ledger path
	 * @throws IOException if the record has no ledger path
	 */
	private static Path ledgerPath(ObjectNode run, Path runPath) throws IOException
	{
		return PromptEvaluation.path(PromptEvaluation.text(run, "attempt_ledger", runPath));
	}
}

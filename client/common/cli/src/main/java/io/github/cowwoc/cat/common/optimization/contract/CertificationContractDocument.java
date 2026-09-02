package io.github.cowwoc.cat.common.optimization.contract;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes bounded, strict certification-contract JSON documents.
 */
public final class CertificationContractDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 1024 * 1024;

	/**
	 * Prevents construction of this static document boundary.
	 */
	private CertificationContractDocument()
	{
	}

	/**
	 * Reads and deterministically validates one contract document.
	 *
	 * @param json the JSON codec
	 * @param source the contract document
	 * @return the ready or blocked freeze result
	 * @throws IOException if JSON is malformed, exceeds its bound, or contains an unsupported field or domain value
	 */
	public static CertificationContract.Result read(JsonMapper json, Path source) throws IOException
	{
		return CertificationContract.freeze(readDraft(json, source));
	}

	/**
	 * Reads one strict intermediate extraction draft without requiring it to be complete enough to freeze.
	 *
	 * @param json the JSON codec
	 * @param source the draft document
	 * @return the parsed draft
	 * @throws IOException if JSON is malformed, exceeds its bound, or contains an unsupported field or domain value
	 */
	public static CertificationContract.Draft readDraft(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Certification contract");
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
				DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			return strictJson.readValue(contents, CertificationContract.Draft.class);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid certification contract JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Atomically writes one frozen contract in canonical order.
	 *
	 * @param json the JSON codec
	 * @param destination the output document
	 * @param contract the frozen contract
	 * @throws IOException if serialization or atomic persistence fails
	 */
	public static void write(JsonMapper json, Path destination, CertificationContract contract) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(contract, "contract must not be null");
		writeDraft(json, destination, contract.content());
	}

	/**
	 * Atomically writes one intermediate extraction draft without claiming that it is ready to freeze.
	 *
	 * @param json the JSON codec
	 * @param destination the output document
	 * @param draft the draft to persist
	 * @throws IOException if serialization or atomic persistence fails
	 */
	public static void writeDraft(JsonMapper json, Path destination, CertificationContract.Draft draft)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(draft, "draft must not be null");
		try
		{
			String contents = json.writerWithDefaultPrettyPrinter().writeValueAsString(draft);
			RecordStore.writeText(destination, contents + System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize certification contract draft: " + destination, e);
		}
	}
}

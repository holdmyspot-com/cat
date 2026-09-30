package io.github.cowwoc.cat.common.optimizer.contract;

import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses only a complete, schema-valid proposal JSON graph.
 */
public final class ContractProposalParser
{
	private final JsonMapper json = ContractJson.mapper();

	/**
	 * Parses and validates proposal bytes.
	 *
	 * @param content the complete proposal encoded as UTF-8 JSON
	 * @return the validated proposal graph
	 * @throws IOException if the JSON or proposal graph is invalid
	 */
	public ContractProposal parse(byte[] content) throws IOException
	{
		return parse(content, false);
	}

	/**
	 * Parses a persisted proposal that may use a historical formal procedure.
	 *
	 * @param content immutable proposal JSON
	 * @return the validated historical or current proposal
	 * @throws IOException if the JSON or proposal graph is invalid
	 */
	ContractProposal parseStored(byte[] content) throws IOException
	{
		return parse(content, true);
	}

	/**
	 * Parses and validates one proposal for its new or historical use.
	 *
	 * @param content complete proposal JSON
	 * @param stored whether this proposal is already persisted historical evidence
	 * @return the validated proposal
	 * @throws IOException if parsing or validation fails
	 */
	private ContractProposal parse(byte[] content, boolean stored) throws IOException
	{
		if (content == null)
			throw new NullPointerException("content");
		try
		{
			ContractProposal proposal = json.readValue(content, ContractProposal.class);
			if (stored)
				ContractValidator.validateStoredProposal(proposal);
			else
				ContractValidator.validateProposal(proposal);
			return proposal;
		}
		catch (Exception e)
		{
			throw new IOException("Invalid contract proposal: " + e.getMessage(), e);
		}
	}
}

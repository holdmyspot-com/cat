package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides normalized ordered-output facts from a replay manifest without making output decisions.
 */
final class OutputManifestFacts
{
	/**
	 * Prevents construction of this static fact utility.
	 */
	private OutputManifestFacts()
	{
	}

	/**
	 * Returns the next output whose retained-or-discarded disposition has not been recorded.
	 *
	 * @param manifest the unfinalized replay manifest
	 * @return the next undecided output identifier
	 * @throws IOException if every output has already been decided
	 */
	static String nextUndecided(BatchManifest manifest) throws IOException
	{
		Set<String> decidedOutputs = new HashSet<>(discarded(manifest));
		manifest.outputWitnesses().forEach(witness -> decidedOutputs.add(witness.outputId()));
		return manifest.outputs().stream().map(Output::id).filter(output -> !decidedOutputs.contains(output)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay has already decided every output"));
	}

	/**
	 * Returns the manifest's discarded outputs, treating an omitted field in an older manifest as empty.
	 *
	 * @param manifest the replay manifest
	 * @return the declared or implied discarded outputs
	 */
	static List<String> discarded(BatchManifest manifest)
	{
		if (manifest.discardedOutputs() == null)
			return List.of();
		return manifest.discardedOutputs();
	}

	/**
	 * Returns a closed retained-output witness.
	 *
	 * @param manifest the batch manifest
	 * @param outputId the output identifier
	 * @return the witness
	 * @throws IOException if the output was not retained
	 */
	static OutputWitness witness(BatchManifest manifest, String outputId) throws IOException
	{
		return manifest.outputWitnesses().stream().filter(witness -> witness.outputId().equals(outputId)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay manifest does not contain retained output: " + outputId));
	}

	/**
	 * Returns the position of an output in the final rewritten history.
	 *
	 * @param outputs the declared outputs
	 * @param outputId the output identifier
	 * @return the zero-based output position
	 */
	static int index(List<Output> outputs, String outputId)
	{
		for (int index = 0; index < outputs.size(); ++index)
		{
			if (outputs.get(index).id().equals(outputId))
				return index;
		}
		throw new AssertionError("Unknown topic-replay output: " + outputId);
	}

	/**
	 * Describes one logical output using both its stable identifier and reader-facing responsibility.
	 *
	 * @param output the output to describe
	 * @return a readable output description
	 */
	static String describe(Output output)
	{
		return output.id() + " (" + output.subject() + ")";
	}

	/**
	 * Indexes outputs after validating their identifiers and supported subjects.
	 *
	 * @param outputs the manifest outputs
	 * @return outputs by identifier
	 * @throws IOException if an output is invalid or duplicated
	 */
	static Map<String, Output> byId(List<Output> outputs) throws IOException
	{
		Map<String, Output> result = new HashMap<>();
		for (Output output : outputs)
		{
			if (output.id() == null || output.id().isBlank() || output.subject() == null ||
				output.source() == null && !TopicOutputDeclaration.isCommitSubject(output.subject()) ||
				output.source() != null && !isFullHash(output.source()) || result.putIfAbsent(output.id(), output) != null)
			{
				throw new IOException("Every output must have a unique identifier, a subject in the format type: summary, " +
					"and an optional source");
			}
		}
		return result;
	}

	/**
	 * Indicates whether a value is a full Git object hash.
	 *
	 * @param value the candidate hash
	 * @return {@code true} if the value is a lowercase 40-digit hexadecimal hash
	 */
	private static boolean isFullHash(String value)
	{
		return value.matches("[0-9a-f]{40}");
	}
}

package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and fingerprints the immutable inputs declared by one evaluator build plan.
 */
public final class BuildInputs
{
	private static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(10);

	/**
	 * Prevents construction of this static build-plan utility.
	 */
	private BuildInputs()
	{
	}

	/**
	 * Returns the bounded time a declared build may own an evaluator before terminal failure.
	 *
	 * @param plan the selected build plan
	 * @param planPath the plan used for diagnostics
	 * @return the declared duration, or the safe default for older focused fixtures
	 * @throws IOException if the declared duration is not a positive whole number of seconds
	 */
	public static Duration timeout(ObjectNode plan, Path planPath) throws IOException
	{
		JsonNode value = plan.path("timeout_seconds");
		if (value.isMissingNode())
			return DEFAULT_BUILD_TIMEOUT;
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0)
			throw new IOException("Build plan timeout_seconds must be a positive whole number: " + planPath);
		return Duration.ofSeconds(value.asLong());
	}

	/**
	 * Returns the declared inputs whose unchanged contents permit a prior runner assembly to be reused.
	 *
	 * @param plan the selected build plan
	 * @param field the plan field that declares this input set
	 * @param planPath the plan used for diagnostics
	 * @param workingDirectory the base for every declared input
	 * @param runnerArtifact the artifact the build produces
	 * @param fallback the input set to use when non-production test plans omit {@code field}, or {@code null}
	 * @return the normalized declared input paths
	 * @throws IOException if the plan does not declare bounded existing inputs
	 */
	public static List<Path> paths(ObjectNode plan, String field, Path planPath, Path workingDirectory,
		Path runnerArtifact, List<Path> fallback) throws IOException
	{
		JsonNode declaredNode = plan.path(field);
		if (declaredNode.isMissingNode() && fallback != null)
			return fallback;
		ArrayNode declared = PromptEvaluation.array(declaredNode, "build plan " + field, planPath);
		if (declared.isEmpty())
			throw new IOException("Build plan " + field + " must not be empty: " + planPath);
		List<Path> result = new ArrayList<>();
		for (JsonNode node : declared)
		{
			if (!node.isString() || node.asString().isBlank())
				throw new IOException("Build plan " + field + " entry must be a non-blank relative path: " + planPath);
			Path input = resolve(workingDirectory, node.asString(), "Build input");
			if (!input.startsWith(workingDirectory) || (!Files.isRegularFile(input, NOFOLLOW_LINKS) &&
				!Files.isDirectory(input, NOFOLLOW_LINKS)))
			{
				throw new IOException("Build plan " + field + " must name an existing file or directory below its working " +
					"directory: " + input);
			}
			if (runnerArtifact.startsWith(input))
			{
				throw new IOException("Build plan " + field + " contains its runner artifact: " + input +
					". Declare only artifacts the build reads, not the artifact or directory it writes.");
			}
			result.add(input);
		}
		return result.stream().sorted().toList();
	}

	/**
	 * Fingerprints all declared build inputs without guessing which source files a command might read.
	 *
	 * @param workingDirectory the declared input base
	 * @param inputs the validated build inputs
	 * @return the stable declared-input fingerprint
	 * @throws IOException if an input cannot be read
	 */
	public static String fingerprint(Path workingDirectory, List<Path> inputs) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path input : inputs)
			{
				String label = "input/" + workingDirectory.relativize(input);
				if (Files.isRegularFile(input, NOFOLLOW_LINKS))
					PromptEvaluation.updateDigest(digest, label, input);
				else
					PromptEvaluation.updateFixtureDigest(digest, label, input);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Resolves a relative path from its plan-declared base.
	 *
	 * @param base the directory relative paths may use
	 * @param value the declared relative path
	 * @param description the reader-visible value being resolved
	 * @return the normalized declared path
	 * @throws IOException if the declaration is absolute
	 */
	static Path resolve(Path base, String value, String description) throws IOException
	{
		Path relative = Path.of(value);
		if (relative.isAbsolute())
			throw new IOException(description + " must be relative to its declared build-plan base: " + value);
		return base.resolve(relative).normalize();
	}
}

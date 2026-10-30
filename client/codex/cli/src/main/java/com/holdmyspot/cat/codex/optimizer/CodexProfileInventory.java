package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAgentDefinition;
import com.holdmyspot.cat.common.optimizer.adapter.BindingFiles;
import com.holdmyspot.cat.common.optimizer.adapter.RankingResource;
import com.holdmyspot.cat.common.optimizer.cli.HarnessVersion;
import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Codex profile inventory and bindings.
 * <p>
 * The inventory is every model that {@code codex debug models} lists with visibility {@code list} and whose slug is
 * {@code gpt-<version>} or {@code gpt-<version>-<rest>} with a major version of 6 or later, each with its supported
 * reasoning levels except {@code ultra}, or with the effort {@code none} if it lists no level; hidden models, models
 * of an earlier version and models whose slug has another form are not offered. {@code ultra} is {@code max}
 * reasoning plus automatic task delegation, which changes what the agent does rather than how deeply it reasons, so
 * a model's highest effort is the one below it. A binding is the
 * top-level {@code model} and {@code model_reasoning_effort} of a custom agent's {@code .toml} file below an
 * {@code agents} directory. The role profile is the binding of the artifact's {@code optimize-xhigh} agent
 * definition, which must name a model of version 6 or later. A trial's {@code config.toml} sets
 * {@code tool_output_token_limit} only from its profile, so the tool-output limit depends on the profile alone.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CodexProfileInventory implements ProfileInventory
{
	private static final String RANKING = "capability-ranking.json";
	private static final String COMMAND = "`codex debug models`";
	private static final String LISTED = "list";
	private static final String DELEGATING_EFFORT = "ultra";
	private static final Pattern TOP_LEVEL_FIELD = Pattern.compile(
		"^(model|model_reasoning_effort)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')\\s*(?:#.*)?$");
	/**
	 * The token budget of a tool call: Codex keeps at most this many tokens of each tool output, or the profile's
	 * tool-output token limit when it is lower.
	 */
	private static final long TOOL_OUTPUT_TOKENS = 10_000;
	/**
	 * The number of bytes per token of the budget that Codex keeps whole; it removes bytes from the middle of a longer
	 * output.
	 */
	private static final long BYTES_PER_TOKEN = 4;
	private final Path artifact;
	private final String executable;
	private final HarnessVersion.Launcher launcher;

	/**
	 * Creates an inventory.
	 *
	 * @param artifact the complete Codex artifact, which holds CAT's agent definitions
	 * @param executable the Codex executable
	 * @param launcher runs {@code <executable> debug models} and {@code <executable> --version}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code executable} is blank
	 */
	public CodexProfileInventory(Path artifact, String executable, HarnessVersion.Launcher launcher)
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(launcher, "launcher").isNotNull();
		this.artifact = artifact;
		this.executable = executable;
		this.launcher = launcher;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The model list is one JSON document that the command prints whole, so it is parsed whole. Output before the
	 * document's opening brace and after the document, such as a warning, is skipped.
	 *
	 * @throws IOException if the command fails, prints no model list, or lists no model of version 6 or later
	 */
	@Override
	public ProfileCatalog inventory() throws IOException
	{
		String output;
		try
		{
			output = launcher.run(List.of(executable, "debug", "models"));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException(COMMAND + " was interrupted.", e);
		}
		int start = output.indexOf('{');
		if (start < 0)
			throw new IOException(COMMAND + " printed no model list: " + output.strip());
		JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
		JsonNode root;
		try
		{
			root = json.readTree(output.substring(start));
		}
		catch (JacksonException e)
		{
			throw new IOException(COMMAND + " printed a malformed model list.", e);
		}
		JsonNode models = root.path("models");
		if (!models.isArray())
			throw new IOException(COMMAND + " printed no model list: " + output.strip());
		List<TrialProfile> profiles = new ArrayList<>();
		for (JsonNode model : models)
		{
			if (!model.path("visibility").asString("").equals(LISTED))
				continue;
			String slug = model.path("slug").asString("");
			if (slug.isBlank())
				throw new IOException(COMMAND + " listed a model without a slug.");
			if (!CodexModelVersions.isSixOrLater(slug))
				continue;
			JsonNode levels = model.path("supported_reasoning_levels");
			if (levels.isEmpty())
				profiles.add(new TrialProfile(slug, TrialProfile.NO_REASONING));
			for (JsonNode level : levels)
			{
				String effort = level.path("effort").asString("");
				if (!effort.equals(DELEGATING_EFFORT))
					profiles.add(new TrialProfile(slug, effort));
			}
		}
		if (profiles.isEmpty())
		{
			throw new IOException(COMMAND + " listed no model with visibility " + LISTED +
				" and no listed model is of version 6 or later.");
		}
		return new ProfileCatalog(COMMAND + " of " + HarnessVersion.read(executable, launcher), profiles);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The ranking is the resource {@code capability-ranking.json} next to this class.
	 *
	 * @throws IOException if the resource is missing or malformed
	 */
	@Override
	public CapabilityRanking ranking() throws IOException
	{
		try (InputStream in = CodexProfileInventory.class.getResourceAsStream(RANKING))
		{
			return RankingResource.parse(in, RANKING);
		}
	}

	@Override
	public boolean canBind(Path path)
	{
		requireThat(path, "path").isNotNull();
		Path name = path.getFileName();
		return name != null && name.toString().endsWith(".toml") && BindingFiles.hasAncestor(path.getParent(), "agents");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Only top-level fields bind: lines inside a multi-line string or after the first table header are skipped.
	 */
	@Override
	public TrialProfile binding(String content)
	{
		requireThat(content, "content").isNotNull();
		String model = "";
		String effort = "";
		String openString = null;
		for (String line : content.split("\\R"))
		{
			String stripped = line.strip();
			if (openString != null)
			{
				if (stripped.contains(openString))
					openString = null;
				continue;
			}
			if (stripped.startsWith("["))
				break;
			Matcher field = TOP_LEVEL_FIELD.matcher(stripped);
			if (field.matches())
			{
				String value = field.group(2);
				if (value == null)
					value = field.group(3);
				if (field.group(1).equals("model"))
					model = value;
				else
					effort = value;
				continue;
			}
			openString = opensMultilineString(stripped);
		}
		return new TrialProfile(model, effort);
	}

	/**
	 * Returns the delimiter of a multi-line string that a line opens and does not close.
	 *
	 * @param line a stripped line
	 * @return {@code '''} or {@code """}, or null if the line leaves no multi-line string open
	 */
	private static String opensMultilineString(String line)
	{
		for (String delimiter : new String[]{"'''", "\"\"\""})
		{
			int open = line.indexOf(delimiter);
			if (open >= 0 && line.indexOf(delimiter, open + delimiter.length()) < 0)
				return delimiter;
		}
		return null;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Codex supports a model of version 6 or later.
	 */
	@Override
	public boolean supportsModel(String model)
	{
		requireThat(model, "model").isNotNull();
		return CodexModelVersions.isSixOrLater(model);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IOException if the installed agent definition is missing or binds no profile, or binds a model that is
	 *   not of version 6 or later
	 */
	@Override
	public TrialProfile roleProfile() throws IOException
	{
		CodexAgentDefinition definition = CodexAgentDefinition.load(artifact, ROLE_AGENT);
		if (!CodexModelVersions.isSixOrLater(definition.model()))
		{
			throw new IOException("The role profile " + artifact.resolve("agents/codex").resolve(ROLE_AGENT + ".toml") +
				" binds the model " + definition.model() + ", which is not of version 6 or later.");
		}
		return new TrialProfile(definition.model(), definition.effort());
	}

	@Override
	public OptionalLong toolOutputBytes(TrialProfile profile)
	{
		requireThat(profile, "profile").isNotNull();
		long tokens = TOOL_OUTPUT_TOKENS;
		if (profile.toolOutputTokenLimit() > 0)
			tokens = Math.min(tokens, profile.toolOutputTokenLimit());
		return OptionalLong.of(tokens * BYTES_PER_TOKEN);
	}
}

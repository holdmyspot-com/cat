package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexAgentDefinition;
import io.github.cowwoc.cat.common.optimizer.adapter.BindingFiles;
import io.github.cowwoc.cat.common.optimizer.cli.HarnessVersion;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Codex profile inventory and bindings.
 * <p>
 * The inventory is every model that {@code codex debug models} lists with visibility {@code list}, each with its
 * supported reasoning levels; hidden models are not offered. A binding is the top-level {@code model} and
 * {@code model_reasoning_effort} of a custom agent's {@code .toml} file below an {@code agents} directory. The role
 * profile is the binding of the artifact's {@code optimize-xhigh} agent definition.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CodexProfileInventory implements ProfileInventory
{
	private static final String COMMAND = "`codex debug models`";
	private static final String LISTED = "list";
	private static final Pattern TOP_LEVEL_FIELD = Pattern.compile(
		"^(model|model_reasoning_effort)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')\\s*(?:#.*)?$");
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
	 * @throws IOException if the command fails or prints no model list
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
			JsonNode levels = model.path("supported_reasoning_levels");
			if (levels.isEmpty())
				profiles.add(new TrialProfile(slug, ""));
			for (JsonNode level : levels)
				profiles.add(new TrialProfile(slug, level.path("effort").asString("")));
		}
		if (profiles.isEmpty())
			throw new IOException(COMMAND + " listed no model with visibility " + LISTED + ".");
		return new ProfileCatalog(COMMAND + " of " + HarnessVersion.read(executable, launcher), profiles);
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

	@Override
	public TrialProfile roleProfile() throws IOException
	{
		CodexAgentDefinition definition = CodexAgentDefinition.load(artifact, BindingFiles.ROLE_AGENT);
		return new TrialProfile(definition.model(), definition.effort());
	}
}

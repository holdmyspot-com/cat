package com.holdmyspot.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeAgentDefinition;
import com.holdmyspot.cat.common.optimizer.adapter.BindingFiles;
import com.holdmyspot.cat.common.optimizer.adapter.RankingResource;
import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.claude.secrets.ClaudeProfileInventoryAccess;
import com.holdmyspot.cat.claude.secrets.ClaudeProfileInventorySecrets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Claude Code profile inventory and bindings.
 * <p>
 * The Claude CLI offers no command that lists its models, so the inventory is the resource
 * {@code claude-profiles.json} next to this class: the documented model aliases, the efforts each one accepts and the
 * documentation each entry came from. An alias whose effort support is undocumented has no effort
 * dimension.
 * <p>
 * A binding is the {@code model} and {@code effort} frontmatter of a skill's {@code SKILL.md} or of a subagent's
 * {@code .md} file below an {@code agents} directory; {@code inherit} binds nothing. The role profile is the binding of
 * the artifact's {@code optimize-xhigh} agent definition. CAT does not know how many bytes of a tool output Claude Code
 * keeps whole, so the inventory states no tool-output limit.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ClaudeProfileInventory implements ProfileInventory
{
	static
	{
		ClaudeProfileInventorySecrets.setAccess(new ClaudeProfileInventoryAccess()
		{
			@Override
			public ProfileCatalog parse(InputStream in) throws IOException
			{
				return ClaudeProfileInventory.parse(in);
			}
		});
	}

	private static final String RANKING = "capability-ranking.json";
	private static final String RESOURCE = "claude-profiles.json";
	private static final int FORMAT_VERSION = 1;
	private static final String INHERIT = "inherit";
	private static final Pattern FRONTMATTER = Pattern.compile("(?s)\\A---\\R(.*?)\\R---");
	private static final Pattern MODEL = field("model");
	private static final Pattern EFFORT = field("effort");
	private final Path artifact;

	/**
	 * Creates an inventory.
	 *
	 * @param artifact the complete Claude artifact, which holds CAT's agent definitions
	 * @throws NullPointerException if {@code artifact} is null
	 */
	public ClaudeProfileInventory(Path artifact)
	{
		requireThat(artifact, "artifact").isNotNull();
		this.artifact = artifact;
	}

	/**
	 * Creates the pattern of a quoted or unquoted YAML scalar field.
	 *
	 * @param name the field's name
	 * @return the pattern, whose first group is the value
	 */
	private static Pattern field(String name)
	{
		return Pattern.compile("(?m)^" + name + ":[ \\t]*[\"']?([^\\s\"']*)[\"']?[ \\t]*$");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The resource is small and fixed, so it is parsed whole.
	 *
	 * @throws IOException if the resource is missing or malformed
	 */
	@Override
	public ProfileCatalog inventory() throws IOException
	{
		try (InputStream in = ClaudeProfileInventory.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
				throw new IOException("The Claude profile inventory resource " + RESOURCE + " is missing.");
			return parse(in);
		}
	}

	/**
	 * Parses the content of the inventory resource.
	 *
	 * @param in the content
	 * @return the inventory
	 * @throws NullPointerException if {@code in} is null
	 * @throws IOException if the content cannot be read or departs from the resource format
	 */
	static ProfileCatalog parse(InputStream in) throws IOException
	{
		requireThat(in, "in").isNotNull();
		JsonNode root;
		try
		{
			root = JsonMapper.builder().build().readTree(in);
		}
		catch (JacksonException e)
		{
			throw new IOException("The Claude profile inventory resource " + RESOURCE + " is not JSON.", e);
		}
		requireFields(root, Set.of("format_version", "source", "models"));
		if (root.path("format_version").asInt(-1) != FORMAT_VERSION)
		{
			throw new IOException("The Claude profile inventory resource " + RESOURCE + " has format_version " +
				root.path("format_version") + "; expected " + FORMAT_VERSION + ".");
		}
		List<TrialProfile> profiles = new ArrayList<>();
		StringJoiner documentation = new StringJoiner("; ", " (", ")");
		for (JsonNode entry : root.path("models"))
		{
			requireFields(entry, Set.of("model", "efforts", "documentation"));
			String model = text(entry, "model");
			documentation.add(model + ": " + text(entry, "documentation"));
			JsonNode efforts = entry.path("efforts");
			if (!efforts.isArray())
				throw new IOException("The efforts of " + model + " in " + RESOURCE + " are not an array.");
			if (efforts.isEmpty())
				profiles.add(new TrialProfile(model, ""));
			for (JsonNode effort : efforts)
				profiles.add(new TrialProfile(model, effort.asString()));
		}
		try
		{
			return new ProfileCatalog(text(root, "source") + documentation, profiles);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("The Claude profile inventory resource " + RESOURCE + " is invalid.", e);
		}
	}

	/**
	 * Rejects an object with missing or unknown fields.
	 *
	 * @param node the object
	 * @param fields its fields
	 * @throws IOException if {@code node} is not an object or its field names differ from {@code fields}
	 */
	private static void requireFields(JsonNode node, Set<String> fields) throws IOException
	{
		if (!node.isObject() || !new HashSet<>(node.propertyNames()).equals(fields))
			throw new IOException("An object in " + RESOURCE + " does not have exactly the fields " + fields + ".");
	}

	/**
	 * Returns a non-blank text field.
	 *
	 * @param node the object
	 * @param name the field's name
	 * @return the value
	 * @throws IOException if the field is not non-blank text
	 */
	private static String text(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("The field " + name + " in " + RESOURCE + " is not non-blank text.");
		return value.asString();
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
		try (InputStream in = ClaudeProfileInventory.class.getResourceAsStream(RANKING))
		{
			return RankingResource.parse(in, RANKING);
		}
	}

	@Override
	public boolean canBind(Path path)
	{
		requireThat(path, "path").isNotNull();
		Path name = path.getFileName();
		if (name == null)
			return false;
		if (name.toString().equals("SKILL.md"))
			return BindingFiles.hasAncestor(path.getParent(), "skills");
		return name.toString().endsWith(".md") && BindingFiles.hasAncestor(path.getParent(), "agents");
	}

	@Override
	public TrialProfile binding(String content)
	{
		requireThat(content, "content").isNotNull();
		Matcher frontmatter = FRONTMATTER.matcher(content);
		if (!frontmatter.find())
			return TrialProfile.HARNESS_DEFAULT;
		String metadata = frontmatter.group(1);
		return new TrialProfile(value(MODEL, metadata), value(EFFORT, metadata));
	}

	/**
	 * Returns the value of a frontmatter field.
	 *
	 * @param pattern the field's pattern
	 * @param metadata the frontmatter
	 * @return the value, or empty if the field is absent or {@code inherit}
	 */
	private static String value(Pattern pattern, String metadata)
	{
		Matcher matcher = pattern.matcher(metadata);
		if (!matcher.find() || matcher.group(1).equals(INHERIT))
			return "";
		return matcher.group(1);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Claude accepts every model name.
	 */
	@Override
	public boolean supportsModel(String model)
	{
		requireThat(model, "model").isNotNull();
		return true;
	}

	@Override
	public TrialProfile roleProfile() throws IOException
	{
		ClaudeAgentDefinition definition = ClaudeAgentDefinition.load(artifact, ROLE_AGENT);
		return new TrialProfile(definition.model(), definition.effort());
	}

	@Override
	public OptionalLong toolOutputBytes(TrialProfile profile)
	{
		requireThat(profile, "profile").isNotNull();
		return OptionalLong.empty();
	}
}

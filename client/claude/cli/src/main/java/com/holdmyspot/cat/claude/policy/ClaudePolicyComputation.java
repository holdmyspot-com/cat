package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.claude.policy.ClaudePaths.Syntax;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

import static com.holdmyspot.cat.claude.policy.SettingsScope.FLAG;
import static com.holdmyspot.cat.claude.policy.SettingsScope.MANAGED;
import static com.holdmyspot.cat.claude.policy.SettingsScope.PROJECT;
import static com.holdmyspot.cat.claude.policy.SettingsScope.USER;

/**
 * Computes the sandbox policy that a Claude Code session's settings and command line give its commands, as the
 * release merges and applies them. Where the release's behavior is not certain, the computed policy is narrower than
 * the session's, never wider.
 */
final class ClaudePolicyComputation
{
	/**
	 * A permission rule: a tool name, followed by its specifier in parentheses.
	 */
	private static final Pattern RULE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)(?:\\((.*)\\))?");
	/**
	 * A host pattern that CAT can compare: a host name, optionally preceded by {@code *.}.
	 */
	private static final Pattern HOST_PATTERN = Pattern.compile("(\\*\\.)?[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*");
	/**
	 * The fix of a refusal that a setting causes.
	 */
	private static final String SETTING_FIX = "Correct or remove that setting, then start a new session";
	/**
	 * The settings, from the highest precedence to the lowest.
	 */
	private final List<SettingsLayer> layers;
	/**
	 * The command line.
	 */
	private final ClaudeCommandLine commandLine;
	/**
	 * The session.
	 */
	private final ClaudeSession session;
	/**
	 * The path resolver of the session.
	 */
	private final ClaudePaths paths;

	/**
	 * Creates a computation.
	 *
	 * @param layers      the settings, from the highest precedence to the lowest
	 * @param commandLine the command line
	 * @param session     the session
	 */
	private ClaudePolicyComputation(List<SettingsLayer> layers, ClaudeCommandLine commandLine, ClaudeSession session)
	{
		this.layers = layers;
		this.commandLine = commandLine;
		this.session = session;
		this.paths = new ClaudePaths(session.projectDirectory(), session.userHome());
	}

	/**
	 * Computes the policy of a session.
	 *
	 * @param layers      the settings, from the highest precedence to the lowest
	 * @param commandLine the command line
	 * @param session     the session
	 * @return the policy
	 * @throws IOException   if a folder cannot be listed while matching a denied pattern
	 * @throws PolicyRefusal if a setting has a value that CAT cannot read safely
	 */
	static ParentPolicy compute(List<SettingsLayer> layers, ClaudeCommandLine commandLine, ClaudeSession session)
		throws IOException, PolicyRefusal
	{
		return new ClaudePolicyComputation(List.copyOf(layers), commandLine, session).compute();
	}

	/**
	 * Computes the policy.
	 *
	 * @return the policy
	 * @throws IOException   if a folder cannot be listed while matching a denied pattern
	 * @throws PolicyRefusal if a setting has a value that CAT cannot read safely
	 */
	private ParentPolicy compute() throws IOException, PolicyRefusal
	{
		for (SettingsLayer layer : layers)
		{
			if (bool(layer, ClaudeRelease.WSL_INHERITS_WINDOWS_SETTINGS).orElse(false))
			{
				throw new PolicyRefusal(layer.source() + " sets " + ClaudeRelease.WSL_INHERITS_WINDOWS_SETTINGS +
					", which applies Windows registry policies that CAT cannot read", SETTING_FIX);
			}
			for (String port : List.of("httpProxyPort", "socksProxyPort"))
			{
				if (!at(layer, "sandbox", "network", port).isMissingNode())
				{
					throw new PolicyRefusal(layer.source() + " sets sandbox.network." + port + ", which sends the " +
						"session's network traffic through a proxy whose rules CAT cannot read", SETTING_FIX);
				}
			}
		}
		boolean managedDomainsOnly = anyTrue(Set.of(MANAGED), "sandbox", "network", "allowManagedDomainsOnly");
		boolean managedRulesOnly = anyTrue(Set.of(MANAGED), "allowManagedPermissionRulesOnly");
		// The release ignores some project settings when a more trusted scope forbids unsandboxed commands
		boolean projectRestricted = managedDomainsOnly ||
			anyFalse(Set.of(MANAGED, FLAG), "sandbox", "allowUnsandboxedCommands");
		if (!sandboxEnabled(projectRestricted))
			return ParentPolicy.UNSANDBOXED;

		List<Path> writeRoots = new ArrayList<>();
		List<Path> writeDenials = new ArrayList<>();
		List<Path> readDenials = new ArrayList<>();
		List<Path> readReallowances = new ArrayList<>();
		List<Path> protectedSources = new ArrayList<>();
		if (filesystemDisabled())
		{
			// Commands may read and write every file, and nothing says that the settings files stay protected
			writeRoots.add(Path.of("/"));
			return new ParentPolicy(true, writeRoots, writeDenials, readDenials, readReallowances,
				hosts(projectRestricted, managedDomainsOnly, managedRulesOnly), allowLocalBinding(projectRestricted),
				protectedSources);
		}
		addWrites(writeRoots, writeDenials, projectRestricted, managedRulesOnly || managedDomainsOnly);
		addReads(readDenials, readReallowances);
		Path projectSettings = session.projectDirectory().resolve(".claude");
		protectedSources.add(projectSettings.resolve("settings.json"));
		protectedSources.add(projectSettings.resolve("settings.local.json"));
		protectedSources.add(session.configurationDirectory().resolve("settings.json"));
		for (SettingsLayer layer : layers)
		{
			if (layer.scope() == FLAG && layer.file().isPresent())
				protectedSources.add(layer.file().get());
		}
		return new ParentPolicy(true, distinct(writeRoots), distinct(writeDenials), distinct(readDenials),
			distinct(readReallowances), hosts(projectRestricted, managedDomainsOnly, managedRulesOnly),
			allowLocalBinding(projectRestricted), distinct(protectedSources));
	}

	/**
	 * Indicates whether the session's sandbox is enabled.
	 *
	 * @param projectRestricted {@code true} if the release ignores some project settings
	 * @return {@code true} if the sandbox is enabled
	 * @throws PolicyRefusal if a setting has the wrong type
	 */
	private boolean sandboxEnabled(boolean projectRestricted) throws PolicyRefusal
	{
		boolean trustedEnable = anyTrue(Set.of(MANAGED, FLAG, USER), "sandbox", "enabled");
		for (SettingsLayer layer : layers)
		{
			Optional<Boolean> enabled = bool(layer, "sandbox", "enabled");
			if (enabled.isEmpty())
				continue;
			if (layer.scope() == PROJECT && !enabled.get() && projectRestricted && trustedEnable)
				continue;
			return enabled.get();
		}
		return false;
	}

	/**
	 * Indicates whether the settings lift the sandbox's file restrictions.
	 *
	 * @return {@code true} if commands may read and write every file
	 * @throws PolicyRefusal if a setting has the wrong type
	 */
	private boolean filesystemDisabled() throws PolicyRefusal
	{
		boolean managedFilesystem = false;
		for (SettingsLayer layer : layers)
		{
			if (layer.scope() == MANAGED && !at(layer, "sandbox", "filesystem").isMissingNode())
				managedFilesystem = true;
		}
		Set<SettingsScope> scopes;
		if (managedFilesystem)
			scopes = Set.of(MANAGED);
		else
			scopes = Set.of(MANAGED, FLAG, USER);
		for (SettingsLayer layer : layers)
		{
			if (!scopes.contains(layer.scope()))
				continue;
			Optional<Boolean> disabled = bool(layer, "sandbox", "filesystem", "disabled");
			if (disabled.isPresent())
				return disabled.get();
		}
		return false;
	}

	/**
	 * Adds the folders that commands may write, and the paths inside them that they may not.
	 *
	 * @param writeRoots        the folders
	 * @param writeDenials      the denied paths
	 * @param projectRestricted {@code true} if the release ignores some project settings
	 * @param managedRulesOnly  {@code true} if only managed permission rules grant access
	 * @throws IOException   if a folder cannot be listed while matching a denied pattern
	 * @throws PolicyRefusal if a setting has a value that CAT cannot read safely
	 */
	private void addWrites(List<Path> writeRoots, List<Path> writeDenials, boolean projectRestricted,
		boolean managedRulesOnly) throws IOException, PolicyRefusal
	{
		for (SettingsLayer layer : layers)
		{
			for (String entry : strings(layer, "sandbox", "filesystem", "denyWrite"))
				writeDenials.addAll(paths.denied(entry, Syntax.SANDBOX, layer.root(), layer.source()));
			for (String rule : strings(layer, "permissions", "deny"))
				writeDenials.addAll(ruleDenials(rule, "Edit", layer.root(), layer.source()));
		}
		for (String rule : commandLine.deniedTools())
		{
			writeDenials.addAll(ruleDenials(rule, "Edit", session.projectDirectory(),
				"Claude Code's option --disallowedTools"));
		}

		writeRoots.add(session.projectDirectory());
		for (String directory : commandLine.addDirectories())
			writeRoots.add(session.projectDirectory().resolve(directory).normalize());
		for (SettingsLayer layer : layers)
		{
			boolean grants = !managedRulesOnly || layer.scope() == MANAGED;
			if (grants)
			{
				for (String entry : strings(layer, "permissions", "additionalDirectories"))
					paths.granted(entry, Syntax.SANDBOX, layer.root()).ifPresent(writeRoots::add);
			}
			if (layer.scope() == PROJECT && projectRestricted)
				continue;
			for (String entry : strings(layer, "sandbox", "filesystem", "allowWrite"))
			{
				Optional<Path> path = paths.granted(entry, Syntax.SANDBOX, layer.root());
				// The release ignores a project entry that would reopen a denied path
				if (path.isPresent() && (layer.scope() != PROJECT || !isInsideAny(path.get(), writeDenials)))
					writeRoots.add(path.get());
			}
			if (!grants)
				continue;
			for (String rule : strings(layer, "permissions", "allow"))
			{
				Optional<String> specifier = specifier(rule, "Edit");
				if (specifier.isPresent())
					paths.granted(specifier.get(), Syntax.RULE, layer.root()).ifPresent(writeRoots::add);
			}
		}
		writeRoots.addAll(ClaudeRelease.BUILT_IN_WRITES);
		session.temporaryDirectory().ifPresent(writeRoots::add);

		for (Path root : List.copyOf(writeRoots))
		{
			for (String inside : ClaudeRelease.BUILT_IN_WRITE_DENIALS)
				writeDenials.add(root.resolve(inside));
		}
		writeDenials.add(session.configurationDirectory());
		writeDenials.add(session.managedDirectory());
		writeDenials.addAll(session.otherManagedDirectories());
		for (SettingsLayer layer : layers)
		{
			if (layer.scope() == FLAG && layer.file().isPresent())
				writeDenials.add(layer.file().get());
		}
	}

	/**
	 * Adds the paths that commands may not read, and the paths inside them that they may read nonetheless.
	 *
	 * @param readDenials      the denied paths
	 * @param readReallowances the re-allowed paths
	 * @throws IOException   if a folder cannot be listed while matching a denied pattern
	 * @throws PolicyRefusal if a setting has a value that CAT cannot read safely
	 */
	private void addReads(List<Path> readDenials, List<Path> readReallowances) throws IOException, PolicyRefusal
	{
		List<Path> trustedDenials = new ArrayList<>();
		for (SettingsLayer layer : layers)
		{
			List<Path> denials = new ArrayList<>();
			for (String entry : strings(layer, "sandbox", "filesystem", "denyRead"))
				denials.addAll(paths.denied(entry, Syntax.SANDBOX, layer.root(), layer.source()));
			for (String rule : strings(layer, "permissions", "deny"))
				denials.addAll(ruleDenials(rule, "Read", layer.root(), layer.source()));
			for (String entry : credentialFiles(layer))
				denials.addAll(paths.denied(entry, Syntax.SANDBOX, layer.root(), layer.source()));
			readDenials.addAll(denials);
			if (layer.scope() != PROJECT)
				trustedDenials.addAll(denials);
		}
		for (String rule : commandLine.deniedTools())
		{
			List<Path> denials = ruleDenials(rule, "Read", session.projectDirectory(),
				"Claude Code's option --disallowedTools");
			readDenials.addAll(denials);
			trustedDenials.addAll(denials);
		}

		boolean managedReadsOnly = anyTrue(Set.of(MANAGED), "sandbox", "filesystem", "allowManagedReadPathsOnly");
		for (SettingsLayer layer : layers)
		{
			if (managedReadsOnly && layer.scope() != MANAGED)
				continue;
			for (String entry : strings(layer, "sandbox", "filesystem", "allowRead"))
			{
				Optional<Path> path = paths.granted(entry, Syntax.SANDBOX, layer.root());
				// The release ignores a project entry that would reopen a more trusted scope's denial
				if (path.isPresent() && (layer.scope() != PROJECT || !isInsideAny(path.get(), trustedDenials)))
					readReallowances.add(path.get());
			}
		}
	}

	/**
	 * Returns the hosts that commands may reach.
	 *
	 * @param projectRestricted  {@code true} if the release ignores some project settings
	 * @param managedDomainsOnly {@code true} if only managed settings allow hosts
	 * @param managedRulesOnly   {@code true} if only managed permission rules grant access
	 * @return the hosts
	 * @throws PolicyRefusal if a setting has a value that CAT cannot read safely
	 */
	private HostAccess hosts(boolean projectRestricted, boolean managedDomainsOnly, boolean managedRulesOnly)
		throws PolicyRefusal
	{
		boolean strict = anyTrue(Set.of(MANAGED, FLAG, USER), "sandbox", "network", "strictAllowlist");
		Set<String> denied = new LinkedHashSet<>();
		boolean deniesAll = false;
		List<String> deniedRules = new ArrayList<>(commandLine.deniedTools());
		for (SettingsLayer layer : layers)
		{
			denied.addAll(strings(layer, "sandbox", "network", "deniedDomains"));
			deniedRules.addAll(strings(layer, "permissions", "deny"));
		}
		for (String rule : deniedRules)
		{
			Matcher matcher = RULE.matcher(rule.strip());
			if (!matcher.matches() || !matcher.group(1).equals("WebFetch"))
				continue;
			String specifier = matcher.group(2);
			if (specifier == null || specifier.isBlank())
				deniesAll = true;
			else if (specifier.startsWith("domain:"))
				denied.add(specifier.substring("domain:".length()));
			else
				deniesAll = true;
		}
		if (deniesAll)
			return HostAccess.NONE;

		Set<String> allowed = new LinkedHashSet<>();
		for (SettingsLayer layer : layers)
		{
			if (managedDomainsOnly && layer.scope() != MANAGED)
				continue;
			if (layer.scope() == PROJECT && (projectRestricted || strict))
				continue;
			allowed.addAll(strings(layer, "sandbox", "network", "allowedDomains"));
			if (managedRulesOnly && layer.scope() != MANAGED)
				continue;
			for (String rule : strings(layer, "permissions", "allow"))
			{
				Optional<String> specifier = specifier(rule, "WebFetch");
				if (specifier.isPresent() && specifier.get().startsWith("domain:"))
					allowed.add(specifier.get().substring("domain:".length()));
			}
		}
		Set<String> patterns = new LinkedHashSet<>();
		for (String pattern : allowed)
		{
			String host = pattern.strip().toLowerCase(Locale.ROOT);
			if (!HOST_PATTERN.matcher(host).matches())
				continue;
			boolean overlaps = false;
			for (String denial : denied)
			{
				if (overlap(host, denial.strip().toLowerCase(Locale.ROOT)))
				{
					overlaps = true;
					break;
				}
			}
			if (!overlaps)
				patterns.add(host);
		}
		if (patterns.isEmpty())
			return HostAccess.NONE;
		return HostAccess.listed(patterns);
	}

	/**
	 * Indicates whether two host patterns match a common host.
	 *
	 * @param first  a host pattern
	 * @param second another host pattern, in any form
	 * @return {@code true} if a host may match both
	 */
	private static boolean overlap(String first, String second)
	{
		if (second.equals("*"))
			return true;
		boolean firstWildcard = first.startsWith("*.");
		boolean secondWildcard = second.startsWith("*.");
		String firstName = first;
		if (firstWildcard)
			firstName = first.substring(2);
		String secondName = second;
		if (secondWildcard)
			secondName = second.substring(2);
		int port = secondName.indexOf(':');
		if (port >= 0)
			secondName = secondName.substring(0, port);
		if (firstName.equals(secondName))
			return true;
		return firstWildcard && secondName.endsWith("." + firstName) ||
			secondWildcard && firstName.endsWith("." + secondName);
	}

	/**
	 * Indicates whether commands may listen on local network addresses.
	 *
	 * @param projectRestricted {@code true} if the release ignores some project settings
	 * @return {@code true} if they may
	 * @throws PolicyRefusal if a setting has the wrong type
	 */
	private boolean allowLocalBinding(boolean projectRestricted) throws PolicyRefusal
	{
		for (SettingsLayer layer : layers)
		{
			Optional<Boolean> allow = bool(layer, "sandbox", "network", "allowLocalBinding");
			if (allow.isEmpty())
				continue;
			if (layer.scope() == PROJECT && allow.get() && projectRestricted)
				continue;
			return allow.get();
		}
		return false;
	}

	/**
	 * Returns the paths that a deny rule denies to a tool.
	 *
	 * @param rule         the rule
	 * @param tool         the tool, {@code Read} or {@code Edit}
	 * @param settingsRoot the root of the settings that hold the rule
	 * @param source       the source of the rule, as the user knows it
	 * @return the paths, {@code /} if the rule denies the tool everywhere
	 * @throws IOException   if a folder cannot be listed while matching a denied pattern
	 * @throws PolicyRefusal if the rule names a path that CAT cannot resolve
	 */
	private List<Path> ruleDenials(String rule, String tool, Path settingsRoot, String source)
		throws IOException, PolicyRefusal
	{
		Matcher matcher = RULE.matcher(rule.strip());
		if (!matcher.matches() || !matcher.group(1).equals(tool))
			return List.of();
		String specifier = matcher.group(2);
		if (specifier == null || specifier.isBlank() || specifier.equals("**") || specifier.equals("//**"))
			return List.of(Path.of("/"));
		return paths.denied(specifier, Syntax.RULE, settingsRoot, source);
	}

	/**
	 * Returns the specifier of a rule for a tool.
	 *
	 * @param rule the rule
	 * @param tool the tool
	 * @return the specifier, or empty if the rule is for another tool or has no specifier
	 */
	private static Optional<String> specifier(String rule, String tool)
	{
		Matcher matcher = RULE.matcher(rule.strip());
		if (!matcher.matches() || !matcher.group(1).equals(tool) || matcher.group(2) == null)
			return Optional.empty();
		return Optional.of(matcher.group(2));
	}

	/**
	 * Returns the credential files that the settings keep from commands.
	 *
	 * @param layer the settings
	 * @return the paths of the files
	 * @throws PolicyRefusal if the setting has the wrong type
	 */
	private static List<String> credentialFiles(SettingsLayer layer) throws PolicyRefusal
	{
		JsonNode files = at(layer, "sandbox", "credentials", "files");
		if (files.isMissingNode())
			return List.of();
		if (!files.isArray())
			throw wrongType(layer, "sandbox.credentials.files", "a list");
		List<String> paths = new ArrayList<>();
		for (JsonNode file : files.values())
		{
			JsonNode path = file;
			if (file.isObject())
				path = file.path("path");
			if (!path.isString())
				throw wrongType(layer, "sandbox.credentials.files", "a list of paths");
			paths.add(path.stringValue());
		}
		return paths;
	}

	/**
	 * Indicates whether a layer of some scopes sets a setting to {@code true}.
	 *
	 * @param scopes the scopes
	 * @param keys   the keys of the setting
	 * @return {@code true} if one does
	 * @throws PolicyRefusal if the setting has the wrong type
	 */
	private boolean anyTrue(Set<SettingsScope> scopes, String... keys) throws PolicyRefusal
	{
		for (SettingsLayer layer : layers)
		{
			if (scopes.contains(layer.scope()) && bool(layer, keys).orElse(false))
				return true;
		}
		return false;
	}

	/**
	 * Indicates whether a layer of some scopes sets a setting to {@code false}.
	 *
	 * @param scopes the scopes
	 * @param keys   the keys of the setting
	 * @return {@code true} if one does
	 * @throws PolicyRefusal if the setting has the wrong type
	 */
	private boolean anyFalse(Set<SettingsScope> scopes, String... keys) throws PolicyRefusal
	{
		for (SettingsLayer layer : layers)
		{
			if (scopes.contains(layer.scope()) && !bool(layer, keys).orElse(true))
				return true;
		}
		return false;
	}

	/**
	 * Returns a setting.
	 *
	 * @param layer the settings
	 * @param keys  the keys of the setting
	 * @return the value, or a missing node if a key is absent or its value is null
	 * @throws PolicyRefusal if a key's parent is not an object
	 */
	private static JsonNode at(SettingsLayer layer, String... keys) throws PolicyRefusal
	{
		JsonNode node = layer.settings();
		StringBuilder name = new StringBuilder();
		for (String key : keys)
		{
			if (!node.isObject())
				throw wrongType(layer, name.toString(), "an object");
			if (!name.isEmpty())
				name.append('.');
			name.append(key);
			node = node.path(key);
			if (node.isNull())
				return node.path(key);
			if (node.isMissingNode())
				return node;
		}
		return node;
	}

	/**
	 * Returns a boolean setting.
	 *
	 * @param layer the settings
	 * @param keys  the keys of the setting
	 * @return the value, or empty if it is absent
	 * @throws PolicyRefusal if the setting has the wrong type
	 */
	private static Optional<Boolean> bool(SettingsLayer layer, String... keys) throws PolicyRefusal
	{
		JsonNode node = at(layer, keys);
		if (node.isMissingNode())
			return Optional.empty();
		if (!node.isBoolean())
			throw wrongType(layer, String.join(".", keys), "true or false");
		return Optional.of(node.booleanValue());
	}

	/**
	 * Returns a setting that lists strings.
	 *
	 * @param layer the settings
	 * @param keys  the keys of the setting
	 * @return the strings, empty if the setting is absent
	 * @throws PolicyRefusal if the setting has the wrong type
	 */
	private static List<String> strings(SettingsLayer layer, String... keys) throws PolicyRefusal
	{
		JsonNode node = at(layer, keys);
		if (node.isMissingNode())
			return List.of();
		if (!node.isArray())
			throw wrongType(layer, String.join(".", keys), "a list of strings");
		List<String> strings = new ArrayList<>();
		for (JsonNode element : node.values())
		{
			if (!element.isString())
				throw wrongType(layer, String.join(".", keys), "a list of strings");
			strings.add(element.stringValue());
		}
		return strings;
	}

	/**
	 * Returns the refusal of a setting whose value has the wrong type.
	 *
	 * @param layer    the settings
	 * @param name     the name of the setting
	 * @param expected the type that the release expects
	 * @return the refusal
	 */
	private static PolicyRefusal wrongType(SettingsLayer layer, String name, String expected)
	{
		return new PolicyRefusal("the setting " + name + " in " + layer.source() + " is not " + expected,
			SETTING_FIX);
	}

	/**
	 * Indicates whether a path lies inside, or is, one of some paths.
	 *
	 * @param path    a path
	 * @param folders the paths
	 * @return {@code true} if it does
	 */
	private static boolean isInsideAny(Path path, List<Path> folders)
	{
		for (Path folder : folders)
		{
			if (path.startsWith(folder))
				return true;
		}
		return false;
	}

	/**
	 * Returns each path of a list once.
	 *
	 * @param paths the paths
	 * @return the distinct paths, in their first order
	 */
	private static List<Path> distinct(List<Path> paths)
	{
		return List.copyOf(new LinkedHashSet<>(paths));
	}
}

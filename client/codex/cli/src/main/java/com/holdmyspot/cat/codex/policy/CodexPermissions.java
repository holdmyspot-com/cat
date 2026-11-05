package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.codex.policy.CodexConfiguration.Layer;
import com.holdmyspot.cat.codex.policy.PermissionEntry.Access;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolves the sandbox of a Codex 0.160.0 session from its configuration and command line.
 * <p>
 * Codex reads one of two syntaxes. The legacy one is {@code sandbox_mode} with {@code [sandbox_workspace_write]}. The
 * profile one is {@code default_permissions}, which names a built-in profile ({@code :read-only},
 * {@code :workspace} or {@code :danger-full-access}) or a {@code [permissions.<name>]} profile that may extend
 * another. {@code -s} selects the legacy syntax. Otherwise a {@code default_permissions} override selects the profile
 * syntax, and else the highest layer that sets either key decides. Without either, the session gets {@code :workspace}
 * with the legacy settings if the user's configuration decides the working directory's trust, and
 * {@code :read-only} otherwise.
 */
public final class CodexPermissions
{
	private static final String READ_ONLY = ":read-only";
	private static final String WORKSPACE = ":workspace";
	private static final String DANGER_FULL_ACCESS = ":danger-full-access";
	/**
	 * The {@code glob_scan_max_depth} that applies when a profile sets none.
	 */
	private static final int DEFAULT_GLOB_DEPTH = 64;
	private final CodexConfiguration configuration;
	private final CodexLaunch launch;
	private final Path cwd;
	private final Map<String, String> environment;
	private final List<Path> workspaceRoots = new ArrayList<>();

	/**
	 * Creates a resolver.
	 *
	 * @param configuration the session's configuration
	 * @param launch        the session's command line
	 * @param cwd           the session's working directory
	 * @param environment   the environment of the session's Codex process
	 */
	private CodexPermissions(CodexConfiguration configuration, CodexLaunch launch, Path cwd,
		Map<String, String> environment)
	{
		this.configuration = configuration;
		this.launch = launch;
		this.cwd = cwd;
		this.environment = environment;
	}

	/**
	 * Resolves a session's sandbox.
	 *
	 * @param configuration the session's configuration
	 * @param launch        the session's command line
	 * @param cwd           the session's working directory
	 * @param environment   the environment of the session's Codex process
	 * @return the sandbox, and the name of the permissions profile that it comes from, or empty for the legacy syntax
	 * @throws IOException   if a folder cannot be read
	 * @throws PolicyRefusal if the configuration names a sandbox mode or profile that the reader does not know, or a
	 *                       path that it cannot resolve
	 */
	public static Resolved resolve(CodexConfiguration configuration, CodexLaunch launch, Path cwd,
		Map<String, String> environment) throws IOException, PolicyRefusal
	{
		return new CodexPermissions(configuration, launch, cwd, environment).resolve();
	}

	/**
	 * A resolved sandbox.
	 *
	 * @param sandbox the sandbox
	 * @param profile the name of the user permissions profile that the sandbox comes from, or empty if none
	 */
	public record Resolved(CodexSandbox sandbox, Optional<String> profile)
	{
	}

	/**
	 * Resolves the sandbox.
	 *
	 * @return the sandbox
	 * @throws IOException   if a folder cannot be read
	 * @throws PolicyRefusal if the configuration names a sandbox mode or profile that the reader does not know, or a
	 *                       path that it cannot resolve
	 */
	private Resolved resolve() throws IOException, PolicyRefusal
	{
		if (launch.bypassSandbox())
			return new Resolved(CodexSandbox.UNSANDBOXED, Optional.empty());
		ObjectNode merged = configuration.merged();
		workspaceRoots.add(cwd);
		for (String added : launch.addDirs())
			workspaceRoots.add(cwd.resolve(path(added, "--add-dir")).normalize());

		if (usesLegacySyntax())
		{
			String mode = launch.sandboxMode().orElse(merged.path("sandbox_mode").asString(""));
			if (mode.isEmpty())
			{
				if (configuration.trustDecision())
					mode = "workspace-write";
				else
					mode = "read-only";
			}
			return switch (mode)
			{
				case "danger-full-access" -> new Resolved(CodexSandbox.UNSANDBOXED, Optional.empty());
				case "read-only" -> new Resolved(readOnly(), Optional.empty());
				case "workspace-write" -> new Resolved(workspace(true), Optional.empty());
				default -> throw new PolicyRefusal("the Codex configuration sets sandbox_mode = \"" + mode + "\", " +
					"which CAT does not know", "Set sandbox_mode to read-only, workspace-write or " +
					"danger-full-access, then start a new Codex session");
			};
		}
		JsonNode selected = merged.path("default_permissions");
		if (!selected.isString())
		{
			if (configuration.trustDecision())
				return new Resolved(workspace(true), Optional.empty());
			return new Resolved(readOnly(), Optional.empty());
		}
		String name = selected.stringValue();
		return switch (name)
		{
			case DANGER_FULL_ACCESS -> new Resolved(CodexSandbox.UNSANDBOXED, Optional.empty());
			case READ_ONLY -> new Resolved(readOnly(), Optional.empty());
			case WORKSPACE -> new Resolved(workspace(false), Optional.empty());
			default -> new Resolved(userProfile(name), Optional.of(name));
		};
	}

	/**
	 * Indicates whether the session uses the legacy syntax.
	 *
	 * @return {@code true} for the legacy syntax, {@code false} for the profile syntax or neither
	 */
	private boolean usesLegacySyntax()
	{
		if (launch.sandboxMode().isPresent())
			return true;
		for (Layer layer : configuration.layers())
		{
			if (layer.flags() && layer.values().has("default_permissions"))
				return false;
		}
		boolean legacy = false;
		for (Layer layer : configuration.layers())
		{
			if (layer.values().has("default_permissions"))
				legacy = false;
			else if (layer.values().has("sandbox_mode"))
				legacy = true;
		}
		return legacy;
	}

	/**
	 * Returns the built-in {@code :read-only} sandbox.
	 *
	 * @return the sandbox
	 */
	private CodexSandbox readOnly()
	{
		List<PermissionEntry> entries = new ArrayList<>();
		for (Path root : FileSystems.getDefault().getRootDirectories())
			entries.add(new PermissionEntry(root, Access.READ));
		return new CodexSandbox(true, entries, HostAccess.NONE, false);
	}

	/**
	 * Returns the built-in {@code :workspace} sandbox.
	 *
	 * @param legacySettings {@code true} if the {@code [sandbox_workspace_write]} settings apply, which they do when
	 *                       the legacy syntax or no syntax selects the profile, but not when
	 *                       {@code default_permissions} names it
	 * @return the sandbox
	 * @throws PolicyRefusal if a writable root is not a path
	 */
	private CodexSandbox workspace(boolean legacySettings) throws PolicyRefusal
	{
		JsonNode settings;
		if (legacySettings)
			settings = configuration.merged().path("sandbox_workspace_write");
		else
			settings = JsonNodeFactory.instance.objectNode();
		for (JsonNode root : settings.path("writable_roots"))
			workspaceRoots.add(cwd.resolve(path(root.asString(""), "writable_roots")).normalize());
		List<PermissionEntry> entries = new ArrayList<>();
		for (Path root : FileSystems.getDefault().getRootDirectories())
			entries.add(new PermissionEntry(root, Access.READ));
		for (Path root : workspaceRoots)
			entries.add(new PermissionEntry(root, Access.WRITE));
		if (!settings.path("exclude_slash_tmp").asBoolean(false))
			slashTmp().ifPresent(tmp -> entries.add(new PermissionEntry(tmp, Access.WRITE)));
		if (!settings.path("exclude_tmpdir_env_var").asBoolean(false))
			tmpdir().ifPresent(tmp -> entries.add(new PermissionEntry(tmp, Access.WRITE)));
		if (settings.path("network_access").asBoolean(false))
			return new CodexSandbox(true, entries, HostAccess.ALL, true);
		return new CodexSandbox(true, entries, HostAccess.NONE, false);
	}

	/**
	 * Returns the sandbox of a {@code [permissions.<name>]} profile.
	 *
	 * @param name the profile's name
	 * @return the sandbox
	 * @throws IOException   if a folder cannot be listed
	 * @throws PolicyRefusal if the profile is undefined, its inheritance has a cycle, or an entry cannot be resolved
	 */
	private CodexSandbox userProfile(String name) throws IOException, PolicyRefusal
	{
		ObjectNode profile = resolveProfile(name);
		for (Entry<String, JsonNode> root : profile.path("workspace_roots").properties())
		{
			if (root.getValue().asBoolean(false))
				workspaceRoots.add(absolutePath(root.getKey(), "workspace_roots").normalize());
		}
		JsonNode filesystem = profile.path("filesystem");
		int globDepth = filesystem.path("glob_scan_max_depth").asInt(DEFAULT_GLOB_DEPTH);
		List<PermissionEntry> entries = new ArrayList<>();
		for (Entry<String, JsonNode> entry : filesystem.properties())
		{
			String key = entry.getKey();
			JsonNode value = entry.getValue();
			if (key.equals("glob_scan_max_depth"))
				continue;
			if (value.isObject())
			{
				for (Entry<String, JsonNode> scoped : value.properties())
					addEntries(key, scoped.getKey(), access(scoped.getValue()), globDepth, entries);
			}
			else
				addEntries(key, ".", access(value), globDepth, entries);
		}
		return withNetwork(entries, profile.path("network"));
	}

	/**
	 * Merges a profile with the profiles that it extends, the most distant parent first.
	 *
	 * @param name the profile's name
	 * @return the merged profile
	 * @throws PolicyRefusal if a profile is undefined or the inheritance has a cycle
	 */
	private ObjectNode resolveProfile(String name) throws PolicyRefusal
	{
		JsonNode permissions = configuration.merged().path("permissions");
		List<JsonNode> chain = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String next = name;
		while (true)
		{
			if (!seen.add(next))
			{
				throw new PolicyRefusal("the Codex permissions profile \"" + name + "\" extends itself",
					"Fix the profile's extends chain, then start a new Codex session");
			}
			JsonNode profile = permissions.get(next);
			if (profile == null || !profile.isObject())
				profile = builtinParent(next, name);
			chain.add(profile);
			JsonNode parent = profile.path("extends");
			if (!parent.isString())
				break;
			next = parent.stringValue();
		}
		ObjectNode merged = JsonNodeFactory.instance.objectNode();
		for (JsonNode profile : chain.reversed())
		{
			if (profile instanceof ObjectNode table)
				CodexConfiguration.mergeInto(merged, table);
		}
		return merged;
	}

	/**
	 * Returns a built-in profile in the form that a profile which extends it merges with.
	 *
	 * @param name    the built-in profile's name
	 * @param profile the profile whose chain names it
	 * @return the built-in profile's entries
	 * @throws PolicyRefusal if {@code name} is not a built-in profile that a profile may extend
	 */
	private static ObjectNode builtinParent(String name, String profile) throws PolicyRefusal
	{
		ObjectNode parent = JsonNodeFactory.instance.objectNode();
		ObjectNode filesystem = parent.putObject("filesystem");
		filesystem.put(":root", "read");
		switch (name)
		{
			case READ_ONLY ->
			{
			}
			case WORKSPACE ->
			{
				filesystem.putObject(":workspace_roots").put(".", "write");
				filesystem.put(":slash_tmp", "write");
				filesystem.put(":tmpdir", "write");
			}
			default -> throw new PolicyRefusal("the Codex permissions profile \"" + profile + "\" is not defined, or " +
				"extends \"" + name + "\", which is not defined", "Define the profile in Codex's config.toml, then " +
				"start a new Codex session");
		}
		return parent;
	}

	/**
	 * Adds the entries of one filesystem key and subpath.
	 *
	 * @param key       the key: a special path, an absolute path, or a path below {@code ~}
	 * @param subpath   the path below the key, or {@code .} for the key itself
	 * @param access    the access
	 * @param globDepth the number of folder levels that {@code **} reaches
	 * @param entries   the entries to add to
	 * @throws IOException   if a folder cannot be listed
	 * @throws PolicyRefusal if the key is a relative path
	 */
	private void addEntries(String key, String subpath, Access access, int globDepth, List<PermissionEntry> entries)
		throws IOException, PolicyRefusal
	{
		List<Path> bases = new ArrayList<>();
		if (key.startsWith(":"))
		{
			switch (key)
			{
				case ":root" -> bases.addAll(rootDirectories());
				case ":workspace_roots", ":project_roots" -> bases.addAll(workspaceRoots);
				case ":tmpdir" -> tmpdir().ifPresent(bases::add);
				case ":slash_tmp" -> slashTmp().ifPresent(bases::add);
				default ->
				{
					// Codex ignores :minimal on Linux and macOS for writes and unknown special paths with a warning;
					// leaving them out is narrower
				}
			}
		}
		else
			bases.add(absolutePath(key, "filesystem"));
		for (Path base : bases)
		{
			String target;
			if (subpath.equals("."))
				target = base.toString();
			else
				target = base.toString().replaceAll("/+$", "") + "/" + subpath;
			if (!GlobDenials.isGlob(target))
			{
				entries.add(new PermissionEntry(Path.of(target).normalize(), access));
				continue;
			}
			if (access == Access.DENY)
			{
				for (Path match : GlobDenials.expand(target, globDepth))
					entries.add(new PermissionEntry(match, Access.DENY));
				continue;
			}
			String subtree = target.replaceAll("/\\*\\*$", "");
			if (!GlobDenials.isGlob(subtree))
				entries.add(new PermissionEntry(Path.of(subtree).normalize(), access));
			// Codex does not fully enforce other read or write globs; leaving them out is narrower
		}
	}

	/**
	 * Returns the sandbox of some entries and a profile's network settings.
	 *
	 * @param entries the entries
	 * @param network the profile's {@code network} table
	 * @return the sandbox
	 */
	private CodexSandbox withNetwork(List<PermissionEntry> entries, JsonNode network)
	{
		if (!network.path("enabled").asBoolean(false))
			return new CodexSandbox(true, entries, HostAccess.NONE, false);
		JsonNode proxy = configuration.merged().path("features").path("network_proxy");
		boolean proxied = proxy.asBoolean(false) || proxy.path("enabled").asBoolean(false);
		if (!proxied)
			return new CodexSandbox(true, entries, HostAccess.ALL, true);
		Set<String> allowed = new LinkedHashSet<>();
		Set<String> denied = new LinkedHashSet<>();
		for (Entry<String, JsonNode> domain : network.path("domains").properties())
		{
			Set<String> target;
			if (domain.getValue().asString("").equals("allow"))
				target = allowed;
			else
				target = denied;
			target.addAll(hostPatterns(domain.getKey()));
		}
		Set<String> kept = new LinkedHashSet<>();
		for (String pattern : allowed)
		{
			HostAccess one = HostAccess.listed(Set.of(pattern));
			boolean overlaps = false;
			for (String deny : denied)
				overlaps |= one.covers(deny) || HostAccess.listed(Set.of(deny)).covers(pattern);
			if (!overlaps)
				kept.add(pattern);
		}
		boolean allowLocalBinding = network.path("allow_local_binding").asBoolean(false);
		return new CodexSandbox(true, entries, CodexAssumptions.managedProxyHosts(kept, allowLocalBinding),
			allowLocalBinding);
	}

	/**
	 * Returns the host patterns of a Codex domain pattern that CAT can express.
	 *
	 * @param domain a domain, {@code *.domain} for its subdomains, or {@code **.domain} for the domain and its
	 *               subdomains
	 * @return the patterns, or none if CAT cannot express the domain pattern
	 */
	private static Set<String> hostPatterns(String domain)
	{
		String normalized = domain.strip();
		if (normalized.startsWith("**."))
		{
			String name = normalized.substring(3);
			if (name.isEmpty() || name.contains("*"))
				return Set.of();
			return Set.of(name, "*." + name);
		}
		String rest = normalized;
		if (normalized.startsWith("*."))
			rest = normalized.substring(2);
		if (rest.isEmpty() || rest.contains("*"))
			return Set.of();
		return Set.of(normalized);
	}

	/**
	 * Returns the access that an entry's value names.
	 *
	 * @param value the value
	 * @return the access
	 * @throws PolicyRefusal if the value names no access that the reader knows
	 */
	private static Access access(JsonNode value) throws PolicyRefusal
	{
		Access access = Access.ofName(value.asString(""));
		if (access == null)
		{
			throw new PolicyRefusal("a Codex permissions profile gives a path the access \"" + value + "\", which " +
				"CAT does not know", "Use read, write or none, then start a new Codex session");
		}
		return access;
	}

	/**
	 * Resolves an absolute path, or a path below the user's home that starts with {@code ~}.
	 *
	 * @param text the path
	 * @param key  the configuration key that holds it
	 * @return the absolute path
	 * @throws PolicyRefusal if the path is relative or not a path
	 */
	private Path absolutePath(String text, String key) throws PolicyRefusal
	{
		String home = environment.getOrDefault("HOME", "");
		if ((text.equals("~") || text.startsWith("~/")) && !home.isBlank())
			return path(home, "HOME").resolve(text.substring(1).replaceAll("^/+", ""));
		Path path = path(text, key);
		if (!path.isAbsolute())
		{
			throw new PolicyRefusal("the Codex configuration's " + key + " names the relative path \"" + text +
				"\", which CAT cannot resolve the way Codex does", "Use an absolute path, then start a new Codex " +
				"session");
		}
		return path;
	}

	/**
	 * Parses a path.
	 *
	 * @param text the path
	 * @param key  the configuration key or option that holds it
	 * @return the path
	 * @throws PolicyRefusal if the text is not a path
	 */
	private static Path path(String text, String key) throws PolicyRefusal
	{
		try
		{
			return Path.of(text);
		}
		catch (InvalidPathException e)
		{
			throw new PolicyRefusal("the Codex " + key + " \"" + text + "\" is not a path: " + e.getReason(),
				"Fix the path, then start a new Codex session");
		}
	}

	/**
	 * Returns the file system roots.
	 *
	 * @return the roots
	 */
	private static List<Path> rootDirectories()
	{
		List<Path> roots = new ArrayList<>();
		FileSystems.getDefault().getRootDirectories().forEach(roots::add);
		return roots;
	}

	/**
	 * Returns the folder that {@code TMPDIR} names.
	 *
	 * @return the folder, or empty if {@code TMPDIR} names no absolute folder
	 */
	private Optional<Path> tmpdir()
	{
		String tmpdir = environment.getOrDefault("TMPDIR", "");
		try
		{
			Path path = Path.of(tmpdir);
			if (!tmpdir.isBlank() && path.isAbsolute())
				return Optional.of(path.normalize());
		}
		catch (InvalidPathException _)
		{
			// Codex ignores a TMPDIR that is not a path
		}
		return Optional.empty();
	}

	/**
	 * Returns {@code /tmp}.
	 *
	 * @return the folder, or empty on a platform without one
	 */
	private static Optional<Path> slashTmp()
	{
		Path tmp = Path.of("/tmp");
		if (tmp.isAbsolute())
			return Optional.of(tmp);
		return Optional.empty();
	}
}

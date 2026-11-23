package com.holdmyspot.cat.codex.runner;

import com.holdmyspot.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds approvals to native hook records from the original and installed trial plugins.
 */
public final class CodexPluginHooks
{
	/** The runner's record of full native hook hashes for this trial. */
	static final String BINDINGS_FILE = "codex-hook-bindings.json";
	/** Maximum size of a retained hook bindings record. */
	private static final int MAXIMUM_BINDINGS_BYTES = 1_048_576;

	/**
	 * Codex's declaration and artifact digests for an installed plugin.
	 */
	private static final Pattern PLUGIN_HASH = Pattern.compile("sha256:[0-9a-f]{64}/sha256:[0-9a-f]{64}");

	/**
	 * Reads the hook records returned for one inspected working directory.
	 *
	 * @param result the native app-server result
	 * @param project the inspected project
	 * @return the hook records by their exact native keys
	 * @throws IOException if the result is missing, belongs to a different project, or is ambiguous
	 */
	public static Map<String, JsonNode> records(JsonNode result, Path project) throws IOException
	{
		JsonNode data = result.path("data");
		if (!data.isArray() || data.size() != 1)
			throw new IOException("Codex did not return a unique hook list for the inspected project");
		JsonNode directory = data.get(0);
		String cwd = directory.path("cwd").asString("");
		if (cwd.isBlank() || !Path.of(cwd).isAbsolute() || !Path.of(cwd).toRealPath().equals(project.toRealPath()))
			throw new IOException("Codex returned hooks for a different project");
		JsonNode errors = directory.path("errors");
		JsonNode hooks = directory.path("hooks");
		if (!errors.isArray() || !errors.isEmpty() || !hooks.isArray())
			throw new IOException("Codex could not list hooks for the inspected project");
		Map<String, JsonNode> byKey = new LinkedHashMap<>();
		for (JsonNode hook : hooks)
		{
			String key = hook.path("key").asString("");
			if (key.isBlank() || byKey.putIfAbsent(key, hook) != null)
				throw new IOException("Codex returned an ambiguous hook key");
		}
		return byKey;
	}

	/**
	 * Selects the enabled hooks whose exact original approval covers the installed trial artifact.
	 *
	 * @param source records from the protected home
	 * @param trial records from the isolated home
	 * @param original the requested original installation
	 * @param isolated the corresponding trial installation
	 * @param approvals the host's existing approvals, with exact native keys
	 * @return each eligible pair of native identities and the full hash that must still match after transfer
	 * @throws IOException if a native declaration path cannot be resolved
	 */
	public static List<Binding> bind(Map<String, JsonNode> source, Map<String, JsonNode> trial,
		Installation original, Installation isolated, Map<String, String> approvals) throws IOException
	{
		return bind(source, trial, original, isolated, approvals, true);
	}

	/**
	 * Selects the enabled hooks of CAT's own plugin, built from the project, whose original approval covers the trial
	 * copy's hook declaration. The build's other files, and so its artifact hash, may differ from those of the approved
	 * installation: the approved hooks then run the launchers that the project built, not the approved ones.
	 *
	 * @param source records from the protected home
	 * @param trial records from the isolated home
	 * @param original the user's installed CAT
	 * @param isolated the trial's copy of CAT built from the project
	 * @param approvals the host's existing approvals, with exact native keys
	 * @return each eligible pair of native identities and the trial copy's full hash, which must still match after
	 *   transfer
	 * @throws IOException if a native declaration path cannot be resolved
	 */
	public static List<Binding> bindDeclarations(Map<String, JsonNode> source, Map<String, JsonNode> trial,
		Installation original, Installation isolated, Map<String, String> approvals) throws IOException
	{
		return bind(source, trial, original, isolated, approvals, false);
	}

	/**
	 * Selects the enabled hooks whose exact original approval covers the trial copy's hook.
	 *
	 * @param source records from the protected home
	 * @param trial records from the isolated home
	 * @param original the requested original installation
	 * @param isolated the corresponding trial installation
	 * @param approvals the host's existing approvals, with exact native keys
	 * @param sameArtifact {@code true} to require the trial copy's full hash to equal the original's; {@code false}
	 *   to require only its declaration hash to
	 * @return each eligible pair of native identities and the trial copy's full hash
	 * @throws IOException if a native declaration path cannot be resolved
	 */
	private static List<Binding> bind(Map<String, JsonNode> source, Map<String, JsonNode> trial,
		Installation original, Installation isolated, Map<String, String> approvals, boolean sameArtifact)
		throws IOException
	{
		String originalPrefix = original.id() + ":";
		List<Binding> bound = new ArrayList<>();
		for (Map.Entry<String, String> approval : approvals.entrySet())
		{
			String key = approval.getKey();
			if (!key.startsWith(originalPrefix))
				continue;
			JsonNode before = source.get(key);
			if (before == null || !"trusted".equals(before.path("trustStatus").asString("")))
				continue;
			String hash = before.path("currentHash").asString("");
			if (!PLUGIN_HASH.matcher(hash).matches() || !declarationHash(hash).equals(approval.getValue()))
				continue;
			String trialKey = isolated.id() + key.substring(original.id().length());
			JsonNode after = trial.get(trialKey);
			if (after == null)
				continue;
			String copied = after.path("currentHash").asString("");
			if (sameArtifact)
			{
				if (!hash.equals(copied))
					continue;
			}
			else if (!PLUGIN_HASH.matcher(copied).matches() || !declarationHash(copied).equals(declarationHash(hash)))
				continue;
			Path originalPath = declaration(before, original);
			Path trialPath = declaration(after, isolated);
			if (originalPath != null && originalPath.equals(trialPath))
				bound.add(new Binding(key, trialKey, copied));
		}
		return List.copyOf(bound);
	}

	/**
	 * The exact native identities that one protected approval binds to an unchanged artifact.
	 *
	 * @param originalKey the protected original installation's hook key
	 * @param trialKey the isolated installation's hook key
	 * @param hash the full native digest shared by both hooks
	 */
	public record Binding(String originalKey, String trialKey, String hash)
	{
	}

	/**
	 * Returns the declaration's plugin-relative path when the record is enabled and belongs to that installation.
	 *
	 * @param hook the native hook
	 * @param installation the requested plugin identity and canonical cache roots
	 * @return the relative path, or {@code null} when the hook is outside this installation or disabled
	 * @throws IOException if a declaration path cannot be resolved
	 */
	private static Path declaration(JsonNode hook, Installation installation) throws IOException
	{
		if (!"plugin".equals(hook.path("source").asString("")) ||
			!installation.id().equals(hook.path("pluginId").asString("")) ||
			!hook.path("enabled").isBoolean() || !hook.path("enabled").booleanValue())
		{
			return null;
		}
		String source = hook.path("sourcePath").asString("");
		if (source.isBlank() || !Path.of(source).isAbsolute())
			return null;
		Path actual = Path.of(source).toRealPath();
		for (Path root : installation.roots())
		{
			if (actual.startsWith(root) && !actual.equals(root))
				return root.relativize(actual);
		}
		return null;
	}

	/**
	 * Checks that the isolated native app-server accepted each transferred hook for the same artifact.
	 *
	 * @param records the isolated app-server's final native hook records
	 * @param bound the keys and full hashes that CAT transferred
	 * @throws IOException if a transferred hook is absent, disabled, changed or untrusted
	 */
	public static void requireTrusted(Map<String, JsonNode> records, Map<String, String> bound) throws IOException
	{
		for (Map.Entry<String, String> expected : bound.entrySet())
		{
			JsonNode hook = records.get(expected.getKey());
			if (hook == null || !hook.path("enabled").isBoolean() || !hook.path("enabled").booleanValue() ||
				!"trusted".equals(hook.path("trustStatus").asString("")) ||
				!expected.getValue().equals(hook.path("currentHash").asString("")))
			{
				throw new IOException("Codex did not accept the exact transferred plugin hook " + expected.getKey());
			}
		}
	}

	/**
	 * Records the complete hashes before launching a thread with the approvals.
	 *
	 * @param json the runner's mapper
	 * @param file the new runner-owned record
	 * @param bound the transferred keys and hashes
	 * @throws IOException if the record cannot be written
	 */
	public static void saveBindings(JsonMapper json, Path file, Map<String, String> bound) throws IOException
	{
		Files.writeString(file, json.writeValueAsString(bound), StandardOpenOption.CREATE_NEW);
	}

	/**
	 * Checks that the retained configuration grants the same ordinary hook approvals as the original launch.
	 *
	 * @param json the runner's mapper
	 * @param file the original launch's protected full-hash record
	 * @param configured the retained declaration approvals
	 * @param installCat whether CAT's managed approvals are verified separately
	 * @return the full hashes that the native app-server must verify before resuming
	 * @throws IOException if the retained approvals cannot be tied to the original launch
	 */
	public static Map<String, String> resumeBindings(JsonMapper json, Path file, Map<String, String> configured,
		boolean installCat) throws IOException
	{
		Map<String, String> ordinary = new LinkedHashMap<>(configured);
		if (installCat)
			CodexConnectionConfiguration.CAT_HOOK_KEYS.forEach(ordinary::remove);
		if (ordinary.isEmpty() && Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return Map.of();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The retained Codex trial has no original plugin hook bindings");
		JsonNode saved;
		try
		{
			saved = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_BINDINGS_BYTES, "Codex hook bindings"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The retained Codex trial's plugin hook bindings are invalid", e);
		}
		if (!saved.isObject() || saved.size() != ordinary.size())
			throw new IOException("The retained Codex trial's plugin hook approvals changed");
		Map<String, String> bound = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : saved.properties())
		{
			String hash = entry.getValue().asString("");
			if (!PLUGIN_HASH.matcher(hash).matches() || !declarationHash(hash).equals(ordinary.get(entry.getKey())))
				throw new IOException("The retained Codex trial's plugin hook approval changed for " + entry.getKey());
			bound.put(entry.getKey(), hash);
		}
		return bound;
	}

	/**
	 * Extracts the component stored by Codex as the configured approval.
	 *
	 * @param nativeHash the validated composite plugin hook hash
	 * @return the declaration hash
	 */
	static String declarationHash(String nativeHash)
	{
		return nativeHash.substring(0, nativeHash.indexOf('/'));
	}

	/**
	 * A selected plugin as identified in one native installations directory.
	 *
	 * @param id the exact native plugin and marketplace ID
	 * @param roots the canonical registered cache roots for that ID
	 */
	public record Installation(String id, List<Path> roots)
	{
		/**
		 * Creates the identity and cache roots of one plugin.
		 *
		 * @param id the exact native plugin and marketplace ID
		 * @param roots the canonical registered cache roots for that ID
		 */
		public Installation
		{
			roots = List.copyOf(roots);
		}
	}

	/**
	 * Prevents instantiation of this utility class.
	 */
	private CodexPluginHooks()
	{
	}
}

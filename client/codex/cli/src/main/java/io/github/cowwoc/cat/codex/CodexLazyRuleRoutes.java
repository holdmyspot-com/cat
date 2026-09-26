package io.github.cowwoc.cat.codex;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Directs Codex to the account-gated command when packaged lazy rules are needed.
 */
final class CodexLazyRuleRoutes
{
	private static final Pattern SHELL_READ = Pattern.compile(
		"cat\\s+\"\\$CAT_PLUGIN_ROOT/rules/(include/(?:[a-z0-9-]+/)*[a-z0-9-]+\\.md)\"");
	private static final Pattern MARKDOWN_REFERENCE = Pattern.compile("`([^`]+\\.md)`");
	private static final String COMMAND = "\"$CAT_PLUGIN_ROOT/client/bin/rule-load\" ";
	private static final String GUIDANCE = "Before loading a packaged CAT rule under `rules/include/`, derive " +
		"`CAT_PLUGIN_ROOT` using the installed Codex skill guidance, then run " +
		"`\"$CAT_PLUGIN_ROOT/client/bin/rule-load\" include/<rule>.md`. " +
		"This command checks the selected account and records the rule delivery. " +
		"Use the command for packaged lazy rules referenced below.\n\n---\n";

	/**
	 * Rewrites installed-rule routes in hook context while preserving user and project rule paths.
	 *
	 * @param context direct context about to be supplied by a Codex hook
	 * @param pluginRoot installed CAT directory
	 * @return context whose packaged lazy routes use the account-gated command
	 */
	static String hookContext(String context, Path pluginRoot)
	{
		return GUIDANCE + rewrite(context, pluginRoot, null);
	}

	/**
	 * Rewrites references within one packaged lazy rule to load its packaged dependencies through the same command.
	 *
	 * @param content body of the lazy rule being delivered
	 * @param pluginRoot installed CAT directory
	 * @param source rule file that owns relative references
	 * @return rule content with resolvable packaged lazy references mediated
	 */
	static String lazyBody(String content, Path pluginRoot, Path source)
	{
		return rewrite(content, pluginRoot, source);
	}

	/**
	 * Replaces the known packaged shell reads and resolvable Markdown rule paths.
	 *
	 * @param content source content
	 * @param pluginRoot installed CAT directory
	 * @param source packaged lazy-rule file, or null for pre-expanded hook context
	 * @return content with packaged lazy reads expressed as commands
	 */
	private static String rewrite(String content, Path pluginRoot, Path source)
	{
		Matcher shell = SHELL_READ.matcher(content);
		StringBuffer commands = new StringBuffer(content.length());
		while (shell.find())
			shell.appendReplacement(commands, Matcher.quoteReplacement(COMMAND + shell.group(1)));
		shell.appendTail(commands);

		Matcher references = MARKDOWN_REFERENCE.matcher(commands);
		StringBuffer result = new StringBuffer(commands.length());
		while (references.find())
		{
			String featureId = featureId(references.group(1), pluginRoot, source);
			if (featureId != null)
				references.appendReplacement(result, Matcher.quoteReplacement("`" + COMMAND + featureId + "`"));
		}
		references.appendTail(result);
		return result.toString();
	}

	/**
	 * Resolves a Markdown path only when it names an installed lazy rule.
	 *
	 * @param value path shown in Markdown
	 * @param pluginRoot installed CAT directory
	 * @param source packaged lazy-rule file, or null for pre-expanded hook context
	 * @return feature ID relative to {@code rules/}, or null when the path is not a packaged lazy rule
	 */
	private static String featureId(String value, Path pluginRoot, Path source)
	{
		Path ruleRoot = pluginRoot.resolve("rules").toAbsolutePath().normalize();
		Path includeRoot = ruleRoot.resolve("include");
		Path resolved;
		try
		{
			if (value.startsWith("rules/include/"))
				resolved = pluginRoot.resolve(value);
			else
			{
				Path candidate = Path.of(value);
				if (candidate.isAbsolute())
					resolved = candidate;
				else if (source != null)
					resolved = source.getParent().resolve(candidate);
				else
					return null;
			}
		}
		catch (InvalidPathException _)
		{
			return null;
		}
		resolved = resolved.toAbsolutePath().normalize();
		if (!resolved.startsWith(includeRoot) || !Files.isRegularFile(resolved, NOFOLLOW_LINKS))
			return null;
		return ruleRoot.relativize(resolved).toString().replace('\\', '/');
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexLazyRuleRoutes()
	{
	}
}

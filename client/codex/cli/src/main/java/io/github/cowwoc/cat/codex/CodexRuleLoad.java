package io.github.cowwoc.cat.codex;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.auth.WorkOsAccountAccess;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Supplies one packaged lazy CAT rule only while a saved account may use the product.
 */
public final class CodexRuleLoad
{
	private static final int MAXIMUM_RULE_BYTES = 1_048_576;

	/**
	 * Reads one packaged rule from the image that contains this command.
	 *
	 * @param args one rule ID relative to the installed {@code rules/} directory
	 */
	public static void main(String[] args)
	{
		Path pluginRoot = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().getParent();
		try
		{
			load(System.getenv(), pluginRoot, args, System.out, System.err);
		}
		catch (IllegalArgumentException | IOException exception)
		{
			System.err.println(exception.getMessage());
			System.exit(1);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			System.err.println("CAT rule loading was interrupted; retry the command");
			System.exit(1);
		}
	}

	/**
	 * Checks account access, retains a feature event, and supplies one bounded packaged rule body.
	 *
	 * @param environment process environment containing the private Codex login
	 * @param pluginRoot installation directory containing packaged rules
	 * @param args one packaged lazy-rule ID
	 * @param output rule content delivered to the caller
	 * @param error diagnostics when a delivered event remains queued
	 * @throws IOException if access, rule content, or event persistence fails
	 * @throws InterruptedException if the account request is interrupted
	 */
	static void load(Map<String, String> environment, Path pluginRoot, String[] args, OutputStream output,
		PrintStream error) throws IOException, InterruptedException
	{
		if (args == null || args.length != 1 || args[0] == null ||
			!args[0].matches("include/(?:[a-z0-9-]+/)*[a-z0-9-]+\\.md"))
			throw new IllegalArgumentException("Usage: rule-load include/<packaged-rule>.md");
		String featureId = args[0];
		Path file = lazyRule(pluginRoot, featureId);
		WorkOsAccountAccess.CheckResult access = CodexRuleInjectionHook.checkAccount(environment);
		if (access.authorizedAccount().isEmpty())
			throw new IOException(CodexRuleInjectionHook.accessMessage(access.decision(), pluginRoot));
		String content = BoundedInput.readUtf8(Files.newInputStream(file, READ, NOFOLLOW_LINKS), file,
			MAXIMUM_RULE_BYTES, "CAT lazy rule");
		content = CodexLazyRuleRoutes.lazyBody(content, pluginRoot, file);
		CodexRuleInjectionHook.enqueueFeatureInvocation(environment, access.authorizedAccount().orElseThrow(),
			featureId);
		output.write(content.getBytes(StandardCharsets.UTF_8));
		output.flush();
		if (output instanceof PrintStream print && print.checkError())
			throw new IOException("CAT lazy rule could not be written to the caller");
		if (access.decision() == WorkOsAccountAccess.Decision.ONLINE)
		{
			try
			{
				CodexRuleInjectionHook.flushPendingFeatures(environment);
			}
			catch (IOException exception)
			{
				error.println("CAT rule-load: Pending feature invocations remain queued: " +
					exception.getMessage());
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				error.println("CAT rule-load: Pending feature invocations remain queued because delivery was interrupted");
			}
		}
	}

	/**
	 * Resolves one named packaged lazy rule without following links out of the installed rule tree.
	 *
	 * @param pluginRoot installed plugin directory
	 * @param featureId validated rule ID beneath {@code rules/include/}
	 * @return regular rule file
	 * @throws IOException if the installed rule is unavailable or unsafe
	 */
	private static Path lazyRule(Path pluginRoot, String featureId) throws IOException
	{
		if (pluginRoot == null || !Files.isDirectory(pluginRoot, NOFOLLOW_LINKS))
			throw new IOException("CAT installation directory is unavailable");
		Path rules = pluginRoot.resolve("rules");
		if (!Files.isDirectory(rules, NOFOLLOW_LINKS))
			throw new IOException("CAT packaged rules directory is unavailable");
		Path relative = Path.of(featureId);
		Path directory = rules;
		for (Path component: relative.getParent())
		{
			directory = directory.resolve(component);
			if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
				throw new IOException("CAT packaged lazy-rule directory is unavailable");
		}
		Path file = directory.resolve(relative.getFileName());
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
			throw new IOException("CAT packaged lazy rule is unavailable: " + featureId);
		return file;
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexRuleLoad()
	{
	}
}

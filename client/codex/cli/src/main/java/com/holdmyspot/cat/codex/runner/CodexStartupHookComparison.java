package com.holdmyspot.cat.codex.runner;

import com.holdmyspot.cat.codex.CommandShell;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HookInspection;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookPair;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolves startup-hook comparisons from protected Codex approvals and a fresh isolated plugin installation.
 */
public final class CodexStartupHookComparison
{
	private static final String CODEX_HOME = "CODEX_HOME";
	private static final String STARTUP = "startup";
	private static final String SESSION_START = "SessionStart";
	private static final String PROMPT_SUBMIT = "UserPromptSubmit";
	private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(20);

	/**
	 * Installs the frozen plugin copies without network access, verifies their exact native approvals, and returns only
	 * startup hooks that the protected owner and the isolated native trial both accept.
	 *
	 * @param context the owner's authenticated native installation and process services
	 * @param inputs the staged plugin copies, with exact owner marketplace IDs for installed plugins
	 * @param production the copied complete fork
	 * @param trial the copied trial project
	 * @param trialHome the new native home beside the trial project
	 * @param prompt the fixed user prompt for the comparison
	 * @return approved invocations in native production order and the hooks lacking verified approval
	 * @throws IOException if the installation or approval cannot be verified
	 * @throws InterruptedException if installation is interrupted
	 */
	public static HookInspection resolve(Context context, List<TrialPluginInput> inputs, Path production, Path trial,
		Path trialHome, String prompt) throws IOException, InterruptedException
	{
		if (inputs.isEmpty())
			return new HookInspection(List.of(), Optional.of(List.of()));
		Path home = trialHome.toRealPath();
		Path project = trial.toRealPath();
		Path scratch = home.getParent();
		if (!project.getParent().equals(scratch) || !context.policy().mayWrite(home))
			throw new IOException("The Codex comparison installation must be inside the writable trial directory");
		Path sandbox = context.sandbox().or(() -> CodexRunner.bubblewrap(context.environment())).orElseThrow(() ->
			new IOException("Codex startup-hook inspection needs bubblewrap")).toRealPath();
		Path executable = Path.of(Platform.current().findProgram(context.executable(), context.environment())).
			toRealPath();
		CodexHookInspection.requireReadableExecutables(context.policy(), sandbox, executable);
		try
		{
			context.policy().requireTrusted(sandbox);
			context.policy().requireTrusted(executable);
		}
		catch (PolicyRefusal e)
		{
			throw new IOException("Codex cannot prepare startup-hook comparisons using an untrusted launcher", e);
		}
		Map<String, String> pluginNames = new LinkedHashMap<>();
		for (TrialPluginInput input : inputs)
		{
			if (!context.policy().mayRead(input.path()))
				throw new IOException("The owner cannot read all of the plugin used for hook comparison: " + input.path());
			String name = PluginManifest.name(input.path(), CodexRunner.MANIFEST);
			pluginNames.put(name + "@" + name, input.installedId().orElse(name));
			Path marketplace = CodexMarketplace.create(input.path(), name, name, home);
			for (List<String> command : CodexRunner.pluginSetupCommands(executable.toString(), marketplace, name))
				install(context.scope(), sandbox, home, project, command, context.policy());
		}
		CodexRunner.TrialHookEvidence evidence = CodexRunner.inspectTrialHooks(inputs, context.home(), home,
			context.project(), project, scratch, context.executable(), context.policy(), context.environment(),
			Optional.of(sandbox), context.scope());
		Map<String, String> expected = new LinkedHashMap<>();
		for (CodexPluginHooks.Binding binding : evidence.bindings())
			expected.put(binding.trialKey(), binding.hash());
		CodexConnectionConfiguration.copyBoundHooks(home.resolve("config.toml"), expected);
		Map<String, JsonNode> accepted = CodexHookInspection.readTrial(home, project, scratch, sandbox, executable,
			context.policy(), CodexRunner.featureFlags(true), context.scope());
		CodexPluginHooks.requireTrusted(accepted, expected);
		List<HookPair> pairs = new ArrayList<>();
		List<CodexPluginHooks.Binding> ordered = new ArrayList<>(evidence.bindings());
		ordered.sort(Comparator.comparingLong(binding ->
			evidence.original().get(binding.originalKey()).path("displayOrder").asLong()));
		for (CodexPluginHooks.Binding binding : ordered)
		{
			JsonNode original = evidence.original().get(binding.originalKey());
			String event = earlyEvent(original);
			if (event.isEmpty())
				continue;
			JsonNode copied = accepted.get(binding.trialKey());
			pairs.add(new HookPair(invocation(context, original, context.home(), production, event, prompt),
				invocation(context, copied, home, project, event, prompt)));
		}
		List<String> unapproved = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : accepted.entrySet())
		{
			JsonNode hook = entry.getValue();
			String plugin = pluginNames.get(hook.path("pluginId").asString(""));
			if (plugin == null || !"plugin".equals(hook.path("source").asString("")) ||
				expected.containsKey(entry.getKey()))
			{
				continue;
			}
			String event = hook.path("eventName").asString();
			if (event.isEmpty())
				throw new IOException("Codex returned a plugin hook without an event");
			String action = hook.path("command").asString(hook.path("handlerType").asString());
			unapproved.add(plugin + " " + Character.toUpperCase(event.charAt(0)) + event.substring(1) + " `" +
				action + "`");
		}
		return new HookInspection(pairs, Optional.of(unapproved));
	}

	/**
	 * Runs a local native installation step with only the new home writable and without networking.
	 *
	 * @param scope process services
	 * @param sandbox the protected bubblewrap executable
	 * @param home the new native home
	 * @param project the comparison project that Codex uses as its working directory
	 * @param command the native installation command
	 * @param policy the authenticated owner's filesystem policy
	 * @throws IOException if installation fails or times out
	 * @throws InterruptedException if installation is interrupted
	 */
	private static void install(JvmScope scope, Path sandbox, Path home, Path project, List<String> command,
		ParentPolicy policy) throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path scratch = cleanup.add(Files.createTempDirectory(home, ".hook-install-")).toRealPath();
			List<String> confined = CodexHookInspection.readOnlyView(sandbox);
			confined.addAll(List.of("--bind", home.toString(), home.toString()));
			CodexHookInspection.applyReadDenials(confined, policy, scratch);
			confined.addAll(List.of("--clearenv", "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "HOME",
				home.toString(), "--setenv", CODEX_HOME, home.toString(), "--setenv", "TMPDIR", home.toString(), "--chdir",
				project.toString(), "--"));
			confined.addAll(command);
			ProcessBuilder builder = new ProcessBuilder(confined).directory(project.toFile());
			builder.environment().clear();
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
			try (Process process = scope.executableFiles().run(builder))
			{
				try
				{
					if (!process.waitFor(SETUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0)
						throw new IOException("Codex could not install the frozen plugin for startup-hook comparison");
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly();
				}
			}
		}
	}

	/**
	 * Selects command hooks that fire for a new session or its first prompt.
	 *
	 * @param hook the approved native record
	 * @return the hook input event, or empty when this hook does not run before the model
	 */
	private static String earlyEvent(JsonNode hook)
	{
		if (!"command".equals(hook.path("handlerType").asString("")))
			return "";
		if ("userPromptSubmit".equals(hook.path("eventName").asString("")))
			return PROMPT_SUBMIT;
		if (!"sessionStart".equals(hook.path("eventName").asString("")))
			return "";
		String matcher = hook.path("matcher").asString("");
		if (matcher.isEmpty() || matcher.equals("*"))
			return SESSION_START;
		try
		{
			if (Pattern.matches(matcher, STARTUP))
				return SESSION_START;
			return "";
		}
		catch (PatternSyntaxException _)
		{
			return "";
		}
	}

	/**
	 * Renders the exact command from the approved native record for one comparison workspace.
	 *
	 * @param context the owner installation and process environment
	 * @param hook the approved native hook record
	 * @param home the Codex home that installed the hook
	 * @param project the workspace copy in which to execute the hook
	 * @param event the hook's input event
	 * @param prompt the fixed comparison prompt
	 * @return the resolved invocation
	 * @throws IOException if the hook no longer belongs to its registered plugin
	 */
	private static HookInvocation invocation(Context context, JsonNode hook, Path home, Path project, String event,
		String prompt) throws IOException
	{
		String id = hook.path("pluginId").asString();
		Path declaration = Path.of(hook.path("sourcePath").asString()).toRealPath();
		Path root = CodexRunner.pluginRoots(home, id).stream().filter(declaration::startsWith).findFirst().orElseThrow(
			() -> new IOException("Codex returned a startup hook outside the installed plugin " + id));
		int separator = id.indexOf('@');
		Path data = home.resolve("plugins/data").resolve(id.substring(0, separator) + "-" +
			id.substring(separator + 1));
		Map<String, String> variables = new LinkedHashMap<>(Map.of(CODEX_HOME, home.toString(),
			"PLUGIN_ROOT", root.toString(), "CLAUDE_PLUGIN_ROOT", root.toString(), "PLUGIN_DATA", data.toString(),
			"CLAUDE_PLUGIN_DATA", data.toString()));
		if (!home.equals(context.home()))
			variables.put("HOME", home.toString());
		ObjectNode input = context.scope().json().createObjectNode().put("session_id",
			"00000000-0000-0000-0000-000000000000").put("cwd", project.toString()).
			put("hook_event_name", event).put("permission_mode", "default");
		if (event.equals(SESSION_START))
			input.put("source", STARTUP);
		else
			input.put("prompt", prompt);
		String command = hook.path("command").asString();
		String name = "the " + event + " hook `" + command + "` of `" + id + "`";
		return new HookInvocation(name, CommandShell.current().processArguments(command, context.environment()),
			variables, input.toString(), Duration.ofSeconds(hook.path("timeoutSec").asLong(600)));
	}

	/**
	 * The authenticated environment in which the original plugin approvals can be inspected.
	 *
	 * @param project the original project
	 * @param home the owner Codex home
	 * @param executable the native command name
	 * @param environment the owner's launcher environment
	 * @param policy the authenticated owner's tool policy
	 * @param scope native process services
	 * @param sandbox bubblewrap selected by the broker, or empty to find it in the owner's environment
	 */
	public record Context(Path project, Path home, String executable, Map<String, String> environment,
		ParentPolicy policy, HarnessRunnerScope scope, Optional<Path> sandbox)
	{
		/**
		 * Creates a local comparison context that finds bubblewrap in the owner's environment.
		 *
		 * @param project the original project
		 * @param home the owner Codex home
		 * @param executable the native command name
		 * @param environment the owner's launcher environment
		 * @param policy the authenticated owner's tool policy
		 * @param scope native process services
		 */
		public Context(Path project, Path home, String executable, Map<String, String> environment,
			ParentPolicy policy, HarnessRunnerScope scope)
		{
			this(project, home, executable, environment, policy, scope, Optional.empty());
		}
	}

	/** Prevents instantiation. */
	private CodexStartupHookComparison()
	{
	}
}

package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.auth.WorkOsAccountAccess;
import io.github.cowwoc.cat.common.auth.WorkOsDeviceAuthClient;
import io.github.cowwoc.cat.common.auth.WorkOsSessionStore;
import io.github.cowwoc.cat.common.usage.FeatureInvocationOutbox;
import io.github.cowwoc.cat.common.usage.UsageReportOutbox;
import io.github.cowwoc.cat.common.usage.WorkOsFeatureInvocationDelivery;
import io.github.cowwoc.cat.common.usage.WorkOsUsageDelivery;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implements the shared JSON boundary for Codex rule-injection hooks.
 */
final class CodexRuleInjectionHook
{
	/**
	 * Prevents instantiation.
	 */
	private CodexRuleInjectionHook()
	{
	}

	/**
	 * Loads rules for one Codex hook event and emits its native JSON response.
	 *
	 * @param eventName the Codex hook event name
	 * @param args the hook command-line arguments
	 * @param input the native hook input
	 * @param output the native hook output
	 * @param error diagnostics for the host
	 * @param environment the hook environment
	 * @param workingDirectory the hook process directory
	 */

	static void run(String eventName, String[] args, InputStream input, OutputStream output, PrintStream error,
	Map<String, String> environment, Path workingDirectory)
{
	try (CodexHookScope scope = new CodexHookScope(environment))
	{
		try
		{
			JsonMapper json = scope.json();
			if (args.length != 0)
				throw new IllegalArgumentException(eventName + " accepts no arguments");
			JsonNode event = CodexHookInput.read(json, input, eventName);
			Path projectDir = scope.projectDir(event, workingDirectory);
			Path pluginRoot = scope.pluginRoot();
			WorkOsAccountAccess.CheckResult access;
			try
			{
				access = checkAccount(environment);
			}
			catch (IOException e)
			{
				error.println("CAT " + eventName + ": Saved login could not be used: " + e.getMessage());
				writeDisabled(json, eventName, output, "CAT features are disabled because the saved login could not " +
					"be used. Check CAT's local credentials, then retry or run " +
					pluginRoot.resolve("client/bin/login") + ".");
				return;
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				error.println("CAT " + eventName + ": Account check was interrupted");
				writeDisabled(json, eventName, output, "CAT features are disabled because the account check " +
					"was interrupted. Retry when connectivity is restored.");
				return;
			}
			WorkOsAccountAccess.Decision decision = access.decision();
			if (decision != WorkOsAccountAccess.Decision.ONLINE && decision != WorkOsAccountAccess.Decision.OFFLINE)
			{
				writeDisabled(json, eventName, output, accessMessage(decision, pluginRoot));
				return;
			}
			Path userRulesRoot = scope.userRulesRoot();
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml());
			ProjectRuleLoader.LoadedRules loaded;
			if (eventName.equals("SubagentStart"))
				loaded = loader.loadForSubagentWithDelivery(pluginRoot, userRulesRoot, projectDir, "codex",
					subagentType(event));
			else
				loaded = loader.loadForMainAgentWithDelivery(pluginRoot, userRulesRoot, projectDir, "codex");
			enqueueFeatureInvocations(environment, access.authorizedAccount().orElseThrow(), loaded);
			output.write(json.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of("hookEventName", eventName,
				"additionalContext", CodexLazyRuleRoutes.hookContext(loaded.context(), pluginRoot)))));
			output.write('\n');
			if (decision == WorkOsAccountAccess.Decision.ONLINE)
			{
				try
				{
					flushPendingFeatures(environment);
				}
				catch (IOException e)
				{
					error.println("CAT " + eventName + ": Pending feature invocations remain queued: " +
						e.getMessage());
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
					error.println("CAT " + eventName + ": Pending feature invocations remain queued because delivery " +
						"was interrupted");
				}
				if (eventName.equals("SessionStart"))
				{
					try
					{
						flushPendingUsage(environment);
					}
					catch (IOException e)
					{
						error.println("CAT SessionStart: Pending usage remains queued: " + e.getMessage());
					}
					catch (InterruptedException _)
					{
						Thread.currentThread().interrupt();
						error.println("CAT SessionStart: Pending usage remains queued because delivery was interrupted");
					}
				}
			}
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			writeFailure(scope.json(), eventName, output, error, e);
		}
	}

	catch (IOException e)
	{
		writeScopeFailure(eventName, output, error, e);
	}
}

	/**
	 * Retains each packaged rule whose content this hook is about to supply.
	 *
	 * @param environment hook environment containing the private Codex data directory
	 * @param account identity returned by the successful account check
	 * @param loaded rule context and delivered packaged feature IDs
	 * @throws IOException if an invocation cannot be retained
	 */
	private static void enqueueFeatureInvocations(Map<String, String> environment,
		WorkOsAccountAccess.AuthorizedAccount account, ProjectRuleLoader.LoadedRules loaded) throws IOException
	{
		FeatureInvocationOutbox outbox = new FeatureInvocationOutbox(CodexDataDirectory.auth(environment).
			getParent().resolve("feature-outbox"));
		for (String featureId: loaded.deliveredPluginRuleIds())
			outbox.enqueue(account.principalId(), account.accountId(), featureId);
	}

	/**
	 * Retains one packaged rule delivery under the identity checked before content is supplied.
	 *
	 * @param environment Codex environment containing its private data directory
	 * @param account checked login and selected account
	 * @param featureId packaged rule ID relative to {@code rules/}
	 * @throws IOException if the event cannot be retained
	 */
	static void enqueueFeatureInvocation(Map<String, String> environment,
		WorkOsAccountAccess.AuthorizedAccount account, String featureId) throws IOException
	{
		FeatureInvocationOutbox outbox = new FeatureInvocationOutbox(CodexDataDirectory.auth(environment).
			getParent().resolve("feature-outbox"));
		outbox.enqueue(account.principalId(), account.accountId(), featureId);
	}

	/**
	 * Retries retained feature invocations after the account server confirms this login.
	 *
	 * @param environment hook environment containing the private Codex data directory
	 * @throws IOException if login, outbox, or server confirmation fails
	 * @throws InterruptedException if provider or server delivery is interrupted
	 */
	static void flushPendingFeatures(Map<String, String> environment) throws IOException, InterruptedException
	{
		Path authDirectory = CodexDataDirectory.auth(environment);
		try (HttpClient http = HttpClient.newHttpClient())
		{
			WorkOsFeatureInvocationDelivery delivery = new WorkOsFeatureInvocationDelivery(http,
				WorkOsDeviceAuthClient.API_BASE, new WorkOsSessionStore(authDirectory),
				new FeatureInvocationOutbox(authDirectory.getParent().resolve("feature-outbox")));
			delivery.flush();
		}
	}

	/**
	 * Retries usage retained by earlier hook invocations after the account server confirms this login.
	 *
	 * @param environment hook environment containing the private Codex data directory
	 * @throws IOException if saved login, outbox, or server confirmation fails
	 * @throws InterruptedException if provider or server delivery is interrupted
	 */
	private static void flushPendingUsage(Map<String, String> environment) throws IOException, InterruptedException
	{
		Path authDirectory = CodexDataDirectory.auth(environment);
		try (HttpClient http = HttpClient.newHttpClient())
		{
			WorkOsUsageDelivery delivery = new WorkOsUsageDelivery(http, WorkOsDeviceAuthClient.API_BASE,
				new WorkOsSessionStore(authDirectory), new UsageReportOutbox(authDirectory.getParent().resolve("outbox")));
			delivery.flush();
		}
	}

	/**
	 * Checks the saved login and selected account before supplying new rule content.
	 *
	 * @param environment hook process environment containing the Codex data location
	 * @return server-confirmed or temporary offline access, or a reason to withhold rules
	 * @throws IOException if the private login cannot be read
	 * @throws InterruptedException if the server or provider request is interrupted
	 */
	static WorkOsAccountAccess.CheckResult checkAccount(Map<String, String> environment)
		throws IOException, InterruptedException
	{
		Path authDirectory = CodexDataDirectory.auth(environment);
		if (!Files.exists(authDirectory))
			return new WorkOsAccountAccess.CheckResult(WorkOsAccountAccess.Decision.SIGN_IN_REQUIRED, Optional.empty());
		try (HttpClient http = HttpClient.newHttpClient())
		{
			WorkOsAccountAccess access = new WorkOsAccountAccess(http, WorkOsDeviceAuthClient.API_BASE,
				new WorkOsSessionStore(authDirectory), Clock.systemUTC());
			return access.checkForDelivery();
		}
	}

	/**
	 * Explains the action that can restore CAT rule delivery after an account check fails.
	 *
	 * @param access server or local account decision
	 * @param pluginRoot installed CAT directory containing login commands
	 * @return user-facing recovery message
	 */
	static String accessMessage(WorkOsAccountAccess.Decision access, Path pluginRoot)
	{
		return switch (access)
		{
			case SIGN_IN_REQUIRED -> "CAT features are disabled. Sign in with " +
				pluginRoot.resolve("client/bin/login") + ".";
			case ACCOUNT_SELECTION_REQUIRED -> "CAT features are disabled. Select an account with " +
				pluginRoot.resolve("client/bin/account-select") + " <account-id>.";
			case ACCESS_DENIED -> "CAT features are disabled because this login is no longer linked to the selected " +
				"HoldMySpot account. Sign in again or select a linked account.";
			case CONNECTIVITY_REQUIRED -> "CAT features are disabled because the HoldMySpot server cannot be reached " +
				"and the 24-hour offline allowance has expired. Connect to the server to restore access.";
			case VERIFICATION_FAILED -> "CAT features are disabled because account authorization could not be " +
				"verified. Retry after restoring connectivity or sign in again.";
			case ONLINE, OFFLINE -> throw new IllegalArgumentException("Enabled access has no recovery message");
		};
	}

	/**
	 * Returns a native hook response with no rule content and a recovery message.
	 *
	 * @param json invocation-owned JSON mapper
	 * @param eventName native hook event name
	 * @param output native hook output
	 * @param message action needed to restore rule delivery
	 * @throws IOException if the response cannot be written
	 */
	private static void writeDisabled(JsonMapper json, String eventName, OutputStream output, String message)
		throws IOException
	{
		output.write(json.writeValueAsBytes(Map.of("hookSpecificOutput", Map.of("hookEventName", eventName,
			"additionalContext", ""), "systemMessage", message)));
		output.write('\n');
	}

	/**
	 * Reports a hook failure and writes the native failure response through the invocation-owned mapper.
	 *
	 * @param json the invocation-owned JSON mapper
	 * @param eventName the Codex hook event name
	 * @param output the native hook output
	 * @param error diagnostics for the host
	 * @param failure the hook failure
	 */
	private static void writeFailure(JsonMapper json, String eventName, OutputStream output, PrintStream error,
		Exception failure)
	{
		error.println("CAT " + eventName + ": " + failure.getMessage());
		try
		{
			output.write(json.writeValueAsBytes(Map.of("continue", false, "stopReason",
				"CAT could not load its " + eventName + " rules.", "systemMessage", "CAT " + eventName +
					" failed: " + failure.getMessage())));
			output.write('\n');
		}
		catch (IOException responseFailure)
		{
			error.println("CAT " + eventName + ": Unable to return the failure response: " +
				responseFailure.getMessage());
		}
	}

	/**
	 * Reports a failure that prevented hook-scope construction and writes Codex's mandatory stop response.
	 *
	 * @param eventName the Codex hook event name
	 * @param output the native hook output
	 * @param error diagnostics for the host
	 * @param failure the scope-construction failure
	 */
	private static void writeScopeFailure(String eventName, OutputStream output, PrintStream error, IOException failure)
	{
		error.println("CAT " + eventName + ": " + failure.getMessage());
		try
		{
			String response = "{\"continue\":false,\"stopReason\":\"CAT could not load its rules.\"," +
				"\"systemMessage\":\"CAT hook scope failed.\"}\n";
			output.write(response.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException responseFailure)
		{
			error.println("CAT " + eventName + ": Unable to return the failure response: " +
				responseFailure.getMessage());
		}
	}

	/**
	 * Returns the host-provided subagent type without making malformed optional metadata a hook crash.
	 *
	 * @param event the native hook event
	 * @return the subagent type, or blank when it is absent or not textual
	 */
	private static String subagentType(JsonNode event)
	{
		assert event != null;
		JsonNode agentType = event.path("agent_type");
		if (!agentType.isString())
			return "";
		return agentType.stringValue();
	}
}

package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.runner.CodexPluginHooks;
import com.holdmyspot.cat.codex.runner.CodexPluginHooks.Binding;
import com.holdmyspot.cat.codex.runner.CodexPluginHooks.Installation;
import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the native binding of existing hook approvals to frozen trial artifacts.
 */
public final class CodexPluginHooksTest
{
	private static final String SOURCE_ID = "fixture@approved-market";
	private static final String TRIAL_ID = "fixture@fixture";
	private static final String SUFFIX = ":hooks.json:session_start:0:0";
	private static final String APPROVAL = "sha256:" + "a".repeat(64);
	private static final String ARTIFACT = "sha256:" + "b".repeat(64);

	/**
	 * Verifies a matching copy can reuse the stored approval, and that the native post-transfer check uses the full
	 * hash and enabled state. A same-name artifact can have the same declaration digest and a different artifact digest.
	 *
	 * @throws IOException if the fixture cannot be written or the expected approval is refused
	 */
	@Test
	public void matchesOnlyEnabledExactArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("cat-hook-binding-")).toRealPath();
			Path original = Files.createDirectory(folder.resolve("original"));
			Path trial = Files.createDirectory(folder.resolve("trial"));
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode before = hook(json, SOURCE_ID, Files.createFile(original.resolve("hooks.json")));
			ObjectNode after = hook(json, TRIAL_ID, Files.createFile(trial.resolve("hooks.json")));
			Map<String, JsonNode> sourceHooks = Map.of(SOURCE_ID + SUFFIX, before);
			Map<String, JsonNode> trialHooks = Map.of(TRIAL_ID + SUFFIX, after);
			Installation sourceInstallation = new Installation(SOURCE_ID, List.of(original));
			Installation trialInstallation = new Installation(TRIAL_ID, List.of(trial));
			Map<String, String> approvals = Map.of(SOURCE_ID + SUFFIX, APPROVAL);
			List<Binding> bindings = CodexPluginHooks.bind(sourceHooks, trialHooks, sourceInstallation,
				trialInstallation, approvals);
			requireThat(bindings, "bindings").
				isEqualTo(List.of(new Binding(SOURCE_ID + SUFFIX, TRIAL_ID + SUFFIX, APPROVAL + "/" + ARTIFACT)));
			Binding binding = bindings.getFirst();
			Map<String, String> bound = Map.of(binding.trialKey(), binding.hash());
			CodexPluginHooks.requireTrusted(trialHooks, bound);

			after.put("currentHash", APPROVAL + "/sha256:" + "c".repeat(64));
			requireThat(CodexPluginHooks.bind(sourceHooks, trialHooks, sourceInstallation, trialInstallation, approvals),
				"differentArtifact").isEmpty();
			expectThrows(IOException.class, () -> CodexPluginHooks.requireTrusted(trialHooks, bound));

			after.put("currentHash", APPROVAL + "/" + ARTIFACT).put("enabled", false);
			requireThat(CodexPluginHooks.bind(sourceHooks, trialHooks, sourceInstallation, trialInstallation, approvals),
				"disabledDestination").isEmpty();
			expectThrows(IOException.class, () -> CodexPluginHooks.requireTrusted(trialHooks, bound));

			after.put("enabled", true);
			before.put("pluginId", "fixture@other-market");
			requireThat(CodexPluginHooks.bind(sourceHooks, trialHooks, sourceInstallation, trialInstallation, approvals),
				"differentMarketplace").isEmpty();
		}
	}

	/**
	 * Resumes with the original artifact digest only when the retained declaration approvals match the launch.
	 *
	 * @throws IOException if the fixture cannot be written or the matching checkpoint cannot be read
	 */
	@Test
	public void resumesOnlyWithOriginalBindings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("cat-retained-hooks-")).resolve("bindings.json");
			JsonMapper json = JsonMapper.builder().build();
			String key = TRIAL_ID + SUFFIX;
			Map<String, String> configured = Map.of(key, APPROVAL);
			Map<String, String> original = Map.of(key, APPROVAL + "/" + ARTIFACT);
			requireThat(CodexPluginHooks.resumeBindings(json, file, Map.of(), false), "legacyWithoutApprovals").
				isEmpty();
			expectThrows(IOException.class, () -> CodexPluginHooks.resumeBindings(json, file, configured, false));

			CodexPluginHooks.saveBindings(json, file, original);
			requireThat(CodexPluginHooks.resumeBindings(json, file, configured, false), "retainedBindings").
				isEqualTo(original);
			Map<String, String> withCat = Map.of(key, APPROVAL, "cat@cat:hooks/hooks.json:session_start:0:0", APPROVAL);
			requireThat(CodexPluginHooks.resumeBindings(json, file, withCat, true), "managedCatApproval").
				isEqualTo(original);
			expectThrows(IOException.class, () -> CodexPluginHooks.resumeBindings(json, file, withCat, false));
			expectThrows(IOException.class, () -> CodexPluginHooks.resumeBindings(json, file, Map.of(), false));
			Map<String, String> changed = Map.of(key, ARTIFACT);
			expectThrows(IOException.class, () -> CodexPluginHooks.resumeBindings(json, file, changed, false));
		}
	}

	/**
	 * Creates a native hook record for one synthetic installed plugin.
	 *
	 * @param json the response mapper
	 * @param id the plugin and marketplace identity
	 * @param path the declaration's physical file
	 * @return the synthetic native record
	 */
	private static ObjectNode hook(JsonMapper json, String id, Path path)
	{
		return json.createObjectNode().put("key", id + SUFFIX).put("pluginId", id).put("source", "plugin").
			put("sourcePath", path.toString()).put("enabled", true).put("trustStatus", "trusted").
			put("currentHash", APPROVAL + "/" + ARTIFACT);
	}
}

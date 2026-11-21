package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HookComparisonLaunch;
import com.holdmyspot.cat.common.runner.HookComparisonRequest;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the comparison inputs crossing the owner request boundary.
 */
public final class HookComparisonRequestTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Confirms that the private launch preserves the broker's selected executables and complete owner policy.
	 *
	 * @throws IOException if the launch cannot be written or read
	 */
	@Test
	public void preservesBrokerLaunchPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("hook-launch-")).toRealPath();
			Path project = root.resolve("project");
			Path denied = project.resolve("private");
			ParentPolicy policy = new ParentPolicy(true, List.of(project), List.of(denied), List.of(denied),
				List.of(denied.resolve("public")), HostAccess.listed(Set.of("api.example.com")), true,
				List.of(root.resolve("trusted")));
			HookComparisonLaunch launch = new HookComparisonLaunch(project, root.resolve("captured"),
				root.resolve("codex"), root.resolve("bin/codex"), root.resolve("bin/bwrap"), policy);
			ByteArrayOutputStream pipe = new ByteArrayOutputStream();
			launch.write(JSON, pipe);

			HookComparisonLaunch received = HookComparisonLaunch.read(JSON, new ByteArrayInputStream(pipe.toByteArray()));

			requireThat(received, "received").isEqualTo(launch);
		}
	}

	/**
	 * Confirms that the archive carries both workspace copies and retains plugin provenance by position.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void archivesBothWorkspacesAndPlugins() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("hook-request-")).toRealPath();
			Path staged = Files.createDirectories(root.resolve("workspace"));
			Path built = Files.createDirectory(root.resolve("built"));
			Path installed = Files.createDirectory(root.resolve("installed"));
			Files.writeString(built.resolve("content"), "built plugin");
			Files.writeString(installed.resolve("content"), "installed plugin");
			HookComparisonRequest sent = new HookComparisonRequest(staged, List.of(
				new TrialPluginInput(built, Optional.empty()),
				new TrialPluginInput(installed, Optional.of("docs@market"))));
			Files.writeString(Files.createDirectories(sent.production()).resolve("notes"), "conventions");
			Files.createDirectories(sent.trial());
			Path requests = Files.createDirectory(root.resolve("requests"));
			String id = sent.publish(JSON, requests);
			Path extracted = Files.createDirectory(root.resolve("captured"));
			RequestArchive.unpack(requests.resolve(id + ".tar"), extracted, RequestLimits.UNBOUNDED);

			HookComparisonRequest received = HookComparisonRequest.read(JSON, extracted);

			requireThat(Files.readString(received.production().resolve("notes")), "production").
				isEqualTo("conventions");
			requireThat(Files.notExists(received.trial().resolve("notes")), "trialHasNoNotes").isTrue();
			requireThat(received.plugins().stream().map(TrialPluginInput::installedId).toList(), "provenance").
				isEqualTo(List.of(Optional.empty(), Optional.of("docs@market")));
			requireThat(Files.readString(received.plugins().getLast().path().resolve("content")), "plugin").
				isEqualTo("installed plugin");
		}
	}

	/**
	 * Confirms that a caller cannot choose a command, a host workspace path or an isolated native home.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesCallerSuppliedAuthority() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("hook-request-")).toRealPath();
			HookComparisonRequest captured = new HookComparisonRequest(root.resolve("project"), List.of());
			Files.createDirectories(captured.production());
			Files.createDirectories(captured.trial());
			Path manifest = root.resolve("request.json");
			ObjectNode value = JSON.createObjectNode().put("format_version", 1).put("project", "project");
			Files.writeString(manifest, value.put("command", "unexpected").toString());
			requireThat(expectThrows(IOException.class, () -> HookComparisonRequest.read(JSON, root)).getMessage(),
				"command").contains("may not choose command");

			value.remove("command");
			Files.writeString(manifest, value.put("project", root.toString()).toString());
			requireThat(expectThrows(IOException.class, () -> HookComparisonRequest.read(JSON, root)).getMessage(),
				"hostPath").contains("must name the archive entry");

			Files.writeString(manifest, value.put("project", "project").toString());
			Files.createDirectory(captured.trial().getParent().resolve("home"));
			requireThat(expectThrows(IOException.class, () -> HookComparisonRequest.read(JSON, root)).getMessage(),
				"home").contains("Unexpected hook comparison workspace entry: home");
		}
	}
}

package com.holdmyspot.cat.codex.runner;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.RealPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Queries native hook state without creating a thread or exposing the network.
 */
final class CodexHookInspection
{
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	/**
	 * Reads protected owner hook state with writable native state in scratch space.
	 *
	 * @param home the native installation to inspect
	 * @param project the project that determines which hooks Codex lists
	 * @param scratchParent an owned directory that can receive the inspection state
	 * @param bubblewrap a resolved native sandbox launcher
	 * @param executable the native Codex executable
	 * @param policy the authenticated owner's filesystem policy
	 * @param options the feature options used in the inspected run
	 * @param scope services for the native process
	 * @return the hook records by their exact native keys
	 * @throws IOException if the native hook state cannot be inspected in the sandbox
	 */
	static Map<String, JsonNode> read(Path home, Path project, Path scratchParent, Path bubblewrap,
		Path executable, ParentPolicy policy, List<String> options, HarnessRunnerScope scope) throws IOException
	{
		return read(home, project, scratchParent, bubblewrap, executable, policy, options, scope, false);
	}

	/**
	 * Reads hook state in a disposable trial home where Codex can initialize its installation identity.
	 *
	 * @param home the isolated trial installation
	 * @param project the project that determines which hooks Codex lists
	 * @param scratchParent an owned directory that can receive the inspection state
	 * @param bubblewrap a resolved native sandbox launcher
	 * @param executable the native Codex executable
	 * @param policy the authenticated owner's filesystem policy
	 * @param options the feature options used in the inspected run
	 * @param scope services for the native process
	 * @return the hook records by their exact native keys
	 * @throws IOException if the native hook state cannot be inspected in the sandbox
	 */
	static Map<String, JsonNode> readTrial(Path home, Path project, Path scratchParent, Path bubblewrap,
		Path executable, ParentPolicy policy, List<String> options, HarnessRunnerScope scope) throws IOException
	{
		return read(home, project, scratchParent, bubblewrap, executable, policy, options, scope, true);
	}

	/**
	 * Reads native records with write access to scratch and, when isolated, the trial home.
	 *
	 * @param home the native installation to inspect
	 * @param project the project that determines which hooks Codex lists
	 * @param scratchParent an owned directory that can receive the inspection state
	 * @param bubblewrap a resolved native sandbox launcher
	 * @param executable the native Codex executable
	 * @param policy the authenticated owner's filesystem policy
	 * @param options the feature options used in the inspected run
	 * @param scope services for the native process
	 * @param trialHome whether the home is isolated and writable
	 * @return the hook records by their exact native keys
	 * @throws IOException if the native hook state cannot be inspected in the sandbox
	 */
	private static Map<String, JsonNode> read(Path home, Path project, Path scratchParent, Path bubblewrap,
		Path executable, ParentPolicy policy, List<String> options, HarnessRunnerScope scope, boolean trialHome)
		throws IOException
	{
		Path nativeHome = home.toRealPath();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path scratch = cleanup.add(Files.createTempDirectory(scratchParent, "hook-inspection-")).toRealPath();
			Path temporary = Files.createDirectory(scratch.resolve("tmp"));
			Path db = Files.createDirectory(scratch.resolve("state"));
			Path logs = Files.createDirectory(scratch.resolve("logs"));
			List<String> command = readOnlyView(bubblewrap);
			if (trialHome)
				command.addAll(List.of("--bind", nativeHome.toString(), nativeHome.toString()));
			else
				command.addAll(ownerOverlays(nativeHome, scratch, policy));
			command.addAll(List.of("--bind", scratch.toString(), scratch.toString()));
			applyReadDenials(command, policy, scratch);
			command.addAll(List.of("--clearenv", "--setenv", "PATH", "/usr/bin:/bin",
				"--setenv", "HOME", scratch.toString(),
				"--setenv", "TMPDIR", temporary.toString(), "--setenv", "CODEX_HOME", nativeHome.toString(),
				"--chdir", project.toString(), executable.toString(), "app-server", "--stdio",
				"-c", "sqlite_home=" + scope.json().writeValueAsString(db.toString()),
				"-c", "log_dir=" + scope.json().writeValueAsString(logs.toString())));
			command.addAll(options);
			Process raw = HarnessProcessStarter.start(command, project, Map.of(), scope.executableFiles());
			try (CodexProcess process = new CodexProcess(raw, scope, false, null))
			{
				JsonNode result = await(process, raw, project);
				return CodexPluginHooks.records(result, project);
			}
		}
	}

	/**
	 * Gives Codex scratch overlays for the writable files it expects in an initialized owner home.
	 *
	 * @param home the owner home that remains read-only
	 * @param scratch the writable inspection directory
	 * @param policy the authenticated owner's filesystem policy
	 * @return the sandbox mount arguments
	 * @throws IOException if the owner installation or scratch overlays are unavailable
	 */
	private static List<String> ownerOverlays(Path home, Path scratch, ParentPolicy policy) throws IOException
	{
		Path installationId = home.resolve("installation_id");
		Path staging = home.resolve(".tmp");
		if (!Files.isRegularFile(installationId, NOFOLLOW_LINKS) || !Files.isDirectory(staging))
			throw new IOException("Codex has no initialized installation to inspect at " + home);
		if (!policy.mayRead(installationId))
			throw new IOException("The owner cannot read the Codex installation ID: " + installationId);
		Path idCopy = scratch.resolve("installation_id");
		try (InputStream id = Files.newInputStream(installationId, NOFOLLOW_LINKS))
		{
			Files.copy(id, idCopy);
		}
		Path stagingCopy = Files.createDirectory(scratch.resolve("staging"));
		return List.of("--bind", idCopy.toString(), installationId.toString(),
			"--bind", stagingCopy.toString(), staging.toString());
	}

	/**
	 * Returns the common isolated filesystem and network view for inspection and installation.
	 *
	 * @param bubblewrap the protected native sandbox launcher
	 * @return a mutable command prefix to which callers add their writable overlays
	 */
	static List<String> readOnlyView(Path bubblewrap)
	{
		return new ArrayList<>(List.of(bubblewrap.toString(), "--die-with-parent", "--unshare-net", "--unshare-pid",
			"--ro-bind", "/", "/", "--proc", "/proc", "--dev", "/dev"));
	}

	/**
	 * Checks both native launch executables before either can run.
	 *
	 * @param policy the authenticated owner's filesystem policy
	 * @param bubblewrap the protected native sandbox launcher
	 * @param executable the native Codex executable
	 * @throws IOException if an executable is not readable within the owner's policy
	 */
	static void requireReadableExecutables(ParentPolicy policy, Path bubblewrap, Path executable) throws IOException
	{
		for (Path path : List.of(bubblewrap, executable))
		{
			if (!policy.mayRead(path))
				throw new IOException("The owner cannot read a Codex hook inspection executable: " + path);
		}
	}

	/**
	 * Masks owner-denied reads after the writable overlays are mounted. An exception covering a denial leaves
	 * nothing to mask. Partial exceptions or a path that cannot be masked make inspection unavailable.
	 *
	 * @param command the sandbox command after its writable overlays
	 * @param policy the authenticated owner's filesystem policy
	 * @param scratch where private mask files can be created
	 * @throws IOException if the view cannot enforce the read policy
	 */
	static void applyReadDenials(List<String> command, ParentPolicy policy, Path scratch) throws IOException
	{
		if (!policy.sandboxed())
			return;
		List<Path> exceptions = RealPaths.resolveAll(policy.readReallowances());
		List<Path> masked = new ArrayList<>();
		List<Path> denied = RealPaths.resolveAll(policy.readDenials()).stream().
			sorted(Comparator.comparingInt(Path::getNameCount)).toList();
		for (Path path : denied)
		{
			if (RealPaths.isInsideAny(path, exceptions) || RealPaths.isInsideAny(path, masked))
				continue;
			if (path.getParent() == null || path.startsWith("/proc") || path.startsWith("/dev") ||
				exceptions.stream().anyMatch(exception -> exception.startsWith(path)))
			{
				throw new IOException("Codex cannot represent the owner's read restriction during hook inspection: " +
					path);
			}
			mask(command, path, scratch);
			masked.add(path);
		}
	}

	/**
	 * Replaces the denied filesystem object with an empty read-only object.
	 *
	 * @param command the sandbox command
	 * @param denied the canonical denied path
	 * @param scratch where a private empty file can be created
	 * @throws IOException if the denied path cannot be masked
	 */
	private static void mask(List<String> command, Path denied, Path scratch) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(denied, BasicFileAttributes.class);
		}
		catch (IOException e)
		{
			throw new IOException("Codex cannot mask the owner's denied read during hook inspection: " + denied, e);
		}
		if (attributes.isDirectory())
			command.addAll(List.of("--tmpfs", denied.toString(), "--remount-ro", denied.toString()));
		else if (attributes.isRegularFile())
		{
			Path empty = Files.createTempFile(scratch, "denied-read-", ".empty",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")));
			command.addAll(List.of("--ro-bind", empty.toString(), denied.toString()));
		}
		else
			throw new IOException("Codex cannot mask the owner's denied read during hook inspection: " + denied);
	}

	/**
	 * Bounds the native handshake and hook inspection by the same deadline.
	 *
	 * @param process the app-server client
	 * @param raw the underlying process, which must stop at the deadline
	 * @param project the directory to inspect
	 * @return the native response
	 * @throws IOException if the native call fails, times out or is interrupted
	 */
	private static JsonNode await(CodexProcess process, Process raw, Path project) throws IOException
	{
		FutureTask<JsonNode> call = new FutureTask<>(() -> process.inspectHooks(project));
		Thread.startVirtualThread(call);
		try
		{
			return call.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e)
		{
			raw.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while inspecting Codex hooks", e);
		}
		catch (TimeoutException e)
		{
			raw.destroyForcibly();
			throw new IOException("Codex did not return hook state within the inspection deadline", e);
		}
		catch (ExecutionException e)
		{
			raw.destroyForcibly();
			throw new IOException("Codex hook inspection failed", e.getCause());
		}
		finally
		{
			call.cancel(true);
		}
	}

	/**
	 * Prevents instantiation of this utility class.
	 */
	private CodexHookInspection()
	{
	}
}

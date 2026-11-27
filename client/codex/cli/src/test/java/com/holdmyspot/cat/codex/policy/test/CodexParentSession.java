package com.holdmyspot.cat.codex.policy.test;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.codex.policy.CodexHookSandbox;
import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The files and processes of a Codex session whose hook asks {@link CodexPolicyReader} for the session's policy.
 * <p>
 * The session runs in {@code project}, a Git checkout, with its home in {@code home} and Codex's home in
 * {@code home/.codex}. Its temporary folder, {@code tmp}, the folder {@code other} and the folder {@code slash-tmp},
 * which stands for the machine's {@code /tmp}, exist beside them.
 */
public final class CodexParentSession
{
	/**
	 * The session's identifier.
	 */
	public static final String SESSION_ID = "01a1a8eb-3d83-7d73-b243-c636c2041ee1";
	private final Path root;
	private final Path home;
	private final Path codexHome;
	private final Path project;
	private CodexSystemFolder systemFolder;
	private Path system;
	private Path slashTmp;
	private Optional<Path> transcript = Optional.empty();

	/**
	 * Creates the session's folders.
	 *
	 * @param folder an empty folder
	 * @throws IOException if the folders cannot be created
	 */
	public CodexParentSession(Path folder) throws IOException
	{
		this.root = folder.toRealPath();
		this.home = Files.createDirectories(root.resolve("home"));
		this.codexHome = Files.createDirectories(home.resolve(".codex"));
		this.project = Files.createDirectories(root.resolve("project"));
		Files.createDirectories(project.resolve(".git"));
		this.system = Files.createDirectories(root.resolve("etc-codex"));
		this.systemFolder = CodexSystemFolder.at(system);
		Files.createDirectories(root.resolve("tmp"));
		Files.createDirectories(root.resolve("other"));
		this.slashTmp = Files.createDirectories(root.resolve("slash-tmp"));
	}

	/**
	 * Returns a folder of the session's files.
	 *
	 * @param name the folder's name below the session's files, such as {@code other/extra}
	 * @return the folder, which this method creates
	 * @throws IOException if the folder cannot be created
	 */
	public Path folder(String name) throws IOException
	{
		return Files.createDirectories(root.resolve(name));
	}

	/**
	 * Returns the session's working directory.
	 *
	 * @return the project folder
	 */
	public Path project()
	{
		return project;
	}

	/**
	 * Returns Codex's home.
	 *
	 * @return the folder
	 */
	public Path codexHome()
	{
		return codexHome;
	}

	/**
	 * Writes a file of the session's files.
	 *
	 * @param name the file's name below the session's files, such as {@code home/.codex/config.toml}
	 * @param text the file's content
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public Path write(String name, String text) throws IOException
	{
		Path file = root.resolve(name);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, text);
	}

	/**
	 * Writes a file of the machine-wide Codex folder.
	 *
	 * @param name the file's name, such as {@code requirements.toml}
	 * @param text the file's content
	 * @throws IOException if the file cannot be written
	 */
	public void writeSystem(String name, String text) throws IOException
	{
		Files.writeString(system.resolve(name), text);
	}

	/**
	 * Makes the session's machine a Windows machine, whose Codex reads its machine-wide files from the program data
	 * folder that Windows names, which here is a folder of the session's files.
	 *
	 * @param registry reads the registry of the machine
	 * @throws IOException if the folder cannot be created
	 */
	public void onWindows(CodexSystemFolder.RegistryLookup registry) throws IOException
	{
		Path programData = root.resolve("ProgramData");
		systemFolder = CodexSystemFolder.of(Platform.WINDOWS_X64, () -> programData, registry);
		system = Files.createDirectories(systemFolder.folder());
	}

	/**
	 * Makes the session's machine a Windows machine on which Windows names no program data folder, so that the
	 * machine-wide files are in the folder that Codex falls back to, which is not one of the session's files.
	 *
	 * @param failure why Windows names no folder
	 */
	public void onWindowsWithoutProgramData(String failure)
	{
		systemFolder = CodexSystemFolder.of(Platform.WINDOWS_X64, () ->
		{
			throw new IOException(failure);
		}, (key, name) -> Optional.empty());
	}

	/**
	 * Names the folder that stands for the machine's {@code /tmp}.
	 *
	 * @param folder the folder
	 */
	public void slashTmp(Path folder)
	{
		this.slashTmp = folder;
	}

	/**
	 * Names the rollout file that the hook's input names.
	 *
	 * @param file the rollout file
	 */
	public void transcript(Path file)
	{
		this.transcript = Optional.of(file);
	}

	/**
	 * Returns a path as a TOML literal string.
	 *
	 * @param path a path
	 * @return the literal
	 */
	public static String toml(Path path)
	{
		return "'" + path + "'";
	}

	/**
	 * Reads the policy of the session's main thread when Codex runs with some arguments.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy read(String... arguments) throws IOException, PolicyRefusal
	{
		return readThread(Optional.empty(), arguments);
	}

	/**
	 * Reads the policy of one of the session's threads when Codex runs with some arguments.
	 *
	 * @param threadId  the thread that asks, or empty for the main thread
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy readThread(Optional<String> threadId, String... arguments) throws IOException, PolicyRefusal
	{
		return reader().read(session(arguments), threadId);
	}

	/**
	 * Reads the policy of one of the session's threads for a command that the thread runs, when Codex runs with some
	 * arguments, as a runner launcher that the thread runs reads it.
	 *
	 * @param threadId  the thread that runs the command, or empty for the main thread
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy readForCommand(Optional<String> threadId, String... arguments) throws IOException,
		PolicyRefusal
	{
		return reader().readForCommand(session(arguments), threadId);
	}

	/**
	 * Reads the policy when a Codex sandbox helper precedes the outer Codex process.
	 *
	 * @param arguments the arguments of the outer Codex process after its executable
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy readForCommandWithSandboxHelper(String... arguments) throws IOException, PolicyRefusal
	{
		return reader().readForCommand(sessionWithSandboxHelper(arguments), Optional.empty());
	}

	/**
	 * Returns what a hook of the session needs to read the session's sandbox, when Codex runs with some arguments.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the sandbox source
	 */
	public CodexHookSandbox sandbox(String... arguments)
	{
		return new CodexHookSandbox(session(arguments).ancestors(), reader(), home);
	}

	/**
	 * Returns what a hook of the session needs to read the session's sandbox, when no ancestor process is Codex.
	 *
	 * @return the sandbox source
	 */
	public CodexHookSandbox sandboxWithoutCodex()
	{
		List<ProcessDescription> ancestors = List.of(
			new ProcessDescription(101, Optional.empty(), Optional.of("/bin/sh"), Optional.empty(),
				Optional.of(List.of("-c", "hook"))));
		return new CodexHookSandbox(ancestors, reader(), home);
	}

	/**
	 * Reads where the session's sandbox may let commands write, with the overestimating reading, when Codex runs with
	 * some arguments.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the writes
	 */
	public SandboxWrites readWideWrites(String... arguments)
	{
		return reader().readWideWrites(session(arguments), Optional.empty());
	}

	/**
	 * Returns the home folder of the session's user.
	 *
	 * @return the folder
	 */
	public Path home()
	{
		return home;
	}

	/**
	 * Returns the session when Codex runs with some arguments.
	 *
	 * @param arguments the arguments of the Codex process after its executable
	 * @return the session
	 */
	private CodexSession session(String... arguments)
	{
		List<ProcessDescription> ancestors = List.of(
			new ProcessDescription(101, Optional.empty(), Optional.of("/bin/sh"),
				Optional.empty(), Optional.of(List.of("-c", "hook"))),
			new ProcessDescription(102, Optional.empty(), Optional.of("/opt/codex/vendor/bin/codex"),
				Optional.empty(), Optional.of(List.of(arguments))),
			new ProcessDescription(103, Optional.empty(), Optional.of("/usr/bin/node"),
				Optional.empty(), Optional.of(List.of("/opt/codex/bin/codex.js"))));
		Map<String, String> environment = Map.of("HOME", home.toString(), "TMPDIR", root.resolve("tmp").toString());
		return new CodexSession(ancestors, environment, home, project, transcript, SESSION_ID);
	}

	/**
	 * Returns a session whose nearest Codex executable uses the sandbox helper as argv[0], followed by the outer Codex
	 * process that owns the command line.
	 *
	 * @param arguments the arguments of the outer Codex process after its executable
	 * @return the session
	 */
	private CodexSession sessionWithSandboxHelper(String... arguments)
	{
		List<ProcessDescription> ancestors = List.of(
			new ProcessDescription(101, Optional.empty(), Optional.of("/bin/sh"), Optional.empty(),
				Optional.of(List.of("-c", "hook"))),
			new ProcessDescription(102, Optional.empty(), Optional.of("/opt/codex/vendor/bin/codex"),
				Optional.of("/usr/libexec/codex-linux-sandbox"), Optional.of(List.of("--sandbox-helper-option"))),
			new ProcessDescription(103, Optional.empty(), Optional.of("/opt/codex/vendor/bin/codex"),
				Optional.of("codex"), Optional.of(List.of(arguments))),
			new ProcessDescription(104, Optional.empty(), Optional.of("/usr/bin/node"), Optional.empty(),
				Optional.of(List.of("/opt/codex/bin/codex.js"))));
		Map<String, String> environment = Map.of("HOME", home.toString(), "TMPDIR", root.resolve("tmp").toString());
		return new CodexSession(ancestors, environment, home, project, transcript, SESSION_ID);
	}

	/**
	 * Returns the reader of the session's policy, which reads the machine-wide files of this session's machine.
	 *
	 * @return the reader
	 */
	private CodexPolicyReader reader()
	{
		return new CodexPolicyReader(new CodexSystemFiles(systemFolder, List.of(root.resolve("managed.plist")), slashTmp));
	}
}

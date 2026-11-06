package com.holdmyspot.cat.codex.policy.test;

import com.holdmyspot.cat.codex.policy.CodexParentPolicy;
import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexRollout;
import com.holdmyspot.cat.codex.policy.CodexSession;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The files of a Codex session whose command asks {@link CodexPolicyReader} for the policy of the thread that runs it.
 * <p>
 * The session runs in {@code project}, a Git checkout, with its home in {@code home} and Codex's home in
 * {@code home/.codex}. Its temporary folder, {@code tmp}, the folder {@code other} and the folder {@code slash-tmp},
 * which stands for the machine's {@code /tmp}, exist beside them.
 */
public final class CodexParentSession
{
	/**
	 * The identifier of the session's main thread.
	 */
	public static final String SESSION_ID = "01a1a8eb-3d83-7d73-b243-c636c2041ee1";
	private final Path root;
	private final Path home;
	private final Path codexHome;
	private final Path project;
	private final Path system;
	private Path slashTmp;
	private Optional<Path> rollout = Optional.empty();

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
	 * Returns the user's home folder.
	 *
	 * @return the folder
	 */
	public Path home()
	{
		return home;
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
	 * Names the folder that stands for the machine's {@code /tmp}.
	 *
	 * @param folder the folder
	 */
	public void slashTmp(Path folder)
	{
		this.slashTmp = folder;
	}

	/**
	 * Names the rollout of the main thread, in place of one that records no turn yet.
	 *
	 * @param file the rollout file
	 */
	public void rollout(Path file)
	{
		this.rollout = Optional.of(file);
	}

	/**
	 * Writes a thread's rollout in Codex's home, as Codex 0.160.0 names and starts it.
	 *
	 * @param threadId the thread
	 * @param records  the records that follow the rollout's {@code session_meta} record, each with its line
	 *                 terminator
	 * @return the rollout
	 * @throws IOException if the file cannot be written
	 */
	public Path writeRollout(String threadId, String records) throws IOException
	{
		String meta = """
			{"timestamp":"2026-11-05T12:00:00.000Z","type":"session_meta","payload":{"session_id":"%s","id":"%s",\
			"cwd":%s,"originator":"codex_exec","cli_version":"0.160.0","source":"exec"}}
			""".formatted(SESSION_ID, threadId, json(project));
		Path folder = Files.createDirectories(codexHome.resolve("sessions/2026/11/05"));
		return Files.writeString(folder.resolve("rollout-2026-11-05T12-00-00-" + threadId + ".jsonl"),
			meta + records);
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
	 * Returns a path as a JSON string.
	 *
	 * @param path a path
	 * @return the string
	 */
	public static String json(Path path)
	{
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}

	/**
	 * Returns the environment of a command that one of the session's threads runs.
	 *
	 * @param threadId the thread
	 * @return the environment
	 */
	public Map<String, String> environment(String threadId)
	{
		Map<String, String> environment = new HashMap<>(Map.of("HOME", home.toString(), "TMPDIR",
			root.resolve("tmp").toString()));
		environment.put(CodexParentPolicy.THREAD_VARIABLE, threadId);
		return environment;
	}

	/**
	 * Returns the reader of the session's policies.
	 *
	 * @return the reader
	 */
	public CodexPolicyReader reader()
	{
		return new CodexPolicyReader(new CodexSystemFiles(system, List.of(root.resolve("managed.plist")),
			slashTmp));
	}

	/**
	 * Reads the policy of the session's main thread.
	 *
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy read() throws IOException, PolicyRefusal
	{
		Path file;
		if (rollout.isPresent())
			file = rollout.get();
		else
			file = writeRollout(SESSION_ID, "");
		return reader().read(new CodexSession(environment(SESSION_ID), home, CodexRollout.cwd(file), file));
	}

	/**
	 * Reads the policy of the thread that runs a command, as a runner that the command runs finds it.
	 *
	 * @param threadId the thread, as the command's environment names it
	 * @return the policy
	 * @throws IOException   if a file cannot be read
	 * @throws PolicyRefusal if the reader refuses
	 */
	public ParentPolicy readThread(String threadId) throws IOException, PolicyRefusal
	{
		return new CodexParentPolicy(reader(), environment(threadId), home, project).read();
	}
}

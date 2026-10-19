package com.holdmyspot.cat.common.optimizer.proposal.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.proposal.ContractAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.proposal.ContractContext;
import com.holdmyspot.cat.common.optimizer.proposal.ContractTools;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.optimizer.proposal.JudgeTemplate;
import com.holdmyspot.cat.common.optimizer.proposal.OutputFile;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.proposal.RegressionScenarios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the project of a contract author session in which a test runs the contract tools: the files that CAT writes
 * into the project when the session starts, then the proposal file and the draft directory that the session writes.
 */
public final class ContractToolSessions
{
	/**
	 * The directory that holds the prompt system, which does not exist, so that each file that CAT writes into the
	 * session's project has its default name.
	 */
	public static final Path PROMPT_SYSTEM = Path.of("/staged/incumbent").toAbsolutePath();
	/**
	 * The target prompt file of the campaign.
	 */
	private static final Path TARGET = PROMPT_SYSTEM.resolve("CLAUDE.md");
	/**
	 * The launcher that provides the contract tools.
	 */
	public static final Path LAUNCHER = Path.of("/opt/cat/bin/prompt-optimizer").toAbsolutePath();
	/**
	 * The profile that the contract author's sessions run with.
	 */
	private static final TrialProfile ROLE = new TrialProfile("role-model", "xhigh");

	/**
	 * Prevents instantiation.
	 */
	private ContractToolSessions()
	{
	}

	/**
	 * Returns a contract author whose target is {@code CLAUDE.md} and whose campaign has no recorded prompt test.
	 *
	 * @param json            the JSON mapper
	 * @param toolOutputBytes returns the tool-output limit of each profile, or empty if it is not known
	 * @return the contract author
	 */
	public static ContractAuthorProtocol contractAuthor(JsonMapper json,
		Function<TrialProfile, OptionalLong> toolOutputBytes)
	{
		return new ContractAuthorProtocol(json, new ProposalSchema(json), new JudgeTemplate(json, ROLE),
			new RegressionScenarios(PROMPT_SYSTEM, List.of()), TARGET, null, true, List.of(), LAUNCHER,
			toolOutputBytes);
	}

	/**
	 * Returns the valid contract author output {@code derivation.json}, with one change.
	 *
	 * @param json   the JSON mapper
	 * @param change edits the output
	 * @return the output as JSON text
	 */
	public static String derivation(JsonMapper json, Consumer<ObjectNode> change)
	{
		try (InputStream in = ContractToolSessions.class.getResourceAsStream("derivation.json"))
		{
			if (in == null)
				throw new UncheckedIOException(new IOException("Missing test resource derivation.json"));
			ObjectNode output = (ObjectNode) json.readTree(new String(in.readAllBytes(), UTF_8));
			change.accept(output);
			return json.writeValueAsString(output);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Writes a contract author session's project: the files that CAT writes when the session starts, then the
	 * session's proposal file and draft directory, which hold a contract author output.
	 *
	 * @param json     the JSON mapper
	 * @param author   the contract author
	 * @param profiles the profile evidence of the session
	 * @param output   the contract author output, whose prompt tests hold their scenarios inline
	 * @param project  the session's project
	 * @return the context file
	 * @throws IOException if a file cannot be written
	 */
	public static Path write(JsonMapper json, ContractAuthorProtocol author, ProfileEvidence profiles,
		String output, Path project) throws IOException
	{
		author.request(PROMPT_SYSTEM, profiles, List.of(), List.of(), 0).files().write(project);
		String proposalFile = DraftFixtures.write(json, output, project.resolve(DraftDirectory.name(PROMPT_SYSTEM)),
			Path.of("CLAUDE.md"), Map.of());
		Files.writeString(project.resolve(OutputFile.name(PROMPT_SYSTEM)), proposalFile, UTF_8);
		return project.resolve(ContractContext.name(PROMPT_SYSTEM));
	}

	/**
	 * Returns the completed contract author session that left a project, as CAT validates it when the session ends.
	 *
	 * @param project        the session's project after the session
	 * @param initialProject an empty directory that stands for the session's project before the session
	 * @return the session's result
	 */
	public static SessionResult.Completed completed(Path project, Path initialProject)
	{
		return new SessionResult.Completed(project.resolve("transcript.jsonl"),
			List.of(List.of("The proposal is in " + OutputFile.name(PROMPT_SYSTEM) + ".")), project, initialProject,
			"model", null, null, List.of(), "skill: release-notes", List.of());
	}

	/**
	 * The report of a contract tool.
	 *
	 * @param exitCode the tool's exit code
	 * @param output   the text that the tool printed
	 */
	public record Report(int exitCode, String output)
	{
	}

	/**
	 * Runs {@code contract check}.
	 *
	 * @param json        the JSON mapper
	 * @param contextFile the session's context file
	 * @return the tool's report
	 * @throws IOException if the context file cannot be read
	 */
	public static Report check(JsonMapper json, Path contextFile) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, UTF_8))
		{
			int exitCode = new ContractTools(json).check(contextFile, out);
			return new Report(exitCode, bytes.toString(UTF_8));
		}
	}

	/**
	 * Runs {@code contract workspace}.
	 *
	 * @param json          the JSON mapper
	 * @param contextFile   the session's context file
	 * @param testDirectory the prompt test's directory, relative to the draft directory
	 * @param testCase      the test case's number, counted from 1
	 * @param output        the directory to write to
	 * @return the tool's report
	 * @throws IOException if a file cannot be read or written
	 */
	public static Report workspace(JsonMapper json, Path contextFile, Path testDirectory, int testCase, Path output)
		throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(bytes, true, UTF_8))
		{
			int exitCode = new ContractTools(json).workspace(contextFile, testDirectory, testCase, output, out);
			return new Report(exitCode, bytes.toString(UTF_8));
		}
	}
}

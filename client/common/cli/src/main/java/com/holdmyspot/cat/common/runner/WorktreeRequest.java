package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request for an operation on the registration of a campaign's optimization worktree, which a request server or a
 * run's runner carries out outside the requesting session's sandbox. Its document names the operation and the
 * campaign's identifier and nothing else: the receiver takes the project and the session from what the session cannot
 * change.
 * <p>
 * The request travels as a request archive whose name starts with {@link #PREFIX}, in the same {@code requests}
 * directory as a launch, and is answered by a {@code response.json} in the same {@code responses} directory.
 *
 * @param operation the operation
 * @param campaign  the campaign's identifier
 */
public record WorktreeRequest(Operation operation, String campaign)
{
	/**
	 * The start of the name of a worktree request's archive, before its identifier.
	 */
	public static final String PREFIX = "worktree-";
	/**
	 * The status of a request that the receiver carried out.
	 */
	public static final String DONE = "done";
	/**
	 * The status of a request that the receiver refused before it changed anything.
	 */
	public static final String REFUSED = "refused";
	/**
	 * The status of a request whose Git operation failed.
	 */
	public static final String FAILED = "failed";
	/**
	 * The result of a repair that left the worktree linked to the project's repository.
	 */
	public static final String REPAIRED = "repaired";
	/**
	 * The result of a repair that found no registration that the worktree may take.
	 */
	public static final String UNREPAIRABLE = "unrepairable";
	private static final Pattern CAMPAIGN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
	private static final String OPERATION = "operation";
	private static final String CAMPAIGN = "campaign";
	private static final Set<String> FIELDS = Set.of(OPERATION, CAMPAIGN);

	/**
	 * Creates a request.
	 *
	 * @param operation the operation
	 * @param campaign  the campaign's identifier
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code campaign} is not shaped like a campaign identifier
	 */
	public WorktreeRequest
	{
		requireThat(operation, "operation").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		if (!isCampaignId(campaign))
			throw new IllegalArgumentException("Not a campaign identifier: " + campaign);
	}

	/**
	 * Indicates whether a value is shaped like a campaign identifier: a letter or digit, then up to 63 letters, digits,
	 * underscores or hyphens.
	 *
	 * @param value the value
	 * @return {@code true} if the value has the form of a campaign identifier
	 * @throws NullPointerException if {@code value} is null
	 */
	public static boolean isCampaignId(String value)
	{
		requireThat(value, "value").isNotNull();
		return CAMPAIGN_ID.matcher(value).matches();
	}

	/**
	 * Publishes the request into a requests directory under a new identifier.
	 *
	 * @param json     the document mapper
	 * @param requests the requests directory
	 * @return the request's identifier, which names its response directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive cannot be written
	 */
	public String publish(JsonMapper json, Path requests) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(requests, "requests").isNotNull();
		String id = PREFIX + UUID.randomUUID();
		ObjectNode manifest = json.createObjectNode().put(OPERATION, operation.label()).put(CAMPAIGN, campaign);
		RequestArchive.packDocument(json, requests.resolve(id + RequestArchive.EXTENSION), manifest,
			RequestLimits.IN_RUN);
		return id;
	}

	/**
	 * Reads the request that an extracted archive holds.
	 *
	 * @param json   the document mapper
	 * @param inputs the private directory that holds the extracted archive
	 * @return the request
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive holds anything besides its document, or the document is not valid
	 *                              JSON, holds another field, names another operation, or an identifier that is not
	 *                              shaped like a campaign identifier
	 */
	public static WorktreeRequest read(JsonMapper json, Path inputs) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(inputs, "inputs").isNotNull();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inputs))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!name.equals(RequestArchive.MANIFEST))
					throw new IOException("A worktree request holds only " + RequestArchive.MANIFEST + ", not " + name);
			}
		}
		Path file = inputs.resolve(RequestArchive.MANIFEST);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Request archive requires " + RequestArchive.MANIFEST);
		JsonNode value;
		try
		{
			value = json.readTree(Files.readAllBytes(file));
		}
		catch (JacksonException e)
		{
			throw new IOException("Request manifest is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (!value.isObject())
			throw new IOException("Request manifest must be a JSON object");
		for (String field : value.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("A worktree request may not choose " + field);
		}
		JsonNode operation = value.path(OPERATION);
		JsonNode campaign = value.path(CAMPAIGN);
		if (!operation.isString())
			throw new IOException("A worktree request requires an operation");
		if (!campaign.isString() || !isCampaignId(campaign.asString()))
			throw new IOException("A worktree request requires a campaign identifier");
		return new WorktreeRequest(Operation.of(operation.asString()), campaign.asString());
	}

	/**
	 * An operation on the registration of a campaign's optimization worktree.
	 */
	public enum Operation
	{
		/**
		 * Prunes the registrations of worktrees that are gone, then adds the worktree.
		 */
		ADD,
		/**
		 * Deletes the worktree, prunes and adds it again.
		 */
		RECREATE,
		/**
		 * Removes the worktree and its registration.
		 */
		REMOVE,
		/**
		 * Repairs the links between the worktree and its registration.
		 */
		REPAIR;

		/**
		 * Returns the operation's name in a request's document.
		 *
		 * @return the name
		 */
		public String label()
		{
			return name().toLowerCase(Locale.ROOT);
		}

		/**
		 * Returns the operation of a name in a request's document.
		 *
		 * @param label the name
		 * @return the operation
		 * @throws IOException if no operation has the name
		 */
		static Operation of(String label) throws IOException
		{
			for (Operation operation : values())
			{
				if (operation.label().equals(label))
					return operation;
			}
			throw new IOException("A worktree request's operation is add, recreate, remove or repair, not " + label);
		}
	}
}

package com.holdmyspot.cat.common.runner;

import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.FORMAT_VERSION;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.IDENTITY_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.REQUEST_ID_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.TURNS_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.VERSION_FIELD;
import static com.holdmyspot.cat.common.runner.RetainedTrialRequest.readTurns;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.RetainedTrialRequest.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A broker's response concerning the retained slot of an original trial launch. A caller may start a replacement
 * attempt after {@link Status#DISCARDED}, {@link Status#ABSENT} or {@link Status#STUCK}.
 *
 * @param requestId      the original launch's request identifier
 * @param status         the broker's answer
 * @param identity       the observed slot for a ready or unsafe status, when known
 * @param completedTurns the number of completed turns for a session ready to resume, or empty otherwise
 * @param stuckSlot      the absolute path of the slot that CAT left untouched, for a stuck status, or empty otherwise
 */
public record RetainedTrialResponse(UUID requestId, Status status, Optional<Identity> identity,
	OptionalInt completedTurns, Optional<Path> stuckSlot)
{
	private static final String ID_FIELD = "id";
	private static final String STATUS_FIELD = "status";
	private static final String REASON_FIELD = "reason";
	private static final String STUCK_SLOT_FIELD = "stuck_slot";
	private static final Set<String> FIELDS = Set.of(VERSION_FIELD, ID_FIELD, REQUEST_ID_FIELD, STATUS_FIELD,
		IDENTITY_FIELD, TURNS_FIELD, REASON_FIELD, STUCK_SLOT_FIELD);

	/**
	 * Reads the broker's answer to a pending request. A discard that finds a different slot generation returns
	 * {@link Status#BUSY} or {@link Status#UNSAFE}, requiring a new status request before another discard.
	 *
	 * @param json      the document mapper
	 * @param directory the envelope's response directory
	 * @param request   the pending request
	 * @return the broker's answer, or empty while the response file is absent
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the answer cannot be read, is malformed, or contradicts the request
	 */
	public static Optional<RetainedTrialResponse> read(JsonMapper json, Path directory, RetainedTrialRequest request)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(request, "request").isNotNull();

		Path file = directory.resolve(WorktreeResponse.FILE_NAME);
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();

		JsonNode value = RetainedTrialRequest.readDocument(json, file, FIELDS);
		JsonNode envelope = value.path(ID_FIELD);
		Path directoryName = directory.getFileName();
		if (directoryName == null || !envelope.isString() || !envelope.asString().equals(directoryName.toString()))
			throw new IOException("The retained trial response names a different request envelope");
		if (value.has(REASON_FIELD))
		{
			String responseStatus = value.path(STATUS_FIELD).asString("");
			JsonNode reason = value.path(REASON_FIELD);
			if ((responseStatus.equals(WorktreeRequest.REFUSED) || responseStatus.equals(WorktreeRequest.FAILED)) &&
				reason.isString() && !value.has(REQUEST_ID_FIELD) && !value.has(IDENTITY_FIELD) && !value.has(TURNS_FIELD))
			{
				throw new IOException("The broker could not settle the retained trial: " + reason.asString());
			}
			throw new IOException("The retained trial response contains an invalid failure");
		}
		UUID requestId = RetainedTrialRequest.readUuid(value.path(REQUEST_ID_FIELD), REQUEST_ID_FIELD);
		if (!requestId.equals(request.requestId()))
			throw new IOException("The retained trial response names a different original launch");

		Status status = Status.parse(value.path(STATUS_FIELD));
		Optional<Identity> identity = Identity.readOptional(value);
		if (request.operation() != Operation.DISCARD && status == Status.DISCARDED)
			throw new IOException("Only a retained trial discard response can report a discard");
		if (request.operation() == Operation.DISCARD && (status == Status.READY || identity.isPresent()))
			throw new IOException("The retained trial discard response cannot report an observed slot");
		OptionalInt completedTurns = readTurns(value);
		Optional<Path> stuckSlot = readStuckSlot(value);
		if (request.operation() == Operation.EXPORT_SUCCESSOR && status == Status.READY)
		{
			Identity predecessor = request.identity().orElseThrow();
			if (identity.isEmpty() || !identity.get().session().equals(predecessor.session()) ||
				identity.get().slotGeneration().equals(predecessor.slotGeneration()) ||
				completedTurns.orElse(-1) != request.completedTurns().orElseThrow() + 1)
				throw new IOException("The broker returned a different successor checkpoint");
		}
		try
		{
			return Optional.of(new RetainedTrialResponse(requestId, status, identity, completedTurns, stuckSlot));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Reads the slot that a stuck status names.
	 *
	 * @param value the response document
	 * @return the slot, or empty if the document names none
	 * @throws IOException if the field is not a valid path
	 */
	private static Optional<Path> readStuckSlot(JsonNode value) throws IOException
	{
		if (!value.has(STUCK_SLOT_FIELD))
			return Optional.empty();
		JsonNode slot = value.path(STUCK_SLOT_FIELD);
		if (!slot.isString() || slot.asString().isBlank())
			throw new IOException("The retained trial response names an invalid stuck slot");
		try
		{
			return Optional.of(Path.of(slot.asString()));
		}
		catch (InvalidPathException e)
		{
			throw new IOException("The retained trial response names an invalid stuck slot", e);
		}
	}

	/**
	 * Creates an answer that names no stuck slot.
	 *
	 * @param requestId      the original launch's request identifier
	 * @param status         the broker's answer, which is not {@link Status#STUCK}
	 * @param identity       the observed slot for a ready or unsafe status, when known
	 * @param completedTurns the number of completed turns for a session ready to resume, or empty otherwise
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the identity or completed turn count contradicts the status
	 */
	public RetainedTrialResponse(UUID requestId, Status status, Optional<Identity> identity,
		OptionalInt completedTurns)
	{
		this(requestId, status, identity, completedTurns, Optional.empty());
	}

	/**
	 * Validates the fields that the reported status can carry.
	 *
	 * @param requestId      the original launch's request identifier
	 * @param status         the broker's answer
	 * @param identity       the observed slot for a ready or unsafe status, when known
	 * @param completedTurns the number of completed turns for a session ready to resume, or empty otherwise
	 * @param stuckSlot      the absolute path of the slot that CAT left untouched, for a stuck status, or empty otherwise
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the identity, completed turn count or stuck slot contradicts the status
	 */
	public RetainedTrialResponse
	{
		requireThat(requestId, "requestId").isNotNull();
		requireThat(status, "status").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(completedTurns, "completedTurns").isNotNull();
		requireThat(stuckSlot, "stuckSlot").isNotNull();
		if ((status == Status.STUCK) != stuckSlot.isPresent())
			throw new IllegalArgumentException("Only a stuck retained trial names a slot, and it must name one");
		if (stuckSlot.isPresent() && !stuckSlot.get().isAbsolute())
		{
			throw new IllegalArgumentException("The retained trial's stuck slot must be an absolute path: " +
				stuckSlot.get());
		}
		if (status == Status.READY)
		{
			if (identity.isEmpty() || identity.get().session().isEmpty() || completedTurns.isEmpty() ||
				completedTurns.getAsInt() < 0)
			{
				throw new IllegalArgumentException("A ready retained trial requires a session and a completed turn count");
			}
		}
		else if (completedTurns.isPresent() || (status != Status.UNSAFE && identity.isPresent()))
			throw new IllegalArgumentException("The retained trial status cannot carry this identity or turn count");
	}

	/**
	 * Publishes the broker's answer atomically in the request envelope's response directory.
	 *
	 * @param json      the document mapper
	 * @param id        the envelope's identifier
	 * @param directory the envelope's response directory
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the identifier is not the name of the response directory
	 * @throws IOException              if the answer cannot be written
	 */
	public void write(JsonMapper json, String id, Path directory) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(id, "id").isNotNull();
		requireThat(directory, "directory").isNotNull();
		SessionRequestCli.validateId(id);
		Path directoryName = directory.getFileName();
		if (directoryName == null || !id.equals(directoryName.toString()))
			throw new IllegalArgumentException("The retained trial response directory must be named after the envelope");

		ObjectNode value = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).put(ID_FIELD, id).
			put(REQUEST_ID_FIELD, requestId.toString()).put(STATUS_FIELD, status.name().toLowerCase(Locale.ROOT));
		identity.ifPresent(observed -> value.set(IDENTITY_FIELD, observed.document(json)));
		completedTurns.ifPresent(turns -> value.put(TURNS_FIELD, turns));
		stuckSlot.ifPresent(slot -> value.put(STUCK_SLOT_FIELD, slot.toString()));
		SessionRequestCli.write(json, directory.resolve(WorktreeResponse.FILE_NAME), value);
	}

	/**
	 * The broker's answer to a retained trial request.
	 */
	public enum Status
	{
		/**
		 * The original launch may still start or still owns active work.
		 */
		BUSY,
		/**
		 * The original launch cannot start and has no retained slot.
		 */
		ABSENT,
		/**
		 * The trial cannot resume, or a discard could not proceed. An observed identity in a status response can
		 * guard a subsequent discard.
		 */
		UNSAFE,
		/**
		 * The retained session was interrupted between turns and can be claimed for resumption.
		 */
		READY,
		/**
		 * The original launch's slot holds a launch whose state stays uncertain and whose owner is gone. CAT leaves the
		 * slot untouched and signals nothing in it, so the caller treats the original launch as settled and runs the
		 * attempt again in another slot, and the user removes the slot by hand.
		 */
		STUCK,
		/**
		 * The bound discard removed the original launch's retained slot.
		 */
		DISCARDED;

		/**
		 * Reads a response status by its lowercase wire label.
		 *
		 * @param value the status field
		 * @return the status
		 * @throws IOException if the field does not name a supported status
		 */
		private static Status parse(JsonNode value) throws IOException
		{
			if (value.isString())
			{
				for (Status candidate : values())
				{
					if (candidate.name().toLowerCase(Locale.ROOT).equals(value.asString()))
						return candidate;
				}
			}
			throw new IOException("The retained trial response has no supported status");
		}
	}

	/**
	 * The broker's observed generation of the slot and its session, when one is known.
	 *
	 * @param slotGeneration the broker-owned identifier for this occupancy of the slot
	 * @param session        the observed harness and session, or empty if the slot has no session ID
	 */
	public record Identity(UUID slotGeneration, Optional<HarnessSession> session)
	{
		private static final String GENERATION_FIELD = "slot_generation";
		private static final String HARNESS_FIELD = "harness";
		private static final String SESSION_ID_FIELD = "session_id";
		private static final Set<String> FIELDS = Set.of(GENERATION_FIELD, HARNESS_FIELD, SESSION_ID_FIELD);

		/**
		 * Checks that the observed slot generation and session information are supplied.
		 *
		 * @param slotGeneration the broker-owned identifier for this occupancy of the slot
		 * @param session        the observed harness and session, or empty if the slot has no session ID
		 * @throws NullPointerException if either argument is null
		 */
		public Identity
		{
			requireThat(slotGeneration, "slotGeneration").isNotNull();
			requireThat(session, "session").isNotNull();
		}

		/**
		 * Reads an optional observed identity from a request or reply.
		 *
		 * @param document the enclosing document
		 * @return the observed identity, or empty if the identity field is absent
		 * @throws IOException if the identity is malformed
		 */
		static Optional<Identity> readOptional(JsonNode document) throws IOException
		{
			if (!document.has(IDENTITY_FIELD))
				return Optional.empty();
			JsonNode value = document.path(IDENTITY_FIELD);
			RetainedTrialRequest.validateFields(value, FIELDS);
			UUID slotGeneration = RetainedTrialRequest.readUuid(value.path(GENERATION_FIELD), GENERATION_FIELD);
			if (!value.has(HARNESS_FIELD) && !value.has(SESSION_ID_FIELD))
				return Optional.of(new Identity(slotGeneration, Optional.empty()));

			JsonNode harness = value.path(HARNESS_FIELD);
			JsonNode sessionId = value.path(SESSION_ID_FIELD);
			if (!harness.isString() || !sessionId.isString())
				throw new IOException("A retained trial identity must supply both harness and session ID");
			try
			{
				return Optional.of(new Identity(slotGeneration,
					Optional.of(new HarnessSession(harness.asString(), sessionId.asString()))));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException(e.getMessage(), e);
			}
		}

		/**
		 * Encodes the observed slot identity for a request or response.
		 *
		 * @param json the document mapper
		 * @return the identity document
		 */
		ObjectNode document(JsonMapper json)
		{
			ObjectNode value = json.createObjectNode().put(GENERATION_FIELD, slotGeneration.toString());
			session.ifPresent(nested ->
			{
				value.put(HARNESS_FIELD, nested.harness()).put(SESSION_ID_FIELD, nested.sessionId());
			});
			return value;
		}
	}

	/**
	 * The identity of a session in its harness.
	 *
	 * @param harness   the harness that owns the session
	 * @param sessionId the session identifier reported by that harness
	 */
	public record HarnessSession(String harness, String sessionId)
	{
		/**
		 * Checks that both parts of the harness session's identity are valid.
		 *
		 * @param harness   the lowercase alphanumeric harness identifier
		 * @param sessionId the identifier reported by the harness
		 * @throws NullPointerException     if either argument is null
		 * @throws IllegalArgumentException if either argument is malformed
		 */
		public HarnessSession
		{
			requireThat(harness, "harness").isNotNull();
			requireThat(sessionId, "sessionId").isNotNull();
			if (!harness.matches("[a-z0-9]+") || !NestedSessionManager.HARNESS_SESSION_ID.matcher(sessionId).matches())
				throw new IllegalArgumentException("A retained trial identity requires a valid harness and session ID");
		}
	}
}

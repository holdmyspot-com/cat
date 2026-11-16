	Otherwise, put new guidance in a prompt file other than `AGENTS.md` or `CLAUDE.md`, which load into every
	session, and add it to one of them only when it cannot otherwise reach the sessions that need it, such as adding
	the reference that loads a new file. Keep the guidance in its separate file and any required entry-file addition
	limited to that reference. Verify that an existing delivery route loads the file when its guidance is needed; if
	none does, add a route with an observable trigger. Check that sessions with that trigger receive the guidance and
	unrelated sessions do not.

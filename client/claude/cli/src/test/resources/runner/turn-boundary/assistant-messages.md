# Claude assistant-message recording

`assistant-messages.jsonl` retains unchanged lines 3, 28, 32, 495 and the terminal result from
`docs/changelog/1.0/captures/cat-claude-live/events.jsonl`, recorded with Claude Code 2.1.220 (the init record
names that version). The first root assistant text precedes a tool-use record, and the final text is followed
by a terminal result that repeats it.

The test supplies session running/idle records independently, using the session-state envelopes recorded with
Claude Code 2.1.286 in the neighboring timelines. Those boundary records let the test exercise message extraction;
they do not claim that version 2.1.220 printed session-state records.

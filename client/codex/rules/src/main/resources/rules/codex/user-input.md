# Requesting User Input

## Design Goals

- Make every user-input tool call carry a complete question for the user whose answer would change a concrete decision
	that the agent faces, and make that question name the missing information and the action that depends on it.
- Never use the user-input tool to send a status update, progress report or other message that needs no answer.
- Never use the user-input tool to make the user repeat information or authorization that the conversation already
	gives.
- Never use the user-input tool to ask again for a decision that an earlier question in the conversation asked, whether
	or not the user has answered it yet, or to ask whether to continue, pause or keep working on an active goal or work
	that the user told the agent to continue.
- Make every message for a subagent or the parent agent reach that agent through a collaboration tool, never through
	the user-input tool, which reaches only the user.
- Never use the user-input tool to cancel, stop or retract anything, including an earlier mistaken user-input call.

## Guidance

This rule governs only calls to a user-input tool, such as `request_user_input` or `request_user_input_async`. A
user-input tool reaches only the user and never delivers anything to another agent.

Call a user-input tool only when the user's answer would change a concrete decision: which action you take, what it
covers, or whether you take it. Before calling it, identify the information that you are missing and the action that
depends on it, and write the question so that it names both. Every call carries that complete question for the user,
never a placeholder, a single word such as `ignore`, `stop` or `hello`, a tool name, or a title with no question.

Do not call a user-input tool to send a status update, progress report or other commentary that needs no answer, such
as what you are doing, what you have finished, or that work is continuing. Such a message needs no answer even when it
is worded as a question.

Do not call a user-input tool to ask for information or authorization that the user has already given in this
conversation, such as the option to use, the scope of a change, or permission to go ahead.

Before a user-input call, compare its question with every question that you have asked in this conversation, answered
or not. If one asks for the same decision, even in other words, do not call: act on its answer, or keep waiting for it.
The answer to a `request_user_input_async` call arrives later as a user message, so the absence of an answer is never a
reason to ask again. An active goal or an instruction to continue authorizes continuing that work: never ask whether to
continue, pause or keep working on it.

Send a message, update, follow-up, correction or progress question for a subagent or the parent agent with a
collaboration tool, such as `send_message` or `followup_task`, addressed to that agent. This includes telling a
subagent about a change to a file it works from, following up on a result it sent, and asking about its progress after
a wait times out. Call collaboration tools directly, like any other function tool. They are not in `exec`'s `ALL_TOOLS`
or `tools`, so their absence there does not mean that they are unavailable.

A user-input call never cancels, stops or retracts anything, including an earlier call made by mistake. After a
mistaken call, say so in at most one sentence of your response, and do not make another user-input call about it.

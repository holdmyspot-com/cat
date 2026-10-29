# Requesting User Input

## Design Goals

- Make every user-input tool call carry a question whose answer would change a concrete decision that the agent faces,
	and make that question name the missing information and the action that depends on it.
- Never use the user-input tool to send a status update, progress report or other message that needs no answer.
- Never use the user-input tool to make the user repeat information or authorization that the conversation already
	gives.

## Guidance

This rule governs only calls to a user-input tool, such as `request_user_input` or `request_user_input_async`.

Call a user-input tool only when the user's answer would change a concrete decision: which action you take, what it
covers, or whether you take it. Before calling it, identify the information that you are missing and the action that
depends on it, and write the question so that it names both.

Do not call a user-input tool to send a status update, progress report or other commentary that needs no answer, such
as what you are doing, what you have finished, or that work is continuing. Such a message needs no answer even when it
is worded as a question.

Do not call a user-input tool to ask for information or authorization that the user has already given in this
conversation, such as the option to use, the scope of a change, or permission to go ahead.

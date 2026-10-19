"""The {{tool_name}} command line: keeps short notes in a JSON file."""

import argparse
import json
import sys

STORE = "{{tool_name}}-notes.json"


def _load():
	try:
		with open(STORE) as file:
			return json.load(file)
	except FileNotFoundError:
		return []


def _save(notes):
	with open(STORE, "w") as file:
		json.dump(notes, file)


def add_note(text):
	if not text.strip():
		print("A note needs text", file=sys.stderr)
		return 1
	notes = _load()
	notes.append(text)
	_save(notes)
	print("Added note", len(notes))
	return 0


def list_notes():
	for number, text in enumerate(_load(), start=1):
		print(number, text)


def remove_note(number):
	notes = _load()
	if not 1 <= number <= len(notes):
		print("There is no note", number, file=sys.stderr)
		return 1
	del notes[number - 1]
	_save(notes)
	print("Removed note", number)
	return 0


def main(argv):
	parser = argparse.ArgumentParser(prog="{{tool_name}}")
	commands = parser.add_subparsers(dest="command", required=True)
	commands.add_parser("add").add_argument("text")
	commands.add_parser("list")
	commands.add_parser("remove").add_argument("number", type=int)
	arguments = parser.parse_args(argv)
	if arguments.command == "add":
		return add_note(arguments.text)
	if arguments.command == "list":
		list_notes()
		return 0
	return remove_note(arguments.number)


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))

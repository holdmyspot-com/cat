"""Activates the license of the {{tool_name}} editor plugin when the editor loads it."""

import json
import urllib.request

SERVICE = "https://licenses.example.com"
EDITION = "pro"


def _post(path, body):
    request = urllib.request.Request(SERVICE + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def activate(sku, state, platform):
    """Activates the license and returns the state that the editor stores for the next activation.

    sku: the edition that the user bought, such as "pro" or "team".
    state: the data that the editor stored for this plugin at its last activation, or None the first time; the
        license service reads it to recognize a machine that it activated before.
    platform: the editor family that runs the plugin, "vscode" or "jetbrains"; each family's marketplace sells its
        own licenses, so the service checks the license against that marketplace's sales.
    """
    reply = _post("/activate", {"sku": sku, "state": state, "platform": platform})
    if reply.get("status") != "active":
        raise RuntimeError("The license is not active: " + reply.get("reason", "unknown reason"))
    return reply["state"]


def on_load(editor):
    """Called by the editor when it loads the plugin."""
    state = editor.read_plugin_state()
    editor.write_plugin_state(activate(EDITION, state, editor.family))

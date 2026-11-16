"""Runs scripts in the screenshot IDE through the Robot Server plugin (see ide.js)."""
import json
import os
import time
import urllib.error
import urllib.request

URL = "http://127.0.0.1:8082/js/retrieveAny"
HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.normpath(os.path.join(HERE, "..")) + "/"
HELPERS = open(os.path.join(HERE, "ide.js")).read()


def js(script, returns=False):
    """Runs script with ide.js on the event thread; its last expression's value if returns, which must be a string."""
    # The endpoint wants the script's value to be serializable; "ok" when there's none.
    code = "var SHOTS = %s\n%s\n%s%s" % (json.dumps(SHOTS), HELPERS, script, "" if returns else '\n"ok"')
    request = urllib.request.Request(URL, json.dumps({"script": code, "runInEdt": True}).encode(),
                                     {"Content-Type": "application/json"})
    try:
        response = json.loads(urllib.request.urlopen(request, timeout=120).read())
    except urllib.error.HTTPError as e:
        response = json.loads(e.read())
    if response.get("status") != "SUCCESS":
        error = response.get("exception") or {}
        raise RuntimeError(error.get("errorMessage") or error.get("detailMessage") or json.dumps(error)[:1000])
    return _string(response.get("bytes")) if returns else None


def _string(serialized):
    """A Java-serialized String."""
    data = bytes((b + 256) % 256 for b in serialized)
    if data[4] == 0x74:  # TC_STRING
        return data[7:7 + int.from_bytes(data[5:7], "big")].decode()
    return data[13:13 + int.from_bytes(data[5:13], "big")].decode()  # TC_LONGSTRING


def wait_for_ide(timeout=300):
    for _ in range(timeout):
        try:
            urllib.request.urlopen("http://127.0.0.1:8082/hello", timeout=2)
            js('project.getName()', returns=True)
            return
        except Exception:
            time.sleep(1)
    raise TimeoutError("The screenshot IDE didn't start; run ./gradlew runIdeForScreenshots")


def wait_until_smart(timeout=120):
    for _ in range(timeout):
        if js('isSmart() ? "yes" : "no"', returns=True) == "yes":
            return
        time.sleep(1)


def shot(name, setup, wait=2.5, cleanup=None):
    """Runs setup, waits for the IDE to settle, saves SHOTS/name.png, then runs cleanup."""
    js(setup)
    time.sleep(wait)
    print(js('shot("%s")' % name, returns=True))
    if cleanup:
        js(cleanup)
        time.sleep(1)

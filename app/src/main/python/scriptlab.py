import io, json, contextlib, traceback

_G = {}


def _fresh():
    from java import jclass

    def radar():
        """Latest Radar snapshot as a dict: wifi, ble, cells, net, emf, base, log."""
        R = jclass("com.inwekobong.ekpe.RadarEngine")
        return json.loads(R.INSTANCE.snapshot())

    def notify(title, text=""):
        """Show an EKPE alert notification."""
        jclass("com.inwekobong.ekpe.RadarEngine").INSTANCE.notify(str(title), str(text))

    g = {"__name__": "__main__", "radar": radar, "notify": notify, "json": json}
    return g


def reset():
    global _G
    _G = _fresh()
    return "session reset"


def run(code):
    global _G
    if not _G:
        _G = _fresh()
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
        try:
            try:
                compiled = compile(code, "<console>", "eval")
            except SyntaxError:
                compiled = None
            if compiled is not None:
                r = eval(compiled, _G)
                if r is not None:
                    print(repr(r))
            else:
                exec(compile(code, "<console>", "exec"), _G)
        except BaseException:
            traceback.print_exc()
    return buf.getvalue()

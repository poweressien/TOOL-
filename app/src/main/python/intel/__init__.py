"""Entity Intelligence engine: public-source entity research.

The engine has no UI dependency, so the Streamlit app, the desktop app and the
bulk CLI all share exactly the same logic.
"""

__version__ = "2.2.0"
__all__ = ["run_scan", "__version__"]


def __getattr__(name):  # lazy import keeps `import intel.detect` cheap
    if name == "run_scan":
        from .scan import run_scan

        return run_scan
    raise AttributeError(name)

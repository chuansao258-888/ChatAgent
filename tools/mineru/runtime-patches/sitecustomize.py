import os
from pathlib import Path


def _configure_fast_langdetect() -> None:
    cache_dir = Path(os.environ.get("FTLANG_CACHE") or (Path.home() / ".cache" / "chatagent-mineru" / "fasttext"))
    cache_dir.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("FTLANG_CACHE", str(cache_dir))

    try:
        import fast_langdetect  # type: ignore
    except Exception:
        return

    original_detect_language = getattr(fast_langdetect, "detect_language", None)
    if original_detect_language is None or getattr(original_detect_language, "__chatagent_patched__", False):
        return

    def _detect_language_with_ascii_cache(text, *, low_memory=True):
        return original_detect_language(text, low_memory=False)

    _detect_language_with_ascii_cache.__chatagent_patched__ = True  # type: ignore[attr-defined]
    fast_langdetect.detect_language = _detect_language_with_ascii_cache


_configure_fast_langdetect()

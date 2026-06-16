import os
import shutil
import sys
from pathlib import Path


def _configure_huggingface_cache() -> None:
    cache_dir = Path(
        os.environ.get("HF_HOME")
        or (Path.home() / ".cache" / "chatagent-mineru" / "huggingface")
    )
    cache_dir.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_HOME", str(cache_dir))
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")


def _configure_huggingface_symlink_fallback() -> None:
    if os.name != "nt":
        return

    try:
        import huggingface_hub.file_download as file_download  # type: ignore
    except Exception:
        return

    original_create_symlink = getattr(file_download, "_create_symlink", None)
    if original_create_symlink is None or getattr(original_create_symlink, "__chatagent_patched__", False):
        return

    def _create_symlink_with_copy_fallback(src, dst, new_blob=False):
        Path(os.path.abspath(os.path.expanduser(str(dst)))).parent.mkdir(parents=True, exist_ok=True)
        try:
            return original_create_symlink(src, dst, new_blob=new_blob)
        except OSError as exc:
            if getattr(exc, "winerror", None) != 1314:
                raise

            abs_src = Path(os.path.abspath(os.path.expanduser(str(src))))
            abs_dst = Path(os.path.abspath(os.path.expanduser(str(dst))))
            abs_dst.parent.mkdir(parents=True, exist_ok=True)
            try:
                if abs_dst.exists() or abs_dst.is_symlink():
                    abs_dst.unlink()
            except FileNotFoundError:
                pass

            if new_blob:
                copy_function = getattr(file_download, "_copy_no_matter_what", shutil.copy2)
                shutil.move(str(abs_src), str(abs_dst), copy_function=copy_function)
            else:
                shutil.copyfile(str(abs_src), str(abs_dst))

            return None

    _create_symlink_with_copy_fallback.__chatagent_patched__ = True  # type: ignore[attr-defined]
    file_download._create_symlink = _create_symlink_with_copy_fallback


def _configure_fast_langdetect() -> None:
    cache_dir = Path(os.environ.get("FTLANG_CACHE") or (Path.home() / ".cache" / "chatagent-mineru" / "fasttext"))
    cache_dir.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("FTLANG_CACHE", str(cache_dir))

    try:
        import fast_langdetect  # type: ignore
        import fast_langdetect.ft_detect as ft_detect  # type: ignore
        import fast_langdetect.ft_detect.infer as infer  # type: ignore
    except Exception:
        return

    infer.CACHE_DIRECTORY = str(cache_dir)

    original_detect_language = getattr(fast_langdetect, "detect_language", None) or getattr(
        ft_detect, "detect_language", None
    )
    if original_detect_language is None:
        return

    def _detect_language_with_ascii_cache(text, *, low_memory=True):
        return original_detect_language(text, low_memory=False)

    _detect_language_with_ascii_cache.__chatagent_patched__ = True  # type: ignore[attr-defined]
    fast_langdetect.detect_language = _detect_language_with_ascii_cache
    ft_detect.detect_language = _detect_language_with_ascii_cache

    language_module = sys.modules.get("mineru.utils.language")
    if language_module is not None:
        language_module.detect_language = _detect_language_with_ascii_cache


_configure_huggingface_cache()
_configure_huggingface_symlink_fallback()
_configure_fast_langdetect()

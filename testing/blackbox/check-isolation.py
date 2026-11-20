"""FW-004: Nur private, physische Testverzeichnisse auf einem Linux-Runner zulassen."""

import os
import re
import stat
import sys
from pathlib import Path


def check(run: Path, target: Path) -> None:
    if not run.is_absolute() or run.resolve(strict=True) != run:
        raise ValueError(f"Umgeleiteter oder relativer Laufpfad: {run}")
    info = run.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError(f"Laufverzeichnis muss dem Testbenutzer allein gehören: {run}")
    if not target.is_relative_to(run) or target.resolve(strict=True) != target:
        raise ValueError(f"Testziel außerhalb des physischen Laufverzeichnisses: {target}")
    # Auch Bind-Mounts auf demselben Dateisystem erscheinen in mountinfo.
    for line in Path("/proc/self/mountinfo").read_text().splitlines():
        field = line.split()[4]
        mount = Path(re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), field))
        if mount.is_relative_to(run) or (mount != Path("/") and mount in run.parents):
            raise ValueError(f"Eingehängtes Dateisystem im Testlauf: {mount}")
    if not target.is_dir():
        raise ValueError(f"Testverzeichnis fehlt: {target}")
    # scandir propagiert Lesefehler. Bei einem unvollständigen Rundgang bleibt das Gate zu.
    pending = [target]
    while pending:
        with os.scandir(pending.pop()) as entries:
            for entry in entries:
                info = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    pending.append(entry.path)
                elif not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    raise ValueError(f"Umleitung oder Spezialdatei im Testziel: {entry.path}")


if __name__ == "__main__":
    try:
        check(Path(sys.argv[1]), Path(sys.argv[2]))
    except (ValueError, OSError) as error:
        sys.exit(f"FW-004: {error}")

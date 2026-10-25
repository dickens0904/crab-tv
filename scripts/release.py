#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""蟹老板TV 发版脚本：自增版本号 → 提交 → 打 tag → 构建 → GitHub Release。

版本号只有一个真源：仓库根的 version.properties
    VERSION_NAME  语义化版本号（展示在 App 设置页）
    VERSION_CODE  整数，必须严格递增

用法：
    python scripts/release.py --level patch      # 5.6.2 → 5.6.3
    python scripts/release.py --level minor      # 5.6.2 → 5.7.0
    python scripts/release.py --level major      # 5.6.2 → 6.0.0
    python scripts/release.py --code-only        # 只自增 VERSION_CODE
    python scripts/release.py --dry-run          # 只打印计划，不改文件不提交
    python scripts/release.py --skip-build       # 跳过本地构建（只提交+打tag+Release）
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VER_FILE = os.path.join(ROOT, "version.properties")
REPO = "dickens0904/crab-tv"
APKS = [
    "app/build/outputs/apk/mobile/release/mobile-arm64_v8a.apk",
    "app/build/outputs/apk/mobile/release/mobile-armeabi-v7a.apk",
    "app/build/outputs/apk/leanback/release/leanback-armeabi-v7a.apk",
]


def read_ver():
    ver = {"VERSION_NAME": "0.0.0", "VERSION_CODE": "1"}
    if os.path.exists(VER_FILE):
        for line in open(VER_FILE, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            ver[k.strip()] = v.strip()
    return ver


def write_ver(ver):
    body = (
        "# 蟹老板TV 版本号\n"
        "# 由 scripts/release.py 自动维护（发版时自增 + 打 tag），一般不要手改。\n"
        "# VERSION_NAME : 语义化版本号，展示在 App 设置页\n"
        "# VERSION_CODE : 整数，必须严格递增（应用商店/增量更新靠它判断新旧）\n"
        "VERSION_NAME=%s\nVERSION_CODE=%s\n" % (ver["VERSION_NAME"], ver["VERSION_CODE"])
    )
    with open(VER_FILE, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)


def bump(ver, level):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", ver["VERSION_NAME"])
    if not m:
        raise SystemExit("VERSION_NAME 不是 x.y.z 形式：%s" % ver["VERSION_NAME"])
    major, minor, patch = (int(x) for x in m.groups())
    if level == "major":
        major, minor, patch = major + 1, 0, 0
    elif level == "minor":
        minor, patch = minor + 1, 0
    elif level == "patch":
        patch += 1
    ver["VERSION_NAME"] = "%d.%d.%d" % (major, minor, patch)
    ver["VERSION_CODE"] = str(int(ver["VERSION_CODE"]) + 1)
    return ver


def run(cmd, cwd=ROOT, check=True):
    print("  $", " ".join(cmd))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if p.stdout.strip():
        print("   ", p.stdout.strip()[:800])
    if p.returncode != 0 and check:
        print("   ! stderr:", (p.stderr or "").strip()[:400])
        raise SystemExit("命令失败: %s" % " ".join(cmd))
    return p


def gh_path():
    """定位 gh。会话/计划任务的 PATH 可能不含 GitHub CLI 目录 → 兜底找常见安装位置。"""
    found = shutil.which("gh")
    if found:
        return found
    for p in (r"C:\Program Files\GitHub CLI\gh.exe",
              r"C:\Program Files (x86)\GitHub CLI\gh.exe",
              os.path.expandvars(r"%LOCALAPPDATA%\Programs\GitHub CLI\gh.exe")):
        if os.path.exists(p):
            return p
    raise SystemExit("找不到 gh（GitHub CLI）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--level", choices=["patch", "minor", "major"], default="patch")
    ap.add_argument("--code-only", action="store_true", help="只自增 VERSION_CODE")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-build", action="store_true")
    args = ap.parse_args()

    ver = read_ver()
    old = dict(ver)
    if args.code_only:
        ver["VERSION_CODE"] = str(int(ver["VERSION_CODE"]) + 1)
    else:
        bump(ver, args.level)
    tag = "v" + ver["VERSION_NAME"]
    print("版本: %s (code %s) -> %s (code %s)" % (
        old["VERSION_NAME"], old["VERSION_CODE"], ver["VERSION_NAME"], ver["VERSION_CODE"]))
    if args.dry_run:
        print("dry-run，到此为止")
        return

    write_ver(ver)
    run(["git", "add", "-A"])
    run(["git", "commit", "-m", "chore(release): %s" % tag])
    run(["git", "tag", "-a", tag, "-m", "蟹老板TV %s" % ver["VERSION_NAME"]])

    if not args.skip_build:
        print("构建 release 包 ...")
        run(["./gradlew", ":app:assembleMobileRelease", ":app:assembleLeanbackRelease",
             "-PappId=com.fongmi.android.tv.opencode"], cwd=ROOT)

    apks = [os.path.join(ROOT, rel) for rel in APKS]
    apks = [a for a in apks if os.path.exists(a)]
    notes = "蟹老板TV %s\n\n构建时间 %s\n" % (ver["VERSION_NAME"], time.strftime("%Y-%m-%d %H:%M"))
    print("创建 GitHub Release %s ..." % tag)
    # 2026-10-25: git push 到 github.com 在本机不可用（DNS/断流）→ 走 Git Data API 脚本。
    # 顺序很重要：先把分支和 tag 推上去，再建 Release（否则 gh release create 找不到 tag）。
    run([sys.executable, os.path.join(ROOT, "scripts", "push_github.py"),
         "--repo", REPO, "--branch", "main"], check=False)
    run([gh_path(), "release", "create", tag] + apks +
        ["--repo", REPO, "--title", "蟹老板TV %s" % ver["VERSION_NAME"],
         "--notes", notes, "--target", "main"], check=False)
    print("完成：%s" % tag)


if __name__ == "__main__":
    main()

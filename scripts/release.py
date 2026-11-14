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
    "app/build/outputs/apk/mobile/release/mobile-armeabi_v7a.apk",
    "app/build/outputs/apk/leanback/release/leanback-armeabi_v7a.apk",
    "app/build/outputs/apk/leanback/release/leanback-arm64_v8a.apk",
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


def build_env():
    """2026-11-09/12: 把 Windows 形式 JAVA_HOME (C:\\Program Files\\...) 转成 MSYS
    POSIX 路径并把 JDK/bin 放进 PATH，避免 "JAVA_HOME is not set / no java command"。

    已知坑（2026-11-12 实测）：Python subprocess 里启动的 bash 仍可能拿不到
    JAVA_HOME（Git Bash 启动脚本会覆盖外部传入的环境）。若构建仍报该错，
    直接在 Git Bash 手动执行：
        ./gradlew :app:assembleMobileRelease :app:assembleLeanbackRelease \
            -PappId=com.fongmi.android.tv.opencode --offline
    然后用 `release.py --level patch --skip-build` 续跑推送与 Release；
    构建命令已默认加 --offline（依赖都在本地 4.8G 缓存，联网曾卡 51 分钟）。
    """
    env = os.environ.copy()
    jh = env.get("JAVA_HOME", "")
    if jh and ":" in jh[:3]:
        drive, rest = jh.split(":", 1)
        jh = "/" + drive.lower() + rest.replace("\\", "/")
    if jh:
        env["JAVA_HOME"] = jh
        # 2026-11-12 修复：Windows Python 的 os.pathsep 是 ";"，但子进程是 MSYS bash，
        # bash 按 ":" 解析 PATH —— 用 ";" 拼接会让 JDK/bin 与后续路径粘成一段无效路径，
        # 结果 gradlew 报 "JAVA_HOME is not set / no java command"（本次实测踩到）。
        # 构建命令在 Windows 下固定走 "bash ./gradlew"，故此处必须硬编码 ":"。
        env["PATH"] = jh + "/bin:" + env.get("PATH", "")
    return env


def run(cmd, cwd=ROOT, check=True, env=None):
    print("  $", " ".join(cmd))
    p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True,
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
    ap.add_argument("--no-offline", action="store_true",
                    help="构建时允许联网拉依赖（默认 --offline，见下方说明）")
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
        # Windows 下 Python subprocess 直接跑 ./gradlew 会因 shebang 失效报 WinError 193，
        # 显式走 bash 解释器（Git Bash 环境一定有 bash）。
        gradlew = ["bash", "./gradlew"] if os.name == "nt" else ["./gradlew"]
        gradle_args = [":app:assembleMobileRelease", ":app:assembleLeanbackRelease",
                       "-PappId=com.fongmi.android.tv.opencode"]
        # 2026-11-12: 依赖已全在 4.8G gradle 缓存中，而沙箱访问 maven.google.com 常超时，
        # 不离线时曾卡住 51 分钟。默认离线构建；确需拉新依赖时加 --no-offline。
        if not args.no_offline:
            gradle_args.append("--offline")
        run(gradlew + gradle_args, cwd=ROOT, env=build_env())

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

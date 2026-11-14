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
    python scripts/release.py --apk-json-only    # 只更新 crab-tv-live 版本 JSON（构建失败手动补时用）
    python scripts/release.py --desc "说明"      # 指定版本 JSON 的 desc
    python scripts/release.py --no-apk-json      # 跳过版本 JSON 更新

发版完整流程：自增版本 → 提交 → 打 tag → 构建 → 推 main/tag → 建 GitHub Release →
更新 crab-tv-live 版本 JSON（App 自动更新源）。APK 下载走 Release latest/download，
版本 JSON 在 crab-tv-live，两者缺一不可，否则 App 检测不到更新。
"""
import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VER_FILE = os.path.join(ROOT, "version.properties")
REPO = "dickens0904/crab-tv"
# App 自动更新的版本 JSON 所在仓库（APK 下载走 GitHub Release latest/download，版本 JSON 在此）
LIVE_REPO = "dickens0904/crab-tv-live"
APK_JSON_FLAVORS = ("mobile", "leanback")
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


def gh_api(gh, method, path, fields=None):
    """调 gh api。fields 用 -f 传（避免命令行长度限制）。返回解析后的 JSON。"""
    cmd = [gh, "api", "--method", method, path]
    for k, v in (fields or {}).items():
        cmd += ["-f", "%s=%s" % (k, v)]
    p = run(cmd, check=False)
    try:
        return json.loads(p.stdout) if p.stdout and p.stdout.strip() else {}
    except Exception:
        return {}


def update_apk_json(ver, desc, gh):
    """更新 crab-tv-live 的 apk 版本 JSON（App 自动更新源）。

    发版必做：APK 下载走 GitHub Release latest/download（发版建 Release 即就绪），
    但版本 JSON（apk/mobile.json + apk/leanback.json）在 crab-tv-live 仓库，
    必须单独更新，否则 App 启动检查（Updater→Github.getJson）检测不到新版本。
    每个 flavor 一个 JSON；更新已有文件须带当前 sha（先 GET 取）。
    """
    name = ver["VERSION_NAME"]
    code = int(ver["VERSION_CODE"])
    for flavor in APK_JSON_FLAVORS:
        path = "apk/%s.json" % flavor
        g = gh_api(gh, "GET", "repos/%s/contents/%s" % (LIVE_REPO, path))
        sha = g.get("sha") if g else None
        data = {"name": name, "code": code, "desc": desc}
        content = base64.b64encode(json.dumps(data, ensure_ascii=False).encode("utf-8")).decode()
        fields = {"message": "release %s" % name, "content": content}
        if sha:
            fields["sha"] = sha
        r = gh_api(gh, "PUT", "repos/%s/contents/%s" % (LIVE_REPO, path), fields)
        new_sha = r.get("content", {}).get("sha", "?") if r else "fail"
        print("   %s.json → sha=%s" % (flavor, new_sha))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--level", choices=["patch", "minor", "major"], default="patch")
    ap.add_argument("--code-only", action="store_true", help="只自增 VERSION_CODE")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--no-offline", action="store_true",
                    help="构建时允许联网拉依赖（默认 --offline，见下方说明）")
    ap.add_argument("--desc", default=None, help="版本 JSON 的 desc（默认自动生成）")
    ap.add_argument("--no-apk-json", action="store_true", help="跳过 crab-tv-live 版本 JSON 更新")
    ap.add_argument("--apk-json-only", action="store_true",
                    help="只更新 crab-tv-live 版本 JSON（构建失败手动补时用）")
    args = ap.parse_args()

    ver = read_ver()
    if args.dry_run:
        old = dict(ver)
        if args.code_only:
            ver["VERSION_CODE"] = str(int(ver["VERSION_CODE"]) + 1)
        else:
            bump(ver, args.level)
        print("版本: %s (code %s) -> %s (code %s)" % (
            old["VERSION_NAME"], old["VERSION_CODE"], ver["VERSION_NAME"], ver["VERSION_CODE"]))
        print("dry-run，到此为止")
        return
    if args.apk_json_only:
        desc = args.desc or "蟹老板TV %s (code %s)" % (ver["VERSION_NAME"], ver["VERSION_CODE"])
        print("仅更新 crab-tv-live 版本 JSON：%s (code %s)" % (ver["VERSION_NAME"], ver["VERSION_CODE"]))
        update_apk_json(ver, desc, gh_path())
        print("完成（仅 apk 版本 JSON）。")
        return
    old = dict(ver)
    if args.code_only:
        ver["VERSION_CODE"] = str(int(ver["VERSION_CODE"]) + 1)
    else:
        bump(ver, args.level)
    tag = "v" + ver["VERSION_NAME"]
    print("版本: %s (code %s) -> %s (code %s)" % (
        old["VERSION_NAME"], old["VERSION_CODE"], ver["VERSION_NAME"], ver["VERSION_CODE"]))

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

    # 更新 crab-tv-live 版本 JSON（App 自动更新源）。放在 Release 之后，确保 latest/download 已指向新 APK。
    if not args.no_apk_json:
        desc = args.desc or "蟹老板TV %s (code %s)，构建于 %s" % (
            ver["VERSION_NAME"], ver["VERSION_CODE"], time.strftime("%Y-%m-%d %H:%M"))
        print("更新 crab-tv-live 版本 JSON ...")
        update_apk_json(ver, desc, gh_path())

    print("完成：%s" % tag)


if __name__ == "__main__":
    main()

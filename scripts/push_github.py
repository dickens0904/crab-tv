#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把工作树推到 GitHub，走 Git Data API（不走 git push）。

为什么不用 git push：本机到 github.com 的 git 传输会随机断流 / 卡死
（2026-10-25 实测：直连 DNS 解析不了 github.com，走 10808 代理则 push 挂 20+ 分钟
不产生任何 tmp pack），而 api.github.com 一直通畅。所以改用 API 传 blob + tree + commit。

特点：
  - 文本文件直接内联进 tree 请求（一次请求几百个文件）
  - 二进制文件逐个建 blob（base64）
  - 不带父提交 = 压成一次提交（上游历史留在 upstream remote 里）
  - 幂等：可重复运行（blob/tree 内容寻址，重复上传不会产生新对象）

用法：
    python scripts/push_github.py --repo dickens0904/crab-tv --branch main
    python scripts/push_github.py --repo dickens0904/crab-tv --branch main --message "xxx"
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.path.join(ROOT, ".git", "api_push_tmp")


def gh_path():
    import shutil
    found = shutil.which("gh")
    if found:
        return found
    for p in (r"C:\Program Files\GitHub CLI\gh.exe",
              r"C:\Program Files (x86)\GitHub CLI\gh.exe",
              os.path.expandvars(r"%LOCALAPPDATA%\Programs\GitHub CLI\gh.exe")):
        if os.path.exists(p):
            return p
    raise SystemExit("找不到 gh（GitHub CLI）")


GH = gh_path()


def api(method, path, payload=None, retries=3):
    """调 GitHub API。payload 为 dict 时用临时文件传（避免命令行长度限制）。"""
    os.makedirs(TMP, exist_ok=True)
    for attempt in range(retries):
        cmd = [GH, "api", "--method", method, path]
        if payload is not None:
            f = os.path.join(TMP, "payload.json")
            with open(f, "w", encoding="utf-8") as fp:
                json.dump(payload, fp, ensure_ascii=False)
            cmd += ["--input", f]
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if p.returncode == 0:
            try:
                return json.loads(p.stdout) if p.stdout.strip() else {}
            except json.JSONDecodeError:
                return {}
        err = (p.stderr or "").strip()
        if attempt == retries - 1:
            raise SystemExit("API 失败 %s %s: %s" % (method, path, err[:300]))
        time.sleep(2 * (attempt + 1))


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True).stdout
    return [f.decode("utf-8") for f in out.split(b"\0") if f]


def file_modes():
    out = subprocess.run(["git", "ls-files", "-s", "-z"], cwd=ROOT, capture_output=True).stdout
    modes = {}
    for rec in out.split(b"\0"):
        if not rec:
            continue
        parts = rec.split(b" ", 2)
        if len(parts) == 3:
            modes[parts[2].decode("utf-8", "replace").lstrip("\t")] = parts[0].decode()
    return modes


def is_binary(path):
    try:
        with open(path, "rb") as f:
            return b"\0" in f.read(4096)
    except OSError:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="owner/name")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--message", default=None)
    ap.add_argument("--tag", default=None, help="顺便打一个 tag（默认用 version.properties 的版本号）")
    args = ap.parse_args()

    files = tracked_files()
    modes = file_modes()
    print("跟踪文件 %d 个" % len(files))

    # 0) 空仓库不能直接建 blob(409) → 先用 README 建一个占位提交把仓库"点亮"
    try:
        api("POST", "repos/%s/git/blobs" % args.repo, {"content": "", "encoding": "utf-8"})
    except SystemExit:
        # 空仓库连 git/trees 都不让(409) → 用 Contents API 建第一个文件来"点亮"
        # （Contents API 支持空仓库，会自动建分支与首个提交）
        api("PUT", "repos/%s/contents/.gitkeep" % args.repo,
            {"message": "chore: init", "content": base64.b64encode(b"").decode(),
             "branch": args.branch})
        print("空仓库 → 已用 Contents API 建占位文件")

    # 1) 二进制 → blob
    blobs = {}
    bin_files = [f for f in files if is_binary(os.path.join(ROOT, f))]
    print("二进制 %d 个，逐个建 blob ..." % len(bin_files))
    for i, f in enumerate(bin_files, 1):
        with open(os.path.join(ROOT, f), "rb") as fp:
            content = base64.b64encode(fp.read()).decode()
        r = api("POST", "repos/%s/git/blobs" % args.repo,
                {"content": content, "encoding": "base64"})
        blobs[f] = r.get("sha")
        if i % 10 == 0 or i == len(bin_files):
            print("   blob %d/%d" % (i, len(bin_files)))

    # 2) 文本 → 分批内联进 tree
    txt_files = [f for f in files if f not in blobs]
    base_tree = None
    CHUNK = 200
    for i in range(0, len(txt_files), CHUNK):
        chunk = txt_files[i:i + CHUNK]
        entries = []
        for f in chunk:
            with open(os.path.join(ROOT, f), "r", encoding="utf-8", errors="replace") as fp:
                entries.append({"path": f, "mode": modes.get(f, "100644"),
                                "type": "blob", "content": fp.read()})
        payload = {"tree": entries}
        if base_tree:
            payload["base_tree"] = base_tree
        r = api("POST", "repos/%s/git/trees" % args.repo, payload)
        base_tree = r.get("sha")
        print("   tree %d/%d  sha=%s" % (min(i + CHUNK, len(txt_files)), len(txt_files), base_tree))

    # 3) 补上二进制条目
    if blobs:
        entries = [{"path": f, "mode": modes.get(f, "100644"), "type": "blob", "sha": sha}
                   for f, sha in blobs.items()]
        r = api("POST", "repos/%s/git/trees" % args.repo, {"tree": entries, "base_tree": base_tree})
        base_tree = r.get("sha")
        print("   补二进制条目 → tree sha=%s" % base_tree)

    # 4) commit（首个提交不带父，压缩历史；上游历史在 upstream remote）
    msg = args.message
    if not msg:
        try:
            msg = subprocess.run(["git", "log", "-1", "--pretty=%s"], cwd=ROOT,
                                 capture_output=True, text=True).stdout.strip()
        except Exception:
            msg = "update"
    c = api("POST", "repos/%s/git/commits" % args.repo,
            {"message": msg, "tree": base_tree, "parents": []})
    commit = c.get("sha")
    print("commit %s : %s" % (commit, msg[:60]))

    # 5) 更新分支 ref
    ref_path = "repos/%s/git/refs/heads/%s" % (args.repo, args.branch)
    try:
        api("PATCH", ref_path, {"sha": commit, "force": True})
    except SystemExit:
        api("POST", "repos/%s/git/refs" % args.repo,
            {"ref": "refs/heads/%s" % args.branch, "sha": commit})
    print("分支 %s → %s" % (args.branch, commit))

    # 6) tag
    tag = args.tag
    if not tag:
        ver = os.path.join(ROOT, "version.properties")
        if os.path.exists(ver):
            for line in open(ver, encoding="utf-8"):
                if line.startswith("VERSION_NAME="):
                    tag = "v" + line.split("=", 1)[1].strip()
    if tag:
        try:
            api("POST", "repos/%s/git/refs" % args.repo,
                {"ref": "refs/tags/%s" % tag, "sha": commit})
            print("tag %s → %s" % (tag, commit))
        except SystemExit as e:
            print("tag 失败（可能已存在）:", str(e)[:120])
    print("完成。")


if __name__ == "__main__":
    main()

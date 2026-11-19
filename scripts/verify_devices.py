#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""装机自检：核对每台设备的 flavor / ABI / 版本 / buffer / 激活源 URL。

背景（2026-11-19）：曾把 Dreambox（盒子，leanback）误判成 mobile 并装错包，
且两台盒子的直播订阅 URL 长期不一致。此脚本是设备状态的单一真源校验。

用法：
    python scripts/verify_devices.py
    python scripts/verify_devices.py --connect     # 先尝试 adb connect 所有 serial
退出码：0 全通过；1 有 FAIL；2 配置/环境错误。
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "scripts", "devices.json")


def run(cmd, timeout=30):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return -1, str(e)


def shell(adb, serial, command):
    return run([adb, "-s", serial, "shell", command])


def read_version():
    out = {}
    with open(os.path.join(ROOT, "version.properties"), encoding="utf-8") as f:
        for line in f:
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                out[k] = v
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--connect", action="store_true", help="先 adb connect 所有 serial")
    args = ap.parse_args()
    cfg = json.load(open(CFG, encoding="utf-8"))
    adb = os.environ.get("ADB") or cfg.get("adb") or "adb"
    ver = read_version()
    expect_ver = (ver["VERSION_NAME"], ver["VERSION_CODE"])
    failures = 0

    for dev in cfg["devices"]:
        name, serial, flavor, abi = dev["name"], dev["serial"], dev["flavor"], dev["abi"]
        exp = dev.get("expect") or {}
        print("\n== %s (%s) ==" % (name, serial))
        if args.connect:
            run([adb, "connect", serial], 15)

        rc, out = run([adb, "devices"], 15)
        if serial not in out:
            print("  WARN 离线，跳过（%s）" % dev.get("note", "未连接"))
            continue

        checks = []
        pkg = cfg["package"]
        # 1) 版本
        rc, out = shell(adb, serial, "dumpsys package %s | grep -E 'versionName|versionCode'" % pkg)
        got_ver = (re.search(r"versionName=(\S+)", out).group(1) if "versionName=" in out else "?",
                   re.search(r"versionCode=(\d+)", out).group(1) if "versionCode=" in out else "?")
        checks.append(("version", got_ver == expect_ver, "%s/%s" % got_ver, "%s/%s" % expect_ver))

        # 2) APK 大小 ↔ 本地目标 flavor/abi（最能识别装错包）
        rc, out = shell(adb, serial, "pm path %s" % pkg)
        m = re.search(r"package:(\S+)", out)
        local = os.path.join(ROOT, "app", "build", "outputs", "apk", flavor, "release",
                             "%s-%s.apk" % (flavor, abi.replace("-", "_")))
        if m and os.path.exists(local):
            rc, sz = shell(adb, serial, "stat -c %%s %s" % m.group(1))
            got_size = (sz or "").strip().splitlines()[-1] if sz.strip() else "?"
            checks.append(("flavor/abi", got_size == str(os.path.getsize(local)),
                           "%sB" % got_size, "%sB (%s-%s)" % (os.path.getsize(local), flavor, abi)))
        else:
            checks.append(("flavor/abi", False, "local APK missing", local))

        # 3) buffer
        if exp.get("buffer") is not None:
            pfx = "/data/data/%s/shared_prefs/%s_preferences.xml" % (pkg, pkg)
            rc, out = shell(adb, serial, 'grep -o \'name="buffer" value="[0-9]*"\' %s' % pfx)
            m = re.search(r'value="(\d+)"', out)
            got = int(m.group(1)) if m else -1
            checks.append(("buffer", got == exp["buffer"], str(got), str(exp["buffer"])))

        # 4) 激活源 URL（type=1 直播 / type=0 点播，按 time DESC 取第一条）
        db = "/data/data/%s/databases/tv" % pkg
        for label, typ in (("live", 1), ("vod", 0)):
            want = exp.get(label)
            if not want:
                continue
            rc, out = shell(adb, serial,
                            'sqlite3 %s "SELECT url FROM Config WHERE type=%d ORDER BY time DESC LIMIT 1;"' % (db, typ))
            got = (out or "").strip().splitlines()[-1] if out.strip() else "?"
            checks.append((label, got == want, got[-72:], want[-72:]))

        for label, ok, got, want in checks:
            print("  %-4s %-12s got=%s want=%s" % ("PASS" if ok else "FAIL", label, got, want))
            if not ok:
                failures += 1
    print("\n结果：%d 项失败" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

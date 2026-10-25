# [SELFTEST -> FEATURE] Source-check spider.
#
# PURPOSE
#   Given one or more CMS endpoints (the `?ac=list&wd=&ids=` family that FongMi speaks
#   for site type 0 = XML and type 1 = JSON), decide whether the endpoint is actually
#   usable *by this app*, and show exactly why not when it is not.
#
# WHY A SPIDER AND NOT A STANDALONE SCRIPT
#   BaseLoader.getSpider dispatches on the api string: an api containing ".py" goes to
#   PyLoader and runs under Chaquopy in-process, with no third-party jar involved. That
#   matters because a self-built APK is signed with our own key and is therefore rejected
#   by the csp_* jars' built-in InitCheck (certificate + package + app-label whitelist).
#   Python spiders have no such gate, so this feature keeps working in a self-built build
#   -- which is exactly the situation it exists to serve.
#
# FAITHFULNESS TO THE APP (this is the whole point of doing it as a spider)
#   Each stage mirrors SiteApi, so "passes here" implies "works in the app":
#     Stage 1  home     GET {api}                              (SiteApi.homeContent)
#     Stage 2  category GET {api}?ac=videolist&t={tid}&pg=1   (SiteApi.categoryContent)
#     Stage 3  detail   GET {api}?ac=detail&ids={id}          (SiteApi.detailContent)
#   The app sends no query parameters at all for stage 1, so neither do we; when that
#   yields no classes we retry once with `?ac=list` (what most CMS docs describe) and
#   record which of the two actually worked, so the report never misstates the cause.
#   Response parsing accepts both the JSON and the XML dialect and auto-detects which one
#   came back, because the same endpoint can serve either.
#
# CONFIGURATION (site `ext`, all keys optional)
#   bare URL / whitespace-separated URLs  -> test exactly those endpoints
#   JSON object:
#     {"candidates": [["name","url"], ...],  endpoints to test; defaults to the seed list
#      "timeout": 8,                         per-request seconds
#      "cms_ext": "",                        value for the optional `extend` param
#      "stages": 3,                          1 = home only (fast), 2 = + category, 3 = all
#      "workers": 8}                         parallel probes for the batch view
#
# ASCII-only comments on purpose: the host javac defaults to GBK on a Chinese Windows and
# mangles UTF-8 CJK bytes, and this file ships through the asset packer.

import json
import re
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote

from base.spider import Spider

# Endpoints verified working against the CMS `provide/vod` family. Seeded so the tool is
# useful with zero configuration, and because they double as a regression suite: a change
# that makes any of these fail is a change worth knowing about.
SEED = [
    ("apibdzy", "https://api.apibdzy.com/api.php/provide/vod/"),
    ("hhzy", "https://hhzyapi.com/api.php/provide/vod/"),
    ("lzizy", "https://lzizy1.com/api.php/provide/vod/"),
    ("huya", "https://www.huyaapi.com/api.php/provide/vod/from/hym3u8"),
    ("ffm3u", "https://api.ffzyapi.com/api.php/provide/vod/from/ffm3u8"),
    ("lzicj", "https://cj.lziapi.com/api.php/provide/vod/"),
    ("bdm3u", "https://api.apibdzy.com/api.php/provide/vod/from/m3u8"),
    ("hhm3u2", "https://hhzyapi.com/api.php/provide/vod/from/hhm3u8"),
    ("lzm3u", "https://lzizy1.com/api.php/provide/vod/from/lzm3u8"),
    ("jsapi", "https://jszyapi.com/api.php/provide/vod/"),
]

STAGE_HOME, STAGE_CATEGORY, STAGE_DETAIL = 1, 2, 3
STAGE_NAMES = {STAGE_HOME: "首页分类", STAGE_CATEGORY: "分类视频", STAGE_DETAIL: "播放解析"}

# Mirrors the app's own User-Agent. See http_get for why this is load-bearing.
DEFAULT_UA = "okhttp/5.5.0"

# Hard ceiling on a single response body. A TV box has very little RAM and a CMS with tens
# of thousands of rows answers ?ac=list with megabytes; reading that unbounded risks an
# OOM kill of the whole app rather than a failed probe.
MAX_BODY_BYTES = 4 * 1024 * 1024

# Wall-clock ceiling for probing one endpoint. Without it the worst case is
# (2 home + up to 6 category + 1 detail) requests x timeout, which at the default 8s is
# ~70s of the user staring at a spinner for a single dead host.
SOURCE_BUDGET_S = 45

# Upper bound on how many endpoints one run may probe, so a stray thousand-URL paste in the
# search box cannot turn into a thousand background requests.
MAX_TARGETS = 40

# Extensions worth reporting as playable. An endpoint that only hands back an HTML error
# page is not a usable source, so a "play url" that is really markup must not pass.
PLAYABLE = (".m3u8", ".mp4", ".flv", ".mkv", ".avi", ".mov", ".ts", ".mpg", ".rmvb", ".webm")


def host_of(url):
    m = re.match(r"https?://([^/?#]+)", url or "")
    return m.group(1) if m else (url or "")


def clip(text, limit=300):
    text = re.sub(r"\s+", " ", str(text or "")).strip()
    return text[:limit] + ("..." if len(text) > limit else "")


def human_bytes(n):
    """Byte count as MB/KB/B. Avoids the "0 KB" absurdity when the cap is scaled down."""
    if n >= 1024 * 1024:
        return "%.1f MB" % (n / 1024.0 / 1024.0)
    if n >= 1024:
        return "%d KB" % (n / 1024)
    return "%d B" % n


def with_query(url, params):
    parts = ["%s=%s" % (k, quote(str(v), safe=""))
             for k, v in (params or {}).items() if v not in (None, "")]
    if not parts:
        return url
    return url + ("&" if "?" in url else "?") + "&".join(parts)


def split_urls(text):
    return [p.strip() for p in re.split(r"[\s,;]+", text or "") if p.strip().startswith("http")]


# --------------------------------------------------------------------------- responses


def parse_body(body):
    """Classify a response body -> (kind, payload).

    kind is json / xml / html / empty / junk. `junk` matters: a fair number of dead CMS
    hosts answer HTTP 200 with an error page, and treating that as "unparseable" is what
    separates "dead" from "returned nothing useful".
    """
    text = (body or "").strip()
    if not text:
        return "empty", None
    start = min([i for i in (text.find("{"), text.find("["), text.find("<")) if i >= 0] or [-1])
    if start < 0:
        return "junk", clip(text)
    text = text[start:]
    if text[0] in "{[":
        try:
            return "json", json.loads(text)
        except ValueError:
            return "junk", clip(body)
    if text[0] == "<":
        if any(t in text[:400].lower() for t in ("<html", "<!doctype", "<head", "<body")):
            return "html", clip(body)
        try:
            return "xml", ET.fromstring(text)
        except ET.ParseError:
            return "junk", clip(body)
    return "junk", clip(body)


def find_first(node, *tags):
    """First descendant element matching any of `tags`.

    CMS XML nests inconsistently (`<results><class>` vs a bare `<class>` root, `<video>`
    vs `<vod>`), so a tag search beats a fixed path.
    """
    if node is None:
        return None
    for tag in tags:
        found = node.find(".//" + tag)
        if found is not None:
            return found
    return None


def attr_or_text(node, *names, default=""):
    if node is None:
        return default
    for name in names:
        if name in node.attrib:
            return node.attrib[name]
    if node.text:
        return node.text.strip()
    return default


def extract_classes(kind, payload):
    """Class/category list -> [{id, name}], for both dialects."""
    out = []
    if kind == "json" and isinstance(payload, dict):
        for c in payload.get("class") or []:
            if isinstance(c, dict) and str(c.get("type_id", "")):
                cid = str(c["type_id"])
                out.append({"id": cid, "name": str(c.get("type_name", "") or cid)})
        return out
    if kind == "xml":
        node = find_first(payload, "class")
        if node is not None:
            for item in node.findall(".//item"):
                cid = attr_or_text(item, "type_id", "type")
                if cid:
                    out.append({"id": cid, "name": attr_or_text(item, "type_name", "name", default=cid)})
    return out


def extract_videos(kind, payload):
    """Video list -> [{id, name, pic}], for both dialects."""
    out = []
    if kind == "json" and isinstance(payload, dict):
        for v in payload.get("list") or []:
            if isinstance(v, dict) and str(v.get("vod_id", "")):
                out.append({"id": str(v["vod_id"]),
                            "name": str(v.get("vod_name", "") or v["vod_id"]),
                            "pic": str(v.get("vod_pic", "") or "")})
        return out
    if kind == "xml":
        node = find_first(payload, "list")
        if node is not None:
            for item in node.findall(".//video"):
                vid = attr_or_text(item, "vid", "vod_id")
                if vid:
                    out.append({"id": vid, "name": attr_or_text(item, "name", "vod_name", default=vid),
                                "pic": attr_or_text(item, "pic", "vod_pic")})
    return out


def page_meta(kind, payload):
    """Pagination fields as strings -- maccms returns them quoted, and the XML dialect
    exposes the same names on the root element."""
    if kind == "json" and isinstance(payload, dict):
        return {k: str(payload.get(k, "")) for k in ("page", "pagecount", "limit", "total")}
    if kind == "xml":
        root = payload if payload is not None else None
        if root is not None:
            return {k: attr_or_text(root, k) for k in ("page", "pagecount", "limit", "total")}
    return {}


def extract_play(kind, payload):
    """Detail result -> (play_from, episodes).

    episodes is [{name, url}] so the detail page can offer genuinely watchable entries
    rather than just a verdict string.
    """
    play_from, play_url = "", ""
    if kind == "json" and isinstance(payload, dict):
        items = payload.get("list") or []
        if items and isinstance(items[0], dict):
            play_from = str(items[0].get("vod_play_from", "") or "")
            play_url = str(items[0].get("vod_play_url", "") or "")
    elif kind == "xml":
        node = find_first(payload, "detail") or find_first(payload, "list")
        if node is not None:
            play_from = attr_or_text(node, "vod_play_from")
            play_url = attr_or_text(node, "vod_play_url")
    episodes = []
    for chunk in re.split(r"#", play_url or ""):
        chunk = chunk.strip()
        if not chunk:
            continue
        bits = chunk.split("$", 1)
        url = bits[1].strip() if len(bits) > 1 else ""
        if url:
            episodes.append({"name": bits[0].strip() or "播放", "url": url})
    return play_from, episodes


def is_playable(url):
    low = (url or "").lower().split("?")[0]
    return any(ext in low for ext in PLAYABLE)


def kind_reason(kind, empty):
    return {"html": "返回 HTML 页面（不是接口）", "junk": "返回非 JSON/XML 内容",
            "empty": "空响应", "xml": "XML 但无 %s" % empty, "json": "JSON 但无 %s" % empty}.get(kind, "无内容")


# --------------------------------------------------------------------------- probing


def http_get(url, params, timeout, ua):
    """GET with the same query shape and User-Agent SiteApi uses -> (body, ms, error).

    The User-Agent is not cosmetic. OkHttp stamps `User-Agent: okhttp/<version>` on every
    request, and a measurable share of CMS hosts gate on it: api.ffzyapi.com answers
    HTTP 403 to `python-requests/*` and HTTP 200 to `okhttp/*`. A probe that omits the UA
    therefore reports live sources as dead, which is worse than not probing at all. The
    default mirrors the app's pinned okhttp version (libs.versions.toml -> okhttp = 5.5.0)
    and is overridable via `ext` for the occasional host that wants something else.

    The body is streamed with a hard byte cap. A TV box has very little RAM, and `?ac=list`
    on a CMS with tens of thousands of rows routinely returns megabytes; without the cap a
    single fat endpoint can take the whole app down with an OOM.
    """
    import requests
    started = time.time()
    try:
        rsp = requests.get(with_query(url, params), timeout=timeout, verify=False,
                           allow_redirects=True, headers={"User-Agent": ua}, stream=True)
        try:
            if rsp.status_code >= 400:
                hint = "（该源拒绝非 okhttp UA，可改 ext 里的 ua）" \
                    if rsp.status_code == 403 and "okhttp" not in ua else ""
                return None, int((time.time() - started) * 1000), \
                    "HTTP %d%s" % (rsp.status_code, hint)
            chunks, size = [], 0
            for chunk in rsp.iter_content(65536):
                chunks.append(chunk)
                size += len(chunk)
                if size > MAX_BODY_BYTES:
                    return None, int((time.time() - started) * 1000), \
                        "响应超过 %s，判定为非接口" % human_bytes(MAX_BODY_BYTES)
        finally:
            rsp.close()
        # Matches base.spider: FongMi treats every CMS response as UTF-8.
        return b"".join(chunks).decode("utf-8", "replace"), int((time.time() - started) * 1000), None
    except Exception as error:
        return None, int((time.time() - started) * 1000), clip(error, 160)


def stage_home(url, params, timeout, ua):
    home = {"stage": STAGE_HOME, "ok": False, "ms": 0, "error": None, "classes": [],
            "summary": "", "detail": "", "kind": None, "via": "裸接口"}
    body, ms, error = http_get(url, params, timeout, ua)
    home["ms"] = ms
    if error:
        home.update(summary="连接失败", detail=error, error=error)
        return home
    kind, payload = parse_body(body)
    home["kind"] = kind
    classes = extract_classes(kind, payload)
    if not classes:
        # Documented addition, not a mirror of SiteApi: see the header note.
        body, ms2, error2 = http_get(url, dict(params, ac="list"), timeout, ua)
        home["ms"] += ms2
        home["via"] = "?ac=list"
        if not error2:
            kind2, payload2 = parse_body(body)
            home["kind"] = kind2
            classes = extract_classes(kind2, payload2)
    if not classes:
        home.update(summary=kind_reason(home["kind"], "class"), detail=clip(body), error=error)
        return home
    home.update(ok=True, classes=classes, count=len(classes),
                summary="%d 个分类（%s）" % (len(classes), home["via"]),
                detail="分类ID: " + ", ".join(c["id"] for c in classes[:12]))
    return home


def stage_category(url, params, timeout, ua, tids):
    """Try each candidate category until one returns videos.

    Records `empty_tids` so the report can distinguish "the whole source is empty" from
    "only the first channel was empty", which look identical in a bare pass/fail.
    """
    cat = {"stage": STAGE_CATEGORY, "ok": False, "ms": 0, "error": None, "videos": [],
           "summary": "", "detail": "", "kind": None, "tid": None, "empty_tids": [],
           "total": 0, "pagecount": 0}
    if not tids:
        cat.update(summary="首页没有可用分类 ID")
        return cat
    last = None
    for tid in tids:
        body, ms, error = http_get(url, dict(params, ac="videolist", t=tid, pg="1"), timeout, ua)
        cat["ms"] += ms
        if error:
            last = {"summary": "连接失败", "detail": error, "error": error, "kind": None}
            continue
        kind, payload = parse_body(body)
        videos = extract_videos(kind, payload)
        if not videos:
            cat["empty_tids"].append(tid)
            last = {"summary": kind_reason(kind, "list"), "detail": clip(body), "error": None, "kind": kind}
            continue
        meta = page_meta(kind, payload)
        pics = sum(1 for v in videos if v["pic"])
        cat.update(ok=True, kind=kind, tid=tid, videos=videos, count=len(videos),
                   with_pic=pics, total=meta.get("total", 0), pagecount=meta.get("pagecount", 0),
                   summary="分类 %s 出 %d 条（共 %s 条），封面 %d/%d" % (
                       tid, len(videos), meta.get("total", "?"), pics, len(videos)),
                   detail="首条: " + videos[0]["name"])
        return cat
    cat.update(summary="试了 %d 个分类都没有数据（%s）" % (len(cat["empty_tids"]), last["summary"] if last else "无"),
               detail=last["detail"] if last else "", error=last["error"] if last else None)
    return cat


def stage_detail(url, params, timeout, ua, vod_id):
    det = {"stage": STAGE_DETAIL, "ok": False, "ms": 0, "error": None, "episodes": [],
           "summary": "", "detail": "", "kind": None, "play_from": ""}
    body, ms, error = http_get(url, dict(params, ac="detail", ids=vod_id), timeout, ua)
    det["ms"] = ms
    if error:
        det.update(summary="连接失败", detail=error, error=error)
        return det
    kind, payload = parse_body(body)
    det["kind"] = kind
    play_from, episodes = extract_play(kind, payload)
    det["play_from"] = play_from
    if not episodes:
        det.update(summary=kind_reason(kind, "vod_play_url"), detail=clip(body))
        return det
    good = [e for e in episodes if is_playable(e["url"])]
    det.update(ok=bool(good), episodes=episodes, playable=len(good),
               summary="%d 集，可播 %d（线路 %s）" % (len(episodes), len(good), play_from or "-"),
               detail=good[0]["url"] if good else episodes[0]["url"])
    return det


def pick_tids(home, limit=6):
    """Candidate categories to drill into, best-first.

    Two behaviours of real CMS endpoints drive this:
      * the top-level channels (电影 / 电视剧 / 综艺, ids 1-4 on most maccms builds) are
        often pure navigation and answer `videolist` with an empty list, while the genre
        level underneath (ids 6+) is where the content lives -- so a single-category probe
        reports a perfectly good source as dead;
      * some endpoints expose extra ids such as "tui"/"recommend" that never paginate.
    So return several, ordered, and let the caller try them in turn.
    """
    classes = home.get("classes") or []
    if not classes:
        return []
    skip = {"tui", "recommend", "recom", "hot", "top", "vip", "mv", "live"}
    numeric = [c["id"] for c in classes if c["id"].isdigit() and c["id"].lower() not in skip]
    other = [c["id"] for c in classes if not c["id"].isdigit() and c["id"].lower() not in skip]
    return (numeric + other)[:limit]


def skipped(stage, summary):
    """A stage that was not run, with the reason. Distinct from a stage that failed."""
    return {"stage": stage, "ok": False, "skipped": True, "ms": 0, "error": None,
            "classes": [], "videos": [], "episodes": [], "summary": summary, "detail": ""}


def guard(stage_id, fn):
    """Run a stage, converting any unexpected exception into a reported failure.

    Without this a single malformed endpoint (an unhashable JSON value, a regex blowing
    its stack on a hostile body) raises out of probe(), and because probe() is what the
    thread pool maps over, that exception discards the results of every *other* endpoint
    in the same batch. A testing tool must never lose the sources it did manage to test.
    """
    try:
        return fn()
    except Exception as error:
        return skipped(stage_id, "解析异常: %s" % clip(error, 120))


def probe(name, url, params, timeout, stages, ua=DEFAULT_UA, budget=SOURCE_BUDGET_S):
    """Run the configured stages for one endpoint -> a verdict record.

    `record["ok"]` is "every stage that actually ran passed", not just the first. That
    matters when `stages` is lowered to 1 for a quick sweep: a source that serves a class
    list but refuses `videolist` would otherwise be reported as fine.
    """
    deadline = time.time() + budget
    record = {"key": name, "url": url, "host": host_of(url), "stages": [], "ok": False,
              "ms": 0, "ua": ua}

    def left():
        # Soft deadline: a request already in flight is not interrupted, and the floor keeps
        # every remaining stage from being handed a zero timeout (which would fail instantly
        # and mark a healthy source dead). Worst case overshoot is one floor per remaining
        # request, i.e. a few seconds past SOURCE_BUDGET_S.
        return max(1.0, deadline - time.time())

    if stages >= STAGE_HOME:
        home = guard(STAGE_HOME, lambda: stage_home(url, params, left(), ua))
        record["stages"].append(home)
        record["ms"] += home["ms"]
    if stages >= STAGE_CATEGORY and record["stages"] and record["stages"][0]["ok"]:
        tids = pick_tids(record["stages"][0])
        record["tids"] = tids
        category = guard(STAGE_CATEGORY, lambda: stage_category(url, params, left(), ua, tids))
        record["stages"].append(category)
        record["ms"] += category["ms"]
        if stages >= STAGE_DETAIL and category["ok"]:
            first = category["videos"][0]["id"]
            detail = guard(STAGE_DETAIL, lambda: stage_detail(url, params, left(), ua, first))
            record["stages"].append(detail)
            record["ms"] += detail["ms"]
    # Only stages that actually ran count towards the verdict; a skipped stage is neutral.
    ran = [s for s in record["stages"] if not s.get("skipped")]
    record["ok"] = bool(ran) and all(s["ok"] for s in ran)
    return record


# --------------------------------------------------------------------------- rendering


def verdict(record):
    if record["ok"]:
        return "可用"
    ran = [s for s in record["stages"] if not s.get("skipped")]
    skipped = len(record["stages"]) - len(ran)
    text = "不可用（%d/%d 段通过）" % (sum(1 for s in ran if s["ok"]), len(ran))
    if skipped:
        text += "，%d 段未跑完" % skipped
    return text


def remark(record):
    return "   ".join("%s %s" % ("⚠" if s.get("skipped") else ("✅" if s["ok"] else "❌"),
                                  s["summary"]) for s in record["stages"])


def report(record):
    lines = ["接口: %s" % record["url"], "总耗时: %d ms" % record["ms"],
             "探测 UA: %s（与 App 内 OkHttp 一致）" % record.get("ua", DEFAULT_UA), ""]
    for s in record["stages"]:
        lines.append("%s %s — %s (%d ms)" % ("[跳过]" if s.get("skipped")
                                           else ("[通过]" if s["ok"] else "[失败]"),
                                           STAGE_NAMES.get(s["stage"], "?"),
                                           s["summary"], s["ms"]))
        if s.get("detail"):
            lines.append("    %s" % s["detail"])
    for s in record["stages"]:
        if s["stage"] == STAGE_CATEGORY and s.get("empty_tids"):
            lines.append("    （空分类已跳过: %s）" % ", ".join(s["empty_tids"]))
    episodes = next((s["episodes"] for s in record["stages"] if s.get("episodes")), None)
    if episodes:
        lines += ["", "播放列表（前 5 集）:"]
        lines += ["    %s  %s" % (e["name"], clip(e["url"], 120)) for e in episodes[:5]]
    lines += ["", "结论: %s" % verdict(record)]
    return "\n".join(lines)


def playable_episodes(record):
    for s in record["stages"]:
        good = [e for e in s.get("episodes") or [] if is_playable(e["url"])]
        if good:
            return good
    return []


def to_vod(record):
    """Summary row for one endpoint; carries real watchable episodes when it passed."""
    vod = {"vod_id": record["url"], "vod_name": record["key"] or record["host"],
           "vod_pic": "", "vod_remarks": verdict(record), "vod_content": report(record)}
    episodes = playable_episodes(record)
    if episodes:
        vod["vod_play_from"] = "试看"
        vod["vod_play_url"] = "#".join("%s$%s" % (e["name"], e["url"]) for e in episodes[:30])
    return vod


def to_stage_vod(record, stage):
    """One row per stage, for the per-source drill-down.

    No play section on purpose: a stage row is evidence, not media, and emitting a
    placeholder episode would make the app try to "play" the elapsed-time string. The
    report lives in vod_content, which the detail page renders.
    """
    return {"vod_id": "%s#%d" % (record["url"], stage["stage"]),
            "vod_name": "%s %s" % ("✅" if stage["ok"] else "❌",
                                   STAGE_NAMES.get(stage["stage"], "?")),
            "vod_pic": "", "vod_remarks": stage["summary"], "vod_content": report(record)}


# --------------------------------------------------------------------------- spider


class Spider(Spider):

    def init(self, extend=""):
        self.extend = extend or ""
        cfg = self._config()
        self.timeout = cfg["timeout"]
        self.params = {"extend": cfg["cms_ext"]} if cfg["cms_ext"] else {}
        self.stages = max(1, min(cfg["stages"], STAGE_DETAIL))
        self.ua = cfg["ua"]
        self.workers = max(1, min(cfg["workers"], 16))
        self.candidates = cfg["candidates"][:MAX_TARGETS]
        self.truncated = len(cfg["candidates"]) > MAX_TARGETS
        self._cache = {}

    def _config(self):
        cfg = {"candidates": list(SEED), "timeout": 8, "cms_ext": "", "stages": 3,
               "workers": 8, "ua": DEFAULT_UA}
        raw = self.extend.strip()
        if not raw:
            return cfg
        if raw.startswith("{"):
            try:
                data = json.loads(raw)
            except ValueError:
                return cfg
            if isinstance(data.get("candidates"), list) and data["candidates"]:
                cfg["candidates"] = [((x[0], x[1]) if isinstance(x, list) and len(x) > 1
                                       else (host_of(str(x)), str(x))) for x in data["candidates"]]
            for key in ("timeout", "stages", "workers"):
                try:
                    if key in data:
                        cfg[key] = int(data[key])
                except (TypeError, ValueError):
                    pass
            cfg["cms_ext"] = str(data.get("cms_ext") or "")
            cfg["ua"] = str(data.get("ua") or DEFAULT_UA)
            return cfg
        urls = split_urls(raw)
        if urls:
            cfg["candidates"] = [(host_of(u), u) for u in urls]
        return cfg

    def _run(self, items):
        """Probe a list of (name, url) in parallel, preserving input order.

        Every target gets a record even if probing it blows up, so one bad endpoint can
        never cost the caller the results of the others.
        """
        if not items:
            return []
        items = list(items)[:MAX_TARGETS]

        def one(item):
            try:
                return probe(item[0], item[1], self.params, self.timeout,
                             self.stages, self.ua)
            except Exception as error:
                return {"key": item[0], "url": item[1], "host": host_of(item[1]),
                        "stages": [skipped(STAGE_HOME, "探测异常: %s" % clip(error, 120))],
                        "ok": False, "ms": 0, "ua": self.ua}

        with ThreadPoolExecutor(max_workers=min(self.workers, len(items))) as pool:
            records = list(pool.map(one, items))
        for record in records:
            self._cache[record["url"]] = record
        return records

    def _batch_list(self, records, head_extra=None):
        good = sum(1 for r in records if r["ok"])
        head = {"vod_id": "batch", "vod_name": "批量结果：%d/%d 可用" % (good, len(records)),
                "vod_pic": "", "vod_remarks": "共 %d ms" % sum(r["ms"] for r in records),
                "vod_content": head_extra or ""}
        return {"list": [head] + [to_vod(r) for r in records],
                "page": 1, "pagecount": 1, "limit": len(records) + 1, "total": len(records)}

    def homeContent(self, filter):
        # Home stays instant on purpose: probing ten endpoints is tens of seconds of
        # network work and must not block the UI. One tap runs the batch.
        classes = [{"type_id": "batch", "type_name": "▶ 批量测试全部（%d 个）" % len(self.candidates)}]
        classes += [{"type_id": str(i), "type_name": name or host_of(url)}
                    for i, (name, url) in enumerate(self.candidates)]
        note = "在搜索框里直接粘贴任意接口地址也可以临时测试，多个地址用空格或逗号隔开即可。"
        if self.truncated:
            note = "候选源超过 %d 个，只测了前 %d 个。%s" % (
                MAX_TARGETS, len(self.candidates), note)
        hint = {"vod_id": "batch", "vod_name": "点上面第一项开始批量测试",
                "vod_pic": "", "vod_remarks": "%d 个候选源" % len(self.candidates),
                "vod_content": note}
        return {"class": classes, "list": [hint]}

    def categoryContent(self, tid, pg, filter, extend):
        try:
            if tid == "batch":
                return self._batch_list(
                    self._run(self.candidates),
                    "把 ext 改成 {\"stages\":1} 可以只测首页，快速筛掉死链。")
            name, url = self.candidates[int(tid)]
        except (ValueError, IndexError, TypeError):
            return {"list": [], "page": 1, "pagecount": 1, "limit": 0, "total": 0}
        record = self._run([(name, url)])[0]
        rows = [to_stage_vod(record, s) for s in record["stages"]]
        rows.insert(0, to_vod(record))
        return {"list": rows, "page": 1, "pagecount": 1, "limit": len(rows), "total": len(rows)}

    def detailContent(self, ids):
        url = (ids[0] if ids else "").split("#")[0]
        try:
            record = self._cache.get(url) or probe(host_of(url), url, self.params,
                                                    self.timeout, self.stages, self.ua)
        except Exception as error:
            record = {"key": host_of(url), "url": url, "host": host_of(url), "ms": 0,
                      "ua": self.ua, "ok": False,
                      "stages": [skipped(STAGE_HOME, "探测异常: %s" % clip(error, 120))]}
        self._cache[url] = record
        vod = to_vod(record)
        vod["vod_name"] = "%s  %s" % (record["key"] or record["host"], verdict(record))
        return {"list": [vod]}

    def searchContent(self, key, quick, pg="1"):
        """Test whatever endpoint(s) the user pasted, split on whitespace/comma/semicolon."""
        urls = split_urls(key)
        if not urls:
            return {"list": [], "page": 1, "pagecount": 1, "limit": 0, "total": 0}
        records = self._run([(host_of(u), u) for u in urls])
        if len(urls) > MAX_TARGETS:
            records = records + [{"key": "…", "url": "", "host": "", "ok": False, "ms": 0,
                                  "stages": [skipped(STAGE_HOME,
                                                    "一次最多测 %d 个，剩余 %d 个未测"
                                                    % (MAX_TARGETS, len(urls) - MAX_TARGETS))]}]
        return {"list": [to_vod(r) for r in records],
                "page": 1, "pagecount": 1, "limit": len(records), "total": len(records)}

    def playerContent(self, flag, id, vipsFlags):
        # to_vod only emits playable urls, so reaching here means stage 3 passed and `id`
        # is a real media url. No parsing: the caller already holds the source.
        return {"parse": 0, "playUrl": id, "header": {}}
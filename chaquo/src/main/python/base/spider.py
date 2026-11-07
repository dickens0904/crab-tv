import re
import os
import json
import time
import requests
from com.github.catvod import Proxy
from com.chaquo.python import Python
from abc import abstractmethod, ABCMeta
from importlib.machinery import SourceFileLoader

# 2026-10-09: upstream did "from lxml import etree" here at module scope. lxml.etree is a
# native extension module, and in a self-built APK it is not always resolvable at runtime
# -- the pure-Python half of lxml lands under files/chaquopy/AssetFinder/requirements/lxml/
# while its compiled etree.so does not, so the import raised
#   ImportError: cannot import name 'etree' from 'lxml'
# at import time. Because every Python spider does "from base.spider import Spider", that
# single top-level import killed all of them before their first line ran. etree is used in
# exactly one place (Spider.html below), so it is now imported lazily and optionally:
# spiders that never call html() no longer need the native module at all, and html() falls
# back to the stdlib html.parser when lxml is unavailable.
try:
    from lxml import etree
except ImportError:
    etree = None


class Spider(metaclass=ABCMeta):
    _instance = None

    def __init__(self):
            self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        else:
            cls._instance = super().__new__(cls)
            return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        cache_dir = Python.getPlatform().getApplication().getCacheDir().getAbsolutePath()
        path = os.path.join(os.path.join(cache_dir, 'py'),  f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        # lxml when it loaded (faster and more forgiving on broken markup), otherwise a
        # small stdlib html.parser tree that answers the same absolute-path xpath().
        # Spiders that do not call html() never touch either.
        if etree is not None:
            return etree.HTML(content)
        from html.parser import HTMLParser

        class _Node:

            def __init__(self, tag, attrib):
                self.tag = tag
                self.attrib = attrib
                self.text = ''
                self.children = []

            def __iter__(self):
                return iter(self.children)

            def xpath(self, expr):
                parts = [p for p in expr.split('/') if p and p != '.']
                nodes = [self]
                for part in parts:
                    if part.startswith('@'):
                        name = part[1:]
                        return [n.attrib.get(name, '') for n in nodes]
                    if part == 'text()':
                        return [n.text for n in nodes]
                    nodes = [c for n in nodes for c in n.children if c.tag == part]
                return nodes

        class _Tree(HTMLParser):

            def __init__(self):
                super().__init__(convert_charrefs=True)
                self.root = _Node('[document]', {})
                self.stack = [self.root]

            def handle_starttag(self, tag, attrs):
                node = _Node(tag, dict(attrs))
                self.stack[-1].children.append(node)
                self.stack.append(node)

            def handle_startendtag(self, tag, attrs):
                self.stack[-1].children.append(_Node(tag, dict(attrs)))

            def handle_endtag(self, tag):
                if len(self.stack) > 1:
                    self.stack.pop()

            def handle_data(self, data):
                self.stack[-1].text += data

        tree = _Tree()
        tree.feed(content)
        tree.close()
        return tree.root

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)
    
    def getProxyUrl(self, local=True):
        return f'{Proxy.getUrl(local)}?do=py'

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f'{msg}')

    def getCache(self, key):
        value = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=get&key={key}', timeout=5).text
        if len(value) > 0:
            if value.startswith('{') and value.endswith('}') or value.startswith('[') and value.endswith(']'):
                value = json.loads(value)
                if type(value) == dict:
                    if not 'expiresAt' in value or value['expiresAt'] >= int(time.time()):
                        return value
                    else:
                        self.delCache(key)
                        return None
            return value
        else:
            return None

    def setCache(self, key, value):
        if type(value) in [int, float]:
            value = str(value)
        if len(value) > 0:
            if type(value) == dict or type(value) == list:
                value = json.dumps(value, ensure_ascii=False)
        r = self.post(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=set&key={key}', data={"value": value}, timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'

    def delCache(self, key):
        r = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=del&key={key}', timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'

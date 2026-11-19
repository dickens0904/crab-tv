package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile DnsOverHttps doh;
    /** 蜂窝网络(5G/4G)下把 IPv4 排在前面：运营商 IPv6 到部分 CDN 路径质量差，先连 AAAA 会明显拖慢起播。 */
    private volatile boolean preferIpv4;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        HttpUrl url = HttpUrl.parse(item.getUrl());
        this.doh = url == null ? null : new DnsOverHttps.Builder().client(new OkHttpClient()).url(url).bootstrapDnsHosts(item.getHosts()).build();
        this.supplier = null;
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
    }

    public void clear() {
        map.clear();
    }

    public void setPreferIpv4(boolean preferIpv4) {
        this.preferIpv4 = preferIpv4;
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Supplier<Doh> supplier = this.supplier;
        if (supplier != null) initDoh(supplier);
        List<InetAddress> list = (doh != null ? doh : Dns.SYSTEM).lookup(get(hostname));
        if (!preferIpv4 || list.size() < 2) return list;
        List<InetAddress> v4 = new ArrayList<>(list.size());
        List<InetAddress> rest = new ArrayList<>(list.size());
        for (InetAddress address : list) {
            if (address instanceof Inet4Address) v4.add(address);
            else rest.add(address);
        }
        // 只有双栈时才重排；纯 AAAA 的域名原样返回，避免误伤只支持 IPv6 的源
        if (v4.isEmpty() || rest.isEmpty()) return list;
        v4.addAll(rest);
        return v4;
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        setDoh(supplier.get());
    }
}

package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.catvod.bean.Header;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import okhttp3.Interceptor;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class ResponseInterceptor implements Interceptor {

    private final List<Header> headers;
    private final ConcurrentHashMap<String, String> redirectMap;

    public ResponseInterceptor() {
        headers = new CopyOnWriteArrayList<>();
        redirectMap = new ConcurrentHashMap<>();
    }

    public void addAll(List<Header> items) {
        headers.addAll(items);
    }

    public void clear() {
        headers.clear();
        redirectMap.clear();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = check(chain.request());
        Response response = chain.proceed(request);
        String encoding = response.header(HttpHeaders.CONTENT_ENCODING);
        if ("deflate".equalsIgnoreCase(encoding)) return deflate(response);
        if ("gzip".equalsIgnoreCase(encoding)) return gzip(response);
        if (response.code() == 406 && redirectMap.containsKey(request.url().toString())) return redirect(request, response);
        if (response.code() == 302 && response.header(HttpHeaders.LOCATION) != null) redirectMap.put(response.header(HttpHeaders.LOCATION), request.url().toString());
        return response;
    }

    private Request check(Request request) {
        String host = request.url().host();
        Request.Builder builder = request.newBuilder();
        for (Header item : headers) if (Util.containOrMatch(host, item.getHost())) Json.toMap(item.getHeader()).forEach(builder::header);
        return builder.build();
    }

    private Response redirect(Request request, Response response) {
        return new Response.Builder().request(request).protocol(response.protocol()).code(302).message("Found").header(HttpHeaders.LOCATION, redirectMap.get(request.url().toString())).build();
    }

    private Response deflate(Response response) throws IOException {
        byte[] raw = response.body().bytes();
        InputStream is = new InflaterInputStream(new java.io.ByteArrayInputStream(raw), new Inflater(true));
        return rebuild(response, is);
    }

    private Response gzip(Response response) throws IOException {
        // 先完整读出原始字节再解压：网络拦截器若直接读 body 流会消费掉缓存写入用的流，
        // 导致 304 复用缓存时读到空/损坏内容。读成字节数组后解压，缓存流不被触碰。
        byte[] raw = response.body().bytes();
        InputStream is = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
        return rebuild(response, is);
    }

    /** 解压后重建响应：移除 Content-Encoding，避免 OkHttp 桥接拦截器对已解压内容二次解压。 */
    private Response rebuild(Response response, InputStream is) {
        Headers.Builder hb = new Headers.Builder();
        for (int i = 0; i < response.headers().size(); i++) {
            String name = response.headers().name(i);
            if (name.equalsIgnoreCase("Content-Encoding")) continue;
            hb.add(name, response.headers().value(i));
        }
        return response.newBuilder().headers(hb.build()).body(getBody(response, is)).build();
    }

    private ResponseBody getBody(Response response, InputStream is) {
        return new ResponseBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return response.body().contentType();
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @NonNull
            @Override
            public BufferedSource source() {
                return Okio.buffer(Okio.source(is));
            }
        };
    }
}

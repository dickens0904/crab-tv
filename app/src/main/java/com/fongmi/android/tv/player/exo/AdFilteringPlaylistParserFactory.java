package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 在 HLS playlist 解析前做一次文本过滤（{@link HlsAdFilter}）。
 *
 * <p>放在解析前而不是解析后重建 {@code HlsMediaPlaylist}，是因为 media3 的 playlist 对象
 * 全是不可变的，重建要手工填十几个字段（版本升级极易踩空）；而 m3u8 本身就是纯文本，
 * 改文本再交给官方解析器最稳。master playlist（多码率清单）原样透传，过滤只发生在 media playlist。
 */
final class AdFilteringPlaylistParserFactory implements HlsPlaylistParserFactory {

    @NonNull
    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return new FilteringParser(new HlsPlaylistParser());
    }

    @NonNull
    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(@NonNull HlsMultivariantPlaylist multivariant, @NonNull HlsMediaPlaylist media) {
        return new FilteringParser(new HlsPlaylistParser(multivariant, media));
    }

    private static final class FilteringParser implements ParsingLoadable.Parser<HlsPlaylist> {

        private final HlsPlaylistParser delegate;

        FilteringParser(HlsPlaylistParser delegate) {
            this.delegate = delegate;
        }

        @NonNull
        @Override
        public HlsPlaylist parse(@NonNull Uri uri, @NonNull InputStream input) throws IOException {
            byte[] raw = readAll(input);
            String text = new String(raw, StandardCharsets.UTF_8);
            String filtered = HlsAdFilter.filter(text, uri.toString());
            if (filtered == text) return delegate.parse(uri, new ByteArrayInputStream(raw));
            byte[] bytes = filtered.getBytes(StandardCharsets.UTF_8);
            return delegate.parse(uri, new ByteArrayInputStream(bytes));
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}

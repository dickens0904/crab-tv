package com.fongmi.android.tv.player.effect.audio;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;

/**
 * 多声道 → 立体声真降混（ExoPlayer 路径）。
 *
 * <p>为什么基座还需要它：基座原有 {@link AudioEffectProcessor#applyStereo} 只是把
 * 第 3..N 声道<b>清零</b>，{@code onConfigure} 原样返回输入的 AudioFormat，
 * 输出仍是 6/8 声道。对只有 2.0 输出的机顶盒来说，AudioTrack 依旧按 6ch 打开，
 * 要么创建失败要么静音——那不是降混。这里真正把输出声道数降成 2。
 *
 * <p>矩阵复用 {@link DownmixMatrix}（与 IJK 侧 ffmpeg 滤镜共享同一份定义），
 * 采用 ITU-R BS.775 风格权重，输出前做一次软限幅，避免叠加后数字削波。
 *
 * <p>模式（沿用蟹老板TV 语义）：
 * <ul>
 *   <li>{@link #MODE_OFF} 关闭：完全不参与，音频链路零开销</li>
 *   <li>{@link #MODE_AUTO} 自动（默认）：仅当输入 &gt; 2 声道时降混</li>
 *   <li>{@link #MODE_FORCE} 强制：多声道一律降混</li>
 * </ul>
 *
 * <p>直接实现 {@link AudioProcessor} 而不用 {@code BaseAudioProcessor}：需要自己控制
 * 跨 buffer 边界的输入累积与输出队列，保证不丢帧（沿用蟹老板TV 的做法）。
 */
public final class DownmixAudioProcessor implements AudioProcessor {

    public static final int MODE_OFF = 0;
    public static final int MODE_AUTO = 1;
    public static final int MODE_FORCE = 2;

    /** 跨 buffer 累积的初始容量，足够放下一个大的 AudioTrack 突发。 */
    private static final int INITIAL_CAPACITY = 192 * 1024;

    private final ModeProvider modeProvider;

    public interface ModeProvider {
        int getMode();
    }

    private int inChannels;
    private int bytesPerSample;
    private int bytesPerFrame;
    private boolean passthrough;

    private ByteBuffer pending = newBuffer(INITIAL_CAPACITY);
    private ByteBuffer outBuffer;
    private boolean endOfStream;

    public DownmixAudioProcessor(ModeProvider modeProvider) {
        this.modeProvider = modeProvider;
    }

    @Override
    public AudioFormat configure(AudioFormat input) {
        endOfStream = false;
        int mode = Math.clamp(modeProvider == null ? MODE_AUTO : modeProvider.getMode(), MODE_OFF, MODE_FORCE);

        boolean wantStereo = switch (mode) {
            case MODE_OFF -> false;
            case MODE_FORCE -> true;
            default -> input.channelCount > 2;
        };
        boolean supported = DownmixMatrix.isSupported(input.channelCount)
                && (input.encoding == C.ENCODING_PCM_16BIT || input.encoding == C.ENCODING_PCM_FLOAT);

        passthrough = !wantStereo || !supported;
        inChannels = input.channelCount;
        bytesPerSample = input.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        bytesPerFrame = passthrough ? 0 : inChannels * bytesPerSample;

        clearBuffers();
        return passthrough ? input : new AudioFormat(input.sampleRate, 2, input.encoding);
    }

    @Override
    public boolean isActive() {
        return !passthrough;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        if (passthrough) {
            // 不处理时必须原样把数据交给下游
            int size = input.remaining();
            if (size == 0) return;
            outBuffer = obtain(size).put(input);
            outBuffer.flip();
            return;
        }
        if (pending.remaining() < input.remaining()) growPending(input.remaining());
        pending.put(input);
        drain(false);
    }

    @Override
    public void queueEndOfStream() {
        // 把不足一帧的尾巴也送出去，别丢掉
        if (!passthrough && pending.position() > 0) drain(true);
        endOfStream = true;
    }

    @Override
    public ByteBuffer getOutput() {
        return outBuffer == null ? AudioProcessor.EMPTY_BUFFER : outBuffer;
    }

    @Override
    public boolean isEnded() {
        return endOfStream && (outBuffer == null || !outBuffer.hasRemaining());
    }

    @Override
    public void flush() {
        clearBuffers();
        endOfStream = false;
    }

    @Override
    public void reset() {
        clearBuffers();
    }

    // ------------------------------------------------------------------

    private ByteBuffer obtain(int size) {
        ByteBuffer buf = outBuffer;
        if (buf == null || buf.capacity() < size) buf = newBuffer(Math.max(size, 64 * 1024));
        buf.clear();
        return buf;
    }

    private void growPending(int extra) {
        int need = pending.position() + extra;
        ByteBuffer grown = newBuffer(Math.max(need, pending.capacity() * 2));
        pending.flip();
        grown.put(pending);
        grown.flip();
        pending = grown;
    }

    private static ByteBuffer newBuffer(int size) {
        return ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    private void drain(boolean allowPartial) {
        pending.flip();
        int available = pending.remaining();
        int frames = available / bytesPerFrame;
        int leftover = available - frames * bytesPerFrame;
        if (frames == 0 && !(allowPartial && leftover > 0)) {
            pending.compact();
            return;
        }

        // allowPartial 时把零头也按整帧处理（尾部补零），避免丢掉最后一小段。
        float[] weightsL = DownmixMatrix.left(inChannels);
        float[] weightsR = DownmixMatrix.right(inChannels);
        ByteBuffer out = obtain(frames * 2 * bytesPerSample);
        float[] inCh = new float[inChannels];

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < inChannels; c++) {
                inCh[c] = bytesPerSample == 4 ? pending.getFloat() : pending.getShort() / 32768f;
            }
            float left = softClip(dot(inCh, weightsL));
            float right = softClip(dot(inCh, weightsR));
            if (bytesPerSample == 4) {
                out.putFloat(left);
                out.putFloat(right);
            } else {
                out.putShort(toPcm16(left));
                out.putShort(toPcm16(right));
            }
        }
        out.flip();
        outBuffer = out;

        if (leftover > 0) {
            ByteBuffer tail = pending.duplicate();
            tail.position(tail.limit() - leftover);
            tail.limit(tail.limit());
            ByteBuffer slice = tail.slice();
            pending.clear();
            pending.put(slice);
        } else {
            pending.clear();
        }
    }

    private static float dot(float[] src, float[] weights) {
        float acc = 0f;
        for (int c = 0; c < src.length; c++) {
            float k = weights[c];
            if (k != 0f) acc += src[c] * k;
        }
        return Float.isFinite(acc) ? acc : 0f;
    }

    /** 0.85 以下线性通过，之上平滑压到 1.0，替代硬削波（与蟹老板TV softClipFloat 同形）。 */
    private static float softClip(float v) {
        float a = Math.abs(v);
        if (a <= 0.85f) return v;
        float shaped = 0.85f + (1f - (float) Math.exp(-3f * (a - 0.85f) / 0.15f)) * 0.15f;
        return v < 0f ? -shaped : shaped;
    }

    private static short toPcm16(float sample) {
        float clamped = Math.clamp(sample, -1f, 1f);
        return clamped <= -1f ? Short.MIN_VALUE : (short) Math.round(clamped * Short.MAX_VALUE);
    }

    private void clearBuffers() {
        pending.clear();
        outBuffer = null;
    }

    @NonNull
    @Override
    public String toString() {
        return "DownmixAudioProcessor{mode=" + (modeProvider == null ? MODE_AUTO : modeProvider.getMode())
                + ", in=" + inChannels + "ch, active=" + isActive() + "}";
    }
}
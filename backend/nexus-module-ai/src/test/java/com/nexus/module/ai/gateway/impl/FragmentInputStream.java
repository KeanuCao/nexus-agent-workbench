package com.nexus.module.ai.gateway.impl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试用输入流：把一段文本按<b>指定的片段序列</b>逐次交付（每次 {@code read} 最多给一个片段）。
 *
 * <h2>为什么需要它</h2>
 * 真实网络里"一个 NDJSON 行 / 一个 SSE 事件被 TCP 切成两半"是<b>必然而非偶然</b>
 * （设计 §9 风险 4：一个 JSON 对象被劈成两半 → 解析失败或丢字）。
 * 而 {@code new ByteArrayInputStream(text)} 会让读取方一次拿全 —— 边界 bug 就永远测不出来。
 *
 * <p>本类把"分片边界"变成可断言的东西：调用方按自己指定的位置切开文本，解析代码<b>必须</b>
 * 自己把半截行拼起来（实现里这件事由 {@code BufferedReader.readLine()} 完成，
 * 它本身就是"累积到行尾才算一行"）。
 *
 * <p>顺带一个真实场景：{@code InputStreamReader} 会按自己的缓冲区大小来调
 * {@link #read(byte[], int, int)}，而本类每次最多交付一个片段，正好模拟"每次网络读只拿到一部分"。
 *
 * @author nexus
 */
final class FragmentInputStream extends InputStream {

    /** 每个片段一个 byte[]（构造期就转成 UTF-8 字节：中文一个字符 3 字节，切在中间才是最狠的边界）。 */
    private final byte[][] fragments;

    private int fragmentIndex;

    private int offsetInFragment;

    /**
     * @param fragments 片段序列（按顺序交付；空片段会被跳过）
     */
    FragmentInputStream(String... fragments) {
        this.fragments = new byte[fragments.length][];
        for (int i = 0; i < fragments.length; i++) {
            this.fragments[i] = fragments[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 直接按字节切片交付 —— 用来构造 {@code String} 表达不了的边界：
     * <b>把一个多字节字符拆到两次读取里</b>（如「你」的三字节 {@code E4 BD A0} 拆成 1 + 2）。
     *
     * <p>这条边界只有字节入参才能表达（{@code String...} 做不到"半个汉字"），
     * 而它恰好是最隐蔽的坏法：解码没拼回来的话得到 U+FFFD <b>且不抛异常</b>。
     *
     * @param fragments 字节片段序列
     */
    FragmentInputStream(byte[]... fragments) {
        this.fragments = new byte[fragments.length][];
        for (int i = 0; i < fragments.length; i++) {
            this.fragments[i] = fragments[i].clone();
        }
    }

    @Override
    public int read() {
        if (!moveToReadableFragment()) {
            return -1;
        }
        return fragments[fragmentIndex][offsetInFragment++] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        if (!moveToReadableFragment()) {
            return -1;
        }
        byte[] current = fragments[fragmentIndex];
        int available = current.length - offsetInFragment;
        int toCopy = Math.min(available, length);
        System.arraycopy(current, offsetInFragment, target, offset, toCopy);
        offsetInFragment += toCopy;
        return toCopy;
    }

    /**
     * 跳到下一个"还有字节"的片段。
     *
     * @return {@code false} 表示流已结束
     */
    private boolean moveToReadableFragment() {
        while (fragmentIndex < fragments.length && offsetInFragment >= fragments[fragmentIndex].length) {
            fragmentIndex++;
            offsetInFragment = 0;
        }
        return fragmentIndex < fragments.length;
    }
}

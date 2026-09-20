#!/usr/bin/env python3
# =============================================================================
# qa/scripts/lib/tc02-stamp-lines.py
#   TC-02（阶段2 流式对话）用例脚本共用的**机械动作**过滤器：
#   把 stdin 上的原始字节流**逐行打印**（每行前缀一个到达时刻），跑完再打一段机械统计。
#
# 谁在用它：qa/scripts/tc02-chat-stream.sh（`curl -sN -i … | python3 本文件`）
#
# 为什么需要它（TC-02 的 2.3-1 / 2.3-2 判「可见增量分片」）：
#   "分片是随打随出还是一次性倾泻"**只能从到达时刻上看出来** —— 响应体本身完全一样。
#   所以这一层的唯一职责就是：给每一行打一个时刻戳，并把"首个 data 行 → 末个 data 行"的
#   跨度算出来。同一时刻倾泻（跨度 ≈ 0）与跨数秒逐帧到达，在读数上一望即分。
#
# ⚠️ 判据不藏脚本里（qa/README.md 铁律 #2）：
#   本文件**只做机械动作与机械统计** —— 打时刻、数事件、数替换字符、机械拼接 delta 正文。
#   它**不打印 PASS/FAIL**、不判断"够不够增量"、不判断"有没有乱码"。
#   判据（跨度 ≥ 多少、U+FFFD 必须为 0 等）一律写在 docs/test-cases/TC-02.md 的「预期」栏。
#
# ⚠️ 为什么要 `--self-test`：这台机器上**统计本身也可能坏**。
#   若"U+FFFD 计数"这一段静默失效，它会稳定地打出 `0`，而人会把那个 `0` 读成"没有乱码"
#   —— 正是 TC-01-1.2-4 记下的头号假通过形态（取值器坏掉 ⇒ 两边都空 ⇒ 看着反而一致）。
#   所以 `--self-test` 用一份**故意含替换字符**的样本跑同一条统计路径：必须数得出 1 个，
#   且 delta 拼接、事件计数、时刻戳三样都得对。它检的是**本过滤器**，与任何 TC 判据无关。
#
# 退出码：0 = 正常跑完；130 = 读取中被 Ctrl-C 打断（仍会打印已到达部分的统计）
# =============================================================================

import io
import json
import sys
import time

MS = 1000


def stamp():
    """到达时刻，形如 18:12:03.412（毫秒精度）"""
    now = time.time()
    return time.strftime("%H:%M:%S", time.localtime(now)) + ".%03d" % int((now % 1) * MS)


def new_state():
    return {
        "chunks": [],       # [(kind, stamp)]，kind ∈ {"event", "data", "other"}
        "event_counts": {},  # 事件名 → 次数
        "delta_parts": [],   # delta 帧的 data.content，按到达顺序机械拼接
        "raw": bytearray(),  # 原始字节（含响应头），供严格 UTF-8 解码用
    }


def feed_line(state, line: bytes, moment: str):
    """吃一行原始字节：记账 + 返回给人看的文本（原样，只去行尾）"""
    state["raw"] += line
    text = line.decode("utf-8", errors="replace").rstrip("\r\n")

    if text.startswith("event:"):
        # 契约 §6.2 规则 3：取值前先剥掉至多一个前导空格（各家实现不统一）
        name = text[len("event:"):].replace(" ", "", 1).strip()
        state["event_counts"][name] = state["event_counts"].get(name, 0) + 1
        state["chunks"].append(("event", moment))
        state["last_event"] = name
    elif text.startswith("data:"):
        state["chunks"].append(("data", moment))
        payload = text[len("data:"):].replace(" ", "", 1).strip()
        try:
            obj = json.loads(payload)
        except ValueError:
            obj = None
        if state.get("last_event") == "delta" and isinstance(obj, dict):
            body = obj.get("data")
            if isinstance(body, dict) and isinstance(body.get("content"), str):
                state["delta_parts"].append(body["content"])
    else:
        state["chunks"].append(("other", moment))
        if text.strip() == "":
            state["last_event"] = None   # 空行 = 一帧结束


def print_summary(state):
    raw_bytes = bytes(state["raw"])
    print("\n--- 观察汇总（机械统计，不含任何判定）---")
    print("原始字节数 = %d" % len(raw_bytes))

    counts = state["event_counts"]
    if counts:
        print("事件计数 = " + "  ".join("%s:%d" % (k, counts[k]) for k in sorted(counts)))
    else:
        print("事件计数 = （本次一个 event 行都没有）")

    def window(kind):
        stamps = [s for (k, s) in state["chunks"] if k == kind]
        if not stamps:
            return None
        return stamps[0], stamps[-1]

    for kind, label in (("data", "data 行"), ("event", "event 行")):
        got = window(kind)
        if got is None:
            print("%s：没有" % label)
        else:
            print("%s：首个 %s → 末个 %s（共 %d 行）" % (label, got[0], got[1], len(
                [s for (k, s) in state["chunks"] if k == kind])))

    # 这一行是「可见增量分片」的读数来源：一次性倾泻时它会是 0 ms（或极小）
    stamps = [s for (k, s) in state["chunks"] if k == "data"]
    if len(stamps) >= 2:
        print("data 行首末跨度的近似值 = %s → %s（判读看时刻差，不在此处判定）" % (stamps[0], stamps[-1]))

    try:
        text = raw_bytes.decode("utf-8")
        print("严格 UTF-8 解码 = 成功")
        print("替换字符 U+FFFD 出现次数 = %d" % text.count("�"))
    except UnicodeDecodeError as ex:
        print("严格 UTF-8 解码 = **失败**（偏移 %d 处：%s）—— 上游/链路发出了非法字节" % (ex.start, ex.reason))

    joined = "".join(state["delta_parts"])
    print("delta 正文机械拼接长度 = %d 字符" % len(joined))
    print("--- delta 正文机械拼接（供与页面逐字对照；无 delta 时为空）---")
    print(joined)
    print("--- 拼接结束 ---")


def run_stream(inp):
    state = new_state()
    state["last_event"] = None
    interrupted = False
    try:
        for line in iter(inp.readline, b""):
            moment = stamp()
            text = feed_line(state, line, moment)
            print("[%s] %s" % (moment, text), flush=True)
    except KeyboardInterrupt:
        interrupted = True
        print("\n（Ctrl-C：读取被主动打断 —— 已到达的部分仍会做下面的统计）")
    except KeyboardInterrupt:  # pragma: no cover - 兼容少数实现的二次抛出
        interrupted = True
    print_summary(state)
    return 130 if interrupted else 0


def self_test():
    print("===== tc02-stamp-lines.py 工具自检（纯本地，不碰网络/容器/Redis）=====")
    print("下面这些「通过 / 不通过」说的是**本过滤器**，与任何 TC 判据无关。\n")

    sample = (
        b"HTTP/1.1 200 \r\n"
        b"Content-Type: text/event-stream;charset=UTF-8\r\n"
        b"\r\n"
        b"event: meta\n"
        b'data: {"code":0,"msg":"success","data":{"modelType":"OLLAMA","model":"qwen2.5:7b","servedBy":"user-selected"}}\n'
        b"\n"
        b"event: delta\n"
        b'data: {"code":0,"msg":"success","data":{"content":"\xe4\xbd\xa0"}}\n'
        b"\n"
        b"event: delta\n"
        b'data: {"code":0,"msg":"success","data":{"content":"\xef\xbf\xbd"}}\n'   # 故意塞一个 U+FFFD
        b"\n"
        b"event: done\n"
        b'data: {"code":0,"msg":"success","data":{"finishReason":"stop","deltaCount":2,"durationMs":842}}\n'
        b"\n"
        b"event: delta\n"
        b'data: {"code":0,"msg":"success","data":{"content":"tail-without-blank-line"}}\n'  # 无空行收尾
    )
    state = new_state()
    state["last_event"] = None
    for line in sample.split(b"\n"):
        text = feed_line(state, line + b"\n", "00:00:00.000")
    counts = state["event_counts"]
    joined = "".join(state["delta_parts"])

    checks = [
        ("事件计数 meta", counts.get("meta"), 1),
        ("事件计数 delta（含最后一行无空行收尾的那一帧）", counts.get("delta"), 3),
        ("事件计数 done", counts.get("done"), 1),
        ("delta 正文拼接", joined, "你�" + "tail-without-blank-line"),
        ("替换字符能被数出来（故意样本里正好 1 个）", bytes(state["raw"]).decode("utf-8").count("�"), 1),
    ]
    failed = 0
    for label, actual, expected in checks:
        if actual == expected:
            print("  %s：通过" % label)
        else:
            failed += 1
            print("  %s：**不通过**（期望 %r，实际 %r）" % (label, expected, actual))

    # 时刻戳：同一条路径连打两次必须给出形如 HH:MM:SS.mmm 的串（不判时间本身，只判形状）
    one, two = stamp(), stamp()
    shape_ok = all(len(s) == 12 and s[8] == "." and s[:2].isdigit() for s in (one, two))
    if shape_ok:
        print("  时刻戳形状（HH:MM:SS.mmm）：通过（%s / %s）" % (one, two))
    else:
        failed += 1
        print("  时刻戳形状（HH:MM:SS.mmm）：**不通过**（%s / %s）" % (one, two))

    # 非法字节：必须报"解码失败"而不是静默算成 0 个替换字符
    bad = b'data: {"content":"\xff\xfe"}\n'
    try:
        bad.decode("utf-8")
        failed += 1
        print("  非法字节样本：**不通过**（居然解码成功了）")
    except UnicodeDecodeError:
        print("  非法字节样本：通过（严格解码按预期失败，不会被算成 0 个替换字符）")

    print("\n===== 工具自检结束：%s =====" % ("全部通过" if failed == 0 else "有 %d 项不通过" % failed))
    print("（提醒：这些结论说的是过滤器本身；TC-02 的判据在 docs/test-cases/TC-02.md 的「预期」栏）")
    return 0 if failed == 0 else 1


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        return self_test()
    if len(sys.argv) > 1:
        print("错误：未知参数 %s（只支持 --self-test）" % sys.argv[1], file=sys.stderr)
        return 2
    return run_stream(sys.stdin.buffer)


if __name__ == "__main__":
    sys.exit(main())

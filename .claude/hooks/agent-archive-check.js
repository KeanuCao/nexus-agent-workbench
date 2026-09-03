#!/usr/bin/env node
/**
 * Stop hook：检测耗时 >= 2 分钟的子代理（Agent）任务，提醒主会话将其留档到 docs/agent-log/。
 * - 通过 .claude/hooks/agent-archive-state.json 记录已提醒过的 task-id，避免重复提醒。
 * - 无待留档任务时静默退出（stdout 无输出、exit 0）。
 */
const fs = require('fs');
const path = require('path');

const MIN_DURATION_MS = 2 * 60 * 1000; // 2 分钟阈值

function readStdin() {
  try {
    return JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
  } catch (e) {
    return {};
  }
}

function main() {
  const input = readStdin();
  const transcriptPath = input.transcript_path || '';
  const cwd = input.cwd || process.env.CLAUDE_PROJECT_DIR || '';
  if (!transcriptPath || !cwd || !fs.existsSync(transcriptPath)) return;

  const stateFile = path.join(cwd, '.claude', 'hooks', 'agent-archive-state.json');
  let state = { reminded: [] };
  try {
    state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  } catch (e) {
    state = { reminded: [] };
  }

  // 解析 transcript：Agent 工具调用（tool_use_id -> 描述/开始时间）与 task-notification（含 task-id/status/结束时间）
  const toolUses = {};
  const notifs = [];
  let lines;
  try {
    lines = fs.readFileSync(transcriptPath, 'utf8').split('\n').filter(Boolean);
  } catch (e) {
    return;
  }
  for (const line of lines) {
    let o;
    try {
      o = JSON.parse(line);
    } catch (e) {
      continue;
    }
    const ts = Date.parse((o && o.timestamp) || '') || 0;
    if (o && o.message && Array.isArray(o.message.content)) {
      for (const b of o.message.content) {
        if (b.type === 'tool_use' && b.name === 'Agent' && b.id && ts) {
          const desc = (b.input && b.input.description) || '(无描述)';
          toolUses[b.id] = { desc, ts };
        }
      }
    }
    const m = /<task-notification>([\s\S]*?)<\/task-notification>/.exec(line);
    if (!m || !ts) continue;
    const body = m[1];
    const taskId = (/<task-id>([^<]+)<\/task-id>/.exec(body) || [])[1];
    const toolUseId = (/<tool-use-id>([^<]+)<\/tool-use-id>/.exec(body) || [])[1];
    const status = (/<status>([^<]+)<\/status>/.exec(body) || [])[1];
    if (taskId && toolUseId) notifs.push({ taskId, toolUseId, status, ts });
  }

  // 找已完成、耗时达标、且未提醒过的任务
  const pending = [];
  const seen = new Set();
  for (const n of notifs) {
    if (n.status !== 'completed' || seen.has(n.taskId)) continue;
    seen.add(n.taskId);
    const tu = toolUses[n.toolUseId];
    if (!tu) continue;
    const durMs = n.ts - tu.ts;
    if (durMs < MIN_DURATION_MS || state.reminded.includes(n.taskId)) continue;
    pending.push({ taskId: n.taskId, desc: tu.desc, minutes: Math.round(durMs / 60000) });
  }
  if (pending.length === 0) return;

  // 记录已提醒，避免重复
  for (const p of pending) state.reminded.push(p.taskId);
  fs.mkdirSync(path.dirname(stateFile), { recursive: true });
  fs.writeFileSync(stateFile, JSON.stringify(state, null, 2));

  const list = pending
    .map((p) => `- 任务 ${p.taskId}（${p.desc}）：耗时约 ${p.minutes} 分钟`)
    .join('\n');
  console.log(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'Stop',
        additionalContext:
          '[agent-archive hook] 以下子代理任务耗时超过 2 分钟且尚未留档。请为每个任务在 docs/agent-log/ 下创建留档文件（命名与格式见 docs/agent-log/README.md，需记录：做了什么 / 遇到的坑 / 下次怎么改进）：\n' +
          list,
      },
    })
  );
}

main();

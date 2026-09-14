# [AI发现] compose 注释里的 `${model}` 触发变量告警

- **发现时间**：2026-09-15 ｜ **发现场景**：核 TC-00-0.1-7 时顺手跑 compose 命令 ｜ **状态**：待定（未修）

## 现象

任何 `docker compose ...` 命令（哪怕只是 `docker compose config --services`）都会先打印一行告警：

```
time="2026-09-15T07:02:02+08:00" level=warning msg="The \"model\" variable is not set. Defaulting to a blank string."
```

## 定位

`docker-compose/docker-compose.yml:110-111` 的**注释**里写了 `${model}`：

```
        #   （如 nomic-embed-text:latest），而 model 变量不带 tag
        #   → 原写法 `awk '{print $1}' | grep -qx "${model}"` 要求整行相等，**必然匹配不上**
```

原因是 Compose 在解析 YAML **之前**先对整份文件做变量插值，**注释里的 `${...}` 同样会被插值** —— 所以这条本意是「记录一个历史 bug」的注释，每次都在制造一条新告警。

## 影响

纯噪声，不影响功能（那个变量只存在于注释里）。但会污染所有 compose 命令的输出，排查问题时容易被当成线索；也会让 `up.sh` 等脚本的日志显得不干净。

## 建议方向（未拍板）

按同文件 `:116` 的既有写法把 `$` 转义成 `$${model}`，或改写措辞去掉 `${}`（例如「原写法用了 awk + grep -qx，要求整行相等，必然匹配不上」）。

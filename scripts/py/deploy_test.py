#!/usr/bin/env python3
# =============================================================================
# deploy_test.py —— 测试档**薄包装**（task.6 / 5.1 交付物；设计 05 §5.1-(b)）
#
# 唯一职责：把「deploy.py 的 test 档参数」组装好并原样交给核心。
#
# ⚠️ 硬规则（设计 05 §5.1，(b) 选项的代价栏）：本文件**只组装参数、不含任何判据** ——
#    任何检查 / 断言 / 端口表都属于 deploy.py 的核心；写在这里就会变成"判据第二份"，必然漂移
#    （本项目"判据只写一份"的既有教训：probe.sh 共享探针库、up.sh / check-env / check-health 三脚本不各写一份）。
#
# 用法（测试发行版内；CI 由 5.2 的 workflow 调用）：
#   python3 scripts/py/deploy_test.py --ci --summary-json /tmp/deploy-summary.json \
#       --ref refs/pull/123/head --expect-sha <PR head SHA 前缀>
# 其余参数（--rebuild / --no-build / --login-user / --login-password …）原样透传，
# 语义与 deploy.py --profile test 完全一致（本文件不新增任何参数）。
# =============================================================================
from __future__ import annotations

import sys
from pathlib import Path

# 同目录导入：直接以脚本方式运行时（python3 scripts/py/deploy_test.py），
# sys.path[0] 本就是本目录；这里显式再加一次，兼容"从别处 import"的调用方式。
sys.path.insert(0, str(Path(__file__).resolve().parent))

import deploy  # noqa: E402  （同目录模块；核心入口是 deploy.main）

if __name__ == "__main__":
    sys.exit(deploy.main(["--profile", "test", *sys.argv[1:]]))

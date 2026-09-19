#!/bin/sh
# 无 GitHub Token 环境下的 CI 操作脚本（仅依赖 SSH + 公开报告分支）
#
# 背景：细粒度 token 权限受限，且用户可能随时撤销。
#      本脚本把所有 CI 操作收敛到「SSH + ci-failure-report 分支」两条不依赖 token 的通道。
#
# 用法：
#   sh scripts/ci-notoken.sh trigger        # 打 tag 触发自签正式版构建
#   sh scripts/ci-notoken.sh wait <秒数>     # 轮询等待构建并取失败报告
#   sh scripts/ci-notoken.sh report         # 拉取最近一次失败报告
#   sh scripts/ci-notoken.sh remote         # 查看远端 main / tag / 报告分支状态
#
set -e

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_DIR"

cmd="$1"

# 正式版失败报告在 ci-failure-report，-self 自签构建失败报告在 ci-release-self-report；
# 此前 wait/report 只盯前者，-self 构建失败永远等不到报告。
REPORT_BRANCHES="ci-failure-report ci-release-self-report"

case "$cmd" in
  remote)
    echo "=== SSH 连通性 ==="
    ssh -T git@github.com 2>&1 | head -2 || true
    echo
    echo "=== 远端引用 ==="
    git ls-remote origin main $REPORT_BRANCHES 2>&1
    echo
    echo "=== 本地 HEAD ==="
    git log --oneline -1
    ;;

  trigger)
    ver="$2"
    [ -z "$ver" ] && { echo "用法: sh scripts/ci-notoken.sh trigger <tag名，如 v0.13.4-self>"; exit 1; }
    echo "=== 打 tag $ver 并推送（走 SSH，不需要 token/Actions 权限）==="
    # 允许重打：先删远端旧 tag
    git push origin ":refs/tags/$ver" 2>/dev/null || true
    git tag -d "$ver" 2>/dev/null || true
    git -c user.name=taixu-agent -c user.email=agent@taixu.local \
        tag -a "$ver" -m "TaiXu $ver self-signed build"
    git push origin "$ver"
    echo "✅ 已推送 tag，CI 会自动开始构建（self-signed 工作流）"
    ;;

  report)
    echo "=== 拉取最近一次失败报告 ==="
    for br in $REPORT_BRANCHES; do
      for i in 1 2 3; do
        if git fetch origin "$br" 2>/dev/null; then
          if git cat-file -e "FETCH_HEAD:ci-failure-report.txt" 2>/dev/null; then
            echo "（来源分支：$br）"
            git show "FETCH_HEAD:ci-failure-report.txt" 2>/dev/null | head -60
            exit 0
          fi
        fi
        sleep 5
      done
    done
    echo "（未取到报告分支，可能目前没有失败，或分支已被删除）"
    ;;

  wait)
    max="${2:-600}"
    echo "=== 轮询等待构建完成（最多 ${max}s）==="
    echo "依据：报告分支 HEAD 相对本轮起点发生变化（仅在失败时推送）"
    # 记录起点 HEAD：报告分支上残留的上一次旧报告不应被误判为本次构建的结论
    base=""
    for br in $REPORT_BRANCHES; do
      b=$(git ls-remote origin "$br" 2>/dev/null | awk '{print $1}')
      base="$base $b"
    done
    start=$(date +%s)
    while [ $(( $(date +%s) - start )) -lt "$max" ]; do
      now=$(date +%H:%M:%S)
      changed=""
      for br in $REPORT_BRANCHES; do
        r=$(git ls-remote origin "$br" 2>/dev/null | awk '{print $1}')
        case " $base " in
          *" $r "*) ;;       # 与起点一致（含同为空）
          *) changed="$br" ;;
        esac
      done
      echo "[$now] 报告分支 HEAD: $(git ls-remote origin $REPORT_BRANCHES 2>/dev/null | awk '{print $1}' | tr '\n' ' ')"
      if [ -n "$changed" ]; then
        echo "=== 检测到报告分支已更新（$changed），拉取内容 ==="
        git fetch origin "$changed" 2>/dev/null || true
        git show "FETCH_HEAD:ci-failure-report.txt" 2>/dev/null | head -60
        exit 0
      fi
      sleep 45
    done
    echo "（等待超时。若构建成功，不会产生报告分支——这本身是好消息）"
    ;;

  *)
    echo "无 token CI 工具"
    echo
    echo "  remote          查看远端/本地状态"
    echo "  trigger <tag>   打 tag 触发构建"
    echo "  report          拉取最近失败报告"
    echo "  wait [秒数]      轮询等待"
    ;;

esac

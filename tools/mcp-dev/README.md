# 纸间课表开发后端 MCP（zhijian-dev）

把本仓库的开发动作固定成 MCP 工具，供 AI 助手（DSH / Claude Desktop / 任何 MCP 客户端）直接调用。
纯 Node 实现，零第三方依赖，stdio 传输，逐行 JSON-RPC 2.0。

## 为什么不是「再写一堆 shell 脚本」

本仓库有几个只有长期待在这里的人才知道的坑，工具化的价值就是让它们不靠记忆：

| 坑 | 工具怎么处理 |
| --- | --- |
| 路径含中文（D:\课程表）导致 Gradle 测试 Worker 读 @argfile 编码错乱 | TEST 有 direct 通道（python 直起 JVM）；auto 命中编码失败特征时自动回落并说明原因 |
| Gradle 任务 UP-TO-DATE 时退出码 0，但一条测试都没跑 | TEST 读 JUnit XML 报告判定，报告不是本次生成的会自动 --rerun-tasks 重跑 |
| KSP 增量缓存损坏（Storage corrupted ... lookups.tab_i） | BUILD 识别该特征，自动清 app/build/kspCaches 后重跑一次 |
| applicationIdSuffix = .debug 只改包名，Activity 仍在 namespace 下 | DEVICE launch 用设备侧 resolve-activity 解析真实组件，不拼字符串 |
| ktlint/detekt 有 baseline，控制台输出带 ANSI 色码且任务名与报告目录不同名 | LINT 读报告文件、按任务族匹配、把「存量」与「新增」分开统计 |
| 真机联调要先 `adb reverse`，但它是 adb 子命令、走 shell 白名单永远进不去 | DEVICE reverse 直接管 add/list/remove，mock-jw 联调不用手敲 |
| `dumpsys gfxinfo` 只吐一大段文本，卡顿率靠肉眼比对两段文本 | DEVICE perf 解析成 totalFrames/jankyPercent/p50/p90/p99，样本不足 60 帧时明确标注不可信 |
| 行宽按字节统计会把 40 个汉字的注释误判超长 | ARCH_CHECK 按字符数（Array.from）统计 |

## 工具清单（12 个）

| 工具 | 用途 |
| --- | --- |
| ENV | 环境体检：JDK / SDK / gradlew / adb 设备 / 版本号 / 编码修复是否生效 |
| MAP | 仓库结构导航：tree / packages / files / module / docs |
| BUILD | Gradle 构建：compile / debug / release / bundle / clean / custom |
| TEST | 单元测试：gradle / direct / auto，按 JUnit XML 判定 |
| LINT | ktlint + detekt：check / format / baseline，区分存量与新增 |
| ARCH_CHECK | 架构约定：包结构、单测不依赖 Android、行宽、Room schema 同步 |
| DEVICE | 真机：list / install / launch / stop / logcat / screenshot / shell / uninstall / reverse / tap / swipe / text / key / settings / perf |
| GIT | status / diff / stage（只接受显式文件）/ commit（校验提交信息）/ log / show |
| JW_SCRIPTS | scripts/index.json 维护：list / verify / sync / lint |
| DOCS_CHECK | docs 索引、相对链接、plans 与 specs 成对 |
| VERSION_BUMP | 发版清单检查 + 改版本号（不提交、不打 tag） |
| MOCK_JW | 假教务服务启停（tools/mock-jw） |

## 统一返回信封

每个工具都返回同一个结构，客户端既能直接渲染文本，也能按字段取值：

```json
{
  "ok": true,
  "summary": "构建成功（:app:assembleDebug）· 28.7s · 产物 app-debug.apk 47.63 MiB",
  "data": { "exitCode": 0, "durationMs": 28700, "artifacts": [] }
}
```

失败同样是这个结构（ok: false + 可读原因），不抛异常、不返回裸堆栈。

## 自检

```bash
node tools/mcp-dev/selftest.mjs                 # 全量：schema 子集校验 + 握手 + 只读实调
node tools/mcp-dev/selftest.mjs --skip-calls    # 只校验协议与 schema
```

自检会复刻 DSH 工具运行时的 JSON Schema 子集规则（只允许
type / oneOf / properties / required / additionalProperties / items / enum / const
加 description / title / default / examples），不合规的 schema 在接入前就会被拦下。

## 接入 DSH

在 ~/.dsh/cordis.patch.yml 末尾追加（注意必须包在 - insert: 里，否则补丁会静默跳过）：

```yaml
- insert:
    - id: mcp-zhijian-dev
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: zhijian
        transport: stdio
        command: node
        args: ['D:/课程表/tools/mcp-dev/server.mjs']
        cwd: 'D:/课程表'
        toolCallTimeoutMs: 1800000
        reconnect:
          enabled: true
          initialDelayMs: 1000
          maxDelayMs: 30000
          maxAttempts: 5
```

路径用正斜杠：YAML 里未加引号的反斜杠会被当转义序列吃掉。

接入后工具名形如 mcp__zhijian__build、mcp__zhijian__test。

## 目录结构

```
tools/mcp-dev/
├── server.mjs              # JSON-RPC 主循环（stdio，逐行）
├── selftest.mjs            # 自检（协议 + schema 子集 + 只读实调）
├── package.json
└── lib/
    ├── meta.mjs            # 服务器身份、协议版本、系统提示说明
    ├── schema.mjs          # JSON Schema 子集构造器
    ├── exec.mjs            # 子进程执行（超时收尸 / .bat 走 cmd / 输出头尾裁剪）
    ├── project.mjs         # 仓库探测（根目录、版本号、SDK、adb、python）
    └── tools/              # 12 个工具，一个文件一个域
        ├── common.mjs      # 统一信封 + 参数取值
        ├── env.mjs  map.mjs  build.mjs  test.mjs  lint.mjs
        ├── arch.mjs device.mjs  git.mjs  jw-scripts.mjs
        ├── docs.mjs  version.mjs  mock-jw.mjs
```

## 设计约定

1. **stdout 只写协议**：任何诊断日志走 stderr，否则客户端解析会炸。
2. **工具不抛异常**：失败返回 { ok: false, summary, data }，让调用方能读懂原因。
3. **写操作保守**：GIT 不做 push、VERSION_BUMP 不提交不打 tag、JW_SCRIPTS sync 默认 dryRun、
   DEVICE shell 有前缀白名单 —— 危险动作一律要显式参数。
4. **路径只在一处拼**：所有工具从 lib/project.mjs 取路径，换机器只改一处（或用
   ZHIJIAN_REPO_ROOT 环境变量覆盖，用于 worktree / 隔离验证）。

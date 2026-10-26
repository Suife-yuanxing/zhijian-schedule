# 架构决策记录（ADR）· 纸间课表

这里记录**代价大、回头贵**的技术取舍：选了 A 就基本告别 B 的那类决定。日常实现细节不要写成 ADR（放提交信息或 `plans/` 里就够）。

## 什么时候写 ADR

写：

- 改变了数据存放位置或格式（例如"要不要云同步""Room 要不要加密"）
- 引入了新的外部依赖或分发渠道（GitHub Releases、镜像源、脚本运行时）
- 定下一条影响后续所有功能的架构边界（哪些代码必须无 Android 依赖、脚本能力边界）
- 推翻了既有 ADR 的结论

不写：命名规范、UI 动效参数、单次重构、能在一个 PR 里讲清楚的事。

## 编号与文件命名

- 文件名：`NNNN-动词短语.md`，**小写英文短横线**，例如 `0001-local-first-data-architecture.md`。
- 编号：从 `0001` 起，四位数字零填充。**不重用、不跳号**；删除的决策用状态标记（Deprecated / Superseded），文件保留。
- 取号方式：看一眼本文件底部的索引表，取最大号 +1。

## 状态

| 状态 | 含义 |
| --- | --- |
| `Proposed` | 提出来了，还没拍板 |
| `Accepted` | 已拍板并落地，后续代码必须遵守 |
| `Deprecated` | 不再推荐，但历史代码还在用 |
| `Superseded by ADR-NNNN` | 被新 ADR 取代，**旧文件不改内容**，只在头部加这行指路 |

**历史条目只追加、不重写。** 结论变了就写新 ADR 并在两边互相指向——这样后来的人能看到"当时为什么那么想"。

## 模板

```markdown
# ADR-NNNN：一句话标题（写决策，不写话题）

- 状态：Accepted
- 日期：YYYY-MM-DD（决策实际发生的日期，不是写文档的日期）
- 相关文档：链接到 specs / plans / research
- 相关代码：真实文件路径

## Context
当时面对什么问题，有哪些约束（技术、时间、成本、合规）。

## Decision
我们决定做什么。用"我们决定…"的肯定句式，写清边界。

## Consequences
正面收益 + 必须接受的代价 + 后续要还的技术债。

## 备选方案
考虑过但被否掉的路径，以及否掉的具体理由。
```

## 索引

| 编号 | 决策 | 状态 | 日期 |
| --- | --- | --- | --- |
| [0001](0001-local-first-data-architecture.md) | 本地优先、无云同步的数据架构（Room + DataStore） | Accepted | 2026-10-13 |
| [0002](0002-jw-import-script-engine.md) | 教务导入采用可下载 JS 脚本引擎 + WebView 页面上下文 | Accepted | 2026-10-25 |
| [0003](0003-github-releases-self-distribution.md) | 应用内更新走 GitHub Releases 自分发，不进应用商店 | Accepted | 2026-10-13 |
| [0004](0004-concurrent-chunked-download.md) | 更新下载采用 8 路并发分片 + 镜像源链 | Accepted | 2026-10-15 |

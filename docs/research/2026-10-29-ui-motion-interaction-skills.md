# GitHub UI / 动效 / 交互设计 Skill 检索报告（v2 · 完整版）

- 检索时间：2026-10-29（Asia/Shanghai）
- 目标项目：`D:\课程表`（Android + Kotlin + Jetpack Compose 课程表应用）
- 数据来源：GitHub REST API `/search/repositories`、`/repos/{owner}/{repo}`、`/repos/{owner}/{repo}/contents`（真实调用）
- v2 变更：补齐第一轮被限流截断的动效/交互维度，并新增「Android/Compose 专线」「中文 skill 专线」两章

## 0. 检索环境（技术记录）

本机系统 DNS（172.16.1.8 / 172.16.1.88）不可达、Clash Verge 内核未运行，因此全程采用「显式 DNS 解析 + 固定 IP + SNI」直连：

- api.github.com => 20.205.243.168（200 OK）
- github.com => 20.205.243.166 / codeload.github.com => 20.205.243.165
- 解析用 DNS：223.5.5.5、8.8.8.8
- 未认证额度：core 60/h、search 10/min，第一批第 9 组后触发次级限流，v2 已分批补齐

## 1. 旗舰 UI / UX 设计 Skill

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| nextlevelbuilder/ui-ux-pro-max-skill | 142317 | 2026-10-26 | 跨平台 UI/UX 设计智能，带 CLI 与 skill.json，含中文 README |
| pbakaus/impeccable | 94149 | 2026-10-29 | polish / bolder / critique / audit + anti-slop；仓库内含 `.dsh/`，DSH 原生友好 |
| emilkowalski/skills | 51265 | 2026-10-28 | 16 个动效/交互技能（见 §3） |
| JimLiu/baoyu-design | 4904 | 2026-10-29 | 本地跑 Claude Design，出 HTML 高保真稿/原型/线框；skills：baoyu-design、claude-design、claude-dashboard、claude-design-system、claude-slides |
| Owl-Listener/designer-skills | 3155 | 2026-09-05 | 设计全链路：研究 → 系统 → UI → 交互 |
| plugin87/ux-ui-agent-skills | 3054 | 2026-10-27 | DTCG token、52 组件、WCAG 2.2 AA-AAA、138 设计系统、50 道客观门禁；含 design-systems/catalog.md |
| dominikmartn/nothing-design-skill | 2847 | 2026-04-01 | Nothing 品牌语言：单色、排版、工业感 |
| oil-oil/oil-ui | 2488 | 2026-10-29 | 多方向探索 → 并排对比 → 按真实截图迭代；单 `SKILL.md` + references |
| Ilm-Alan/frontend-design | 153 | 2026-09-26 | 八个美学锚点，每个锁定配色/字体/布局 |
| superdesigndev/superdesign-skill | 914 | 2026-08-21 | 反 AI-slop 前端设计；含 `.dsh/`、`.claude-plugin/`、`.codex-plugin/`、`.cursor-plugin/` |
| edisonmbli/refactoring-ui-skill | 313 | 2026-08-21 | 《Refactoring UI》151 条规则 + token 生成 + Tailwind 映射 |
| wilwaldon/Claude-Code-Frontend-Design-Toolkit | 1241 | 2026-04-11 | 前端设计资源工具箱（skills/plugins/MCP/CLAUDE.md 技巧） |
| arvindrk/extract-design-system | 261 | 2026-09-05 | 从任意网站抽设计 token（色/字/间距/圆角/阴影）输出 JSON + CSS 变量 |
| Anionex/agent-vision-toolkit | 1251 | 2026-10-13 | 为纯文本模型做「看图」：多图理解、前端 UI 还原比对、GUI 自动化 |

## 2. Android / Jetpack Compose 专线（与课程表最贴合）

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| new-silvermoon/awesome-android-agent-skills | 993 | 2026-07-27 | 现代 Android（Kotlin/Compose）标准化 Agent Skill 合集，兼容 Copilot/Claude/Gemini/Cursor |
| aldefy/compose-skill | 599 | 2026-07-23 | Jetpack Compose Agent Skill，结论带 androidx 源码引用；仓库自带 `verify-claims/`（自带 Gradle 工程验证） |
| skydoves/compose-performance-skills | 520 | 2026-06-16 | Compose 性能技能库：recomposition、stability、modifiers、side-effects、measurement、hot-reload、audit |
| Meet-Miyani/compose-skill | 306 | 2026-10-23 | Compose & Compose Multiplatform 技能（Claude Code / Codex / Cursor / Gemini CLI） |
| Drjacky/claude-android-ninja | 129 | 2026-09-29 | Kotlin + Compose Android 技能：模块化架构、Navigation3、Gradle 约定、依赖管理、测试 |
| Ludi-Herry/mobile-design-system-skill | 19 | 2026-10-25 | 中文移动端（iOS/Android）设计系统与组件规范执行级 skill |
| Ludi-Herry/b-end-design-system-skill | 38 | 2026-10-25 | 中文 B 端设计系统与组件规范 skill |
| ceorkm/mobile-app-ui-design | 465 | 2026-10-28 | 移动 App UI/UX 设计 skill（单 SKILL.md + references） |
| hamen/material-3-skill | 1528 | 2026-07-15 | Material Design 3：30+ 组件、design token、主题、响应式布局、MD3 合规审计 |

## 3. 动效 Motion Design Skill

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| greensock/gsap-skills | 17436 | 2026-07-29 | GSAP 官方 AI skill，教 agent 正确使用 GSAP；含 `.claude-plugin/`、`.cursor-plugin/` |
| Jakubantalik/transitions.dev | 5889 | 2026-10-29 | UI motion agent + 43+ 打磨过的过渡动画库 |
| LottieFiles/motion-design-skill | 2599 | 2026-05-18 | 通用动效原则：timing、easing、choreography、迪士尼原则 |
| kylezantos/design-motion-principles | 1451 | 2026-05-30 | 双模式：构建有目的的动效组件 / 审计既有动画 |
| iart-ai/motion-skills | 1070 | 2026-10-25 | 50 个 skill / 14 个可安装包：动态排版、数据可视化、解说视频、WebGL、Remotion、Manim |
| AThevon/genjutsu | 701 | 2026-10-05 | 创意编程：动效、微交互、设计系统 |
| lovisdotio/motion2code | 298 | 2026-10-20 | 动效视频逐帧还原成循环 SVG + GSAP 代码 |
| bendrape1-byte/silk-design | 142 | 2026-08-26 | 让界面「动得好」：动效 + 平滑滚动 + design token |
| iart-ai/web-animation-skills | 64 | — | GSAP · SVG · Lottie · 微交互 · 页面转场 · 60fps 性能 |
| frankxai/awesome-motion-design-agent-skills | 86 | 2026-10-24 | 动效 skill 精选索引（GSAP/Motion/Remotion/Lottie/Rive + 动效 QA） |

## 4. 交互 Interaction / 微交互 Skill

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| Owl-Listener/ai-design-skills | 191 | 2026-06-09 | AI 产品设计：交互、会话、信任感 |
| alima-max/prototype-to-figma-skill | 141 | 2026-07-28 | 原型 → Figma：把 Claude 原型映射到你的 Figma 设计系统 |
| natdexterra/work-with-design-systems | 52 | 2026-10-20 | 检查/构建/扩展 Figma 设计系统，含 WCAG 只读审计 |
| rastian/interaction-design-skills | 21 | 2026-08-31 | 交互设计专项：流程、状态、微交互、可供性、反馈、IxD 原则 |
| EliaAlberti/ux-audit-skill | 21 | 2026-06-12 | 截图启发式 UX 审计，带严重度分级与启发式引用 |
| solinkz/micro-interactions-skill | 12 | — | 20+ 微交互模式：移动 App / Web / 响应式站点 |
| dannyjpwilliams/design-engineer-skill | 12 | — | 设计工程手艺：阴影、动效、色彩、视觉对齐、渐变、手势 |
| dabaibian/css-skills | 6 | — | 纯 CSS hover 与微交互，零 JS |
| detaildotdesign/skill | 69 | — | 细节打磨：微交互、措辞、边界考量 |

## 5. 中文 / 亚洲设计 Skill

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| joeseesun/qiaomu-design | 660 | 2026-10-13 | 偏执型设计顾问：反 AI 味 + 风格试衣间 + 58 站设计系统库；带中文排版 topic |
| oil-oil/draw-ui | 523 | 2026-10-04 | 出 UI 设计稿并还原成页面，截图到代码 |
| Ludi-Herry/mobile-design-system-skill | 19 | 2026-10-25 | 中文移动端设计系统执行级 skill |
| Ludi-Herry/b-end-design-system-skill | 38 | 2026-10-25 | 中文 B 端设计系统 skill |
| ccwbb78/design-skills | 12 | 2026-06-10 | 优化 agent 建站审美（中文说明） |
| helloianneo/awesome-claude-code-skills | 500 | 2026-10-27 | Claude Code 中文精选合集，按场景分类带推荐等级 |

## 6. 设计 token / 无障碍 / 配色

| 仓库 | Stars | 更新 | 要点 |
| --- | --- | --- | --- |
| hamen/material-3-skill | 1528 | 2026-07-15 | MD3 组件 + token + 主题 + 合规审计 |
| meodai/skill.color-expert | 625 | 2026-10-21 | 色彩科学：色空间、APCA/WCAG、配色生成、颜料混合、色彩史 |
| senlindesign/taste-skill | 436 | 2026-07-07 | 逆向任意网站设计品味，产出具体 token 与取舍 |
| senlindesign/claude2figma | 207 | 2026-05-13 | Claude Code + Figma 的强制层，让 AI 待在自家设计系统轨道上 |
| conalyz/conalyz_cli | 14 | 2026-07-02 | Flutter WCAG 无障碍分析 CLI + agent skill |
| savvides/idstack | 27 | 2026-10-09 | 11 个循证教学设计 skill，含无障碍核查 |

## 7. 精选索引 / 反 AI-slop

| 仓库 | Stars | 说明 |
| --- | --- | --- |
| sickn33/agentic-awesome-skills | 48705 | 2877+ 可复用 skill，含本地目录发现 |
| VoltAgent/awesome-agent-skills | 36548 | 1000+ agent skill 精选 |
| ComposioHQ/awesome-claude-skills | 78237 | 精选索引（设计类条目集中） |
| travisvn/awesome-claude-skills · BehiSecc/awesome-claude-skills | 15620 / 10324 | 通用精选列表 |
| wwewtech/anti-slop-design | 2 | 反 AI 设计陈词滥调 |
| taylorwinfield/ux-ui-skills | 1 | 10 个 skill：设计系统落地、状态、无障碍 |

## 8. 未验证 / 待补项

- 限流未完成查询：`landing page design skill claude code`、`dashboard admin ui skill claude` 的深分页、`ui motion principles skill claude`（仅 2 条弱结果）
- 名称存疑（搜索结果未复核）：`awesome-skills/mobile-app-design`（82 star）、`NguyenKhacPhuc/mobile-app-design-pro`、`chriswiles/claude-code-showcase`
- 与本项目无关（仅因 topic 命中，已剔除）：`paperclipai/paperclip`、`thedotmack/claude-mem` 等 `topic:claude-skills` 高星条目

## 9. 本机 DSH 落地方式

- skill 目录：`C:\Users\se'jng'k's\.dsh\skills\`（现有 `adobe-design-suite`、`re-local-tools`）
- 手动放置：复制 `skills/<name>/SKILL.md` 整个目录到 `~/.dsh/skills/<name>/`，重启会话生效
- 插件式安装：带 `.claude-plugin/` 或 `.dsh/` 目录的仓库按其 INSTALL/README 走：`impeccable`、`superdesign-skill`、`gsap-skills`、`aldefy/compose-skill`、`ux-ui-agent-skills`、`baoyu-design`、`refactoring-ui-skill`、`motion2code`

## 10. 针对「课程表」项目的推荐组合

| 环节 | 选型 | 理由 |
| --- | --- | --- |
| Android 工程底座 | `aldefy/compose-skill` + `skydoves/compose-performance-skills` | 前者给 Compose 代码规范（带 androidx 源码佐证），后者管 recomposition/性能，正好是课程表这种高频滚动列表的命门 |
| 视觉与设计系统 | `pbakaus/impeccable` + `plugin87/ux-ui-agent-skills` | 打磨+审查双闸门，token/组件/无障碍一次到位，都带插件目录 |
| 动效与交互质感 | `emilkowalski/skills`（mobile-native / apple-design）+ `kylezantos/design-motion-principles` | 移动原生动效语汇 + 双模式动效审计 |
| 出稿与验证 | `JimLiu/baoyu-design` + `Anionex/agent-vision-toolkit` | 前者出高保真 HTML 稿，后者能让 AI「看」回截图做还原比对 |

---

## 11. 已安装落地（2026-10-29）

落点：`C:\Users\se'jng'k's\.dsh\skills\`（DSH 已热加载，无需重启会话）。下载方式：`codeload.github.com` 固定 IP + `curl --resolve` 拉 tarball 解包，未改动系统 hosts 与 DNS 设置。

已安装 25 个：

- **skydoves/compose-performance-skills（18 个）**：debugging-recompositions、using-strong-skipping-correctly、deferring-state-reads、choosing-derivedstateof、avoiding-subcomposition-pitfalls、understanding-stability-inference、stabilizing-compose-types、diagnosing-compose-stability、visualizing-recomposition-cascades、enforcing-stability-in-ci、using-stability-analyzer-ide-plugin、optimizing-lazy-layouts、configuring-lazy-prefetch、ordering-modifier-chains、migrating-to-modifier-node、tracing-recompositions-at-runtime、using-efficient-effects、auditing-compose-performance
- **aldefy/compose-skill（1 个）**：compose-expert（含 34 文件参考资料）
- **kylezantos/design-motion-principles（1 个）**：design-motion-principles
- **emilkowalski/skills（5 个）**：review-animations、improve-animations、apple-design、animation-performance、mobile-native

未安装（源码包保留在 `.tmp/skills-dl`）：compose-performance 的 build/configuring-r8-for-compose、hot-reload 4 个、measurement 2 个、side-effects/collecting-flows-safely；emil 的 11 个（animate、animate-expo、animation-vocabulary、ask-sonner、break-ui、emil-design-eng、find-animation-opportunities、pick-ui-library、prototype、remove-animations、write-swift）。

注意事项：

- 动效组中 `animation-performance`、`mobile-native`、`design-motion-principles`、`review-animations` 为 Web/React 语境，理念与审查标准可迁移，代码示例对 Compose 参考价值有限。
- `review-animations` frontmatter 带 `disable-model-invocation: true`，可能需手动点名调用。
- 回滚：`rm -rf ~/.dsh/skills/<技能名>`；确认无误后 `.tmp/skills-dl` 可整目录删除。

## 12. Compose 动效专项补充（2026-10-29 追加）

专项检索结论：Compose 动效 skill 生态远比通用 UI 稀薄，最终锁定三个纯 Compose 向 skill + 两个上游配套。

| 技能 | 来源仓库 | Stars | 内容 |
| --- | --- | --- | --- |
| compose-animations | chrisbanes/skills（Chris Banes，Compose 权威） | — | 70 行主文 + references/animated-content.md；AnimatedVisibility / animate*AsState / rememberTransition / AnimatedContent / Crossfade 选型 |
| compose-motion | AThevon/genjutsu（skills/_jutsu/） | 701 | 365 行 + 3 个 references（gestures-compose、recomposition-and-anim、shared-transitions）；基线 Compose 1.12 / BOM 2026.08；含 ValueAnimator.areAnimatorsEnabled() 与 ANIMATOR_DURATION_SCALE 讨论 |
| wnb-compose-animation | wenubey/claude-android-skills | 1 | 83 行；决策门（先问「这个 UI 到底该不该动」）+最小 API 选择+必需 label/尊重 reduce-motion/避开滚动相邻重组路径 |
| motion-principles | AThevon/genjutsu | 701 | 跨平台动效通则（时间、缓动、无障碍），compose-motion 的上游文档 |
| mobile-principles | AThevon/genjutsu | 701 | 移动体验通则，含 references/accessibility-mobile.md |

已装：`compose-graphics`（见 §13）。未装：`compose-multiplatform`；`chrisbanes/skills` 另有 compose-state-and-effects、compose-performance、compose-component-design、compose-focus-navigation、compose-ui-testing-patterns。

已处理：`animation-performance`、`mobile-native`（Web 向）已移除，备份在 `.tmp/removed-skills/`（见 §13）。

本次已下载核验但未安装的候选：haidrrrry/compose-kotlin-agent-skills（references/03-animations.md，共享元素与 Canvas）、ayush016/android-lead-agent-skills（references/shared-element-transitions.md，动效 API 命中密度最高 104 处）、shampooli61/motion-to-code（动效视频→Web+Compose 代码，中文）、tawhidmonowar/android-design-skills（仅布局基础，无动效）。

## 13. 2026-10-29 收尾：清理 Web 向、补齐 Compose 向

**移除（已备份到 `.tmp/removed-skills/`，可回滚）**：`animation-performance`（CSS/WAAPI/合成层）、`mobile-native`（Web 手机手感修复）。两者均为 Web 语境，已被 Compose 向技能取代。

**genjutsu（AThevon，701 star）补充**：

| 技能 | 说明 |
| --- | --- |
| compose-graphics | 高级 Compose 视觉：Material 3 Expressive 动效物理、AGSL shader（Android 13+）、Canvas/DrawScope 生成式绘制、graphicsLayer 效果 —— 与项目 GlassShaders 直接相关 |
| design-audit | 设计审计清单：动效缺口、无障碍、配色一致性、响应式、性能（自带 scripts/） |
| cast | genjutsu 的动效入口：扫描技术栈 → 提出交互命题（interaction thesis）→ 调度对应子技能 → 实现；显式适配 Android/Compose。引用 `../bunshin`（子代理编排框架，未装），存在 1 处悬空引用 |
| tells | AI 惯性反射清单：编造数据、装饰性填充、趋同布局、空洞文案 |

未补：`canvas-generative`（Web Canvas 2D）、`framer-motion` / `gsap` / `css-native` / `threejs-r3f`（Web 生态）、`swiftui-motion` / `swiftui-graphics` / `desktop-principles`（非 Android）、`orchestration` / `bunshin` / `paint`（genjutsu 编排与视觉管线，耦合多端子技能）。

**chrisbanes/skills 补充**：

| 技能 | 说明 |
| --- | --- |
| compose-component-design | 可复用组件 API 设计：modifier 参数、根布局位置、caller 提供内容、原子内容参数、可选内容、布尔形状标志 —— 对应项目 GlassSurface / CardTilt / JellySwitch 这类自绘组件 |
| compose-state-and-effects | 状态归属、remember、状态上提、screen state holder、LaunchedEffect / DisposableEffect / SideEffect、Flow 收集、导航、snackbar、分析埋点、焦点请求 |
| compose-performance | 重组开销、编译器稳定性报告、可跳过性、unstable 参数、帧率级 State 读取、跨阶段快照回写、@ReadOnlyComposable 契约 |

未补：`compose-focus-navigation`（TV/键盘/桌面焦点，手机端用不上）、`kotlin-*`（通用 Kotlin，与 UI 动效无关）、`compose-ui-testing-patterns`、`android-benchmark-comparison`。

**最终技能总数**：35 个目录（约 4MB）。

## 14. 安装记录（2026-10-29 收尾实测）

本节数字全部取自本次会话对磁盘的实测读数，不是推断。

### 14.1 本轮新增落位

落点：`D:\课程表\.dsh\skills\`（项目级技能根）。本会话是 `workspace-write` 子会话，写 `~/.dsh` 被沙箱拒（EPERM），因此先落项目级，用户级待有写权限的会话补齐。

| 技能 | 来源 | 文件 | 字节 | 行数 |
| --- | --- | --- | --- | --- |
| gradle-run | chrisbanes/skills | 5 | 121353 | 2854 |
| compose-focus-navigation | chrisbanes/skills | 1 | 3974 | 102 |
| compose-ui-testing-patterns | chrisbanes/skills | 2 | 8847 | 200 |
| **合计** | — | **8** | **134174** | **3156** |

校验：sha256 对 `.skill-install/project-skills.sha256.txt` 为 **8/8 通过**；git blob SHA-1 对 `.skill-install/MANIFEST.sha1.txt` 为 **8/8 通过**。安装脚本 `.skill-install/finish-user-install.mjs`，备份目录 `.skill-install/removed-skills/`。

### 14.2 两个技能根不能混装

DSH 同时扫两个根：项目级 `<cwd>/.dsh/skills`（rank 100）与用户级 `~/.dsh/skills`（rank 400）。相对引用按**引用方自己所在目录**解析，跨不了根，所以把依赖补到项目级修不好用户级的断链。

- 项目级根：10 个目录（compose-animations、compose-component-design、compose-focus-navigation、compose-performance、compose-state-and-effects、compose-ui-testing-patterns、dsh-file-edit-guardrails、dsh-skill-catalog-management、github-api-access、gradle-run），29 文件 / 240074 B。
- 用户级根：37 个目录，120 文件 / 3797033 B。
- 同名重叠 4 个：compose-animations、compose-component-design、compose-performance、compose-state-and-effects（rank 100 盖住 400，容易读到旧版）。
- 用户级断链 **62 条**（`../<skill>/` 解析不到），其中 4 条目标只存在于项目级：compose-component-design → compose-ui-testing-patterns、compose-performance → gradle-run、compose-state-and-effects → compose-focus-navigation 与 compose-ui-testing-patterns。要归零须在能写 `~/.dsh` 的会话里把 3 个依赖装到用户级，再删项目级同名副本。
- 项目级自身断链 **4 条**，全部出自 `dsh-skill-catalog-management/references/incidents.md`，指向 compose-graphics、motion-principles、mobile-principles、compose-multiplatform（这四个只在用户级），是同一原因的另一面。

### 14.3 未核验项

- `haidrrrry/compose-kotlin-agent-skills`、`ayush016/android-lead-agent-skills`、`shampooli61/motion-to-code`、`tawhidmonowar/android-design-skills` 四个候选仓的 owner 拼写与 star 数仍未复核（本次会话无外网）。
- 旧 `animation-performance` 现在同时存在于 `.tmp/removed-skills/` 与 `dsh-skills-install/_removed-archive/`，两处都可回滚，清理时机待定。

---

## 14. 本机网络修复记录：GitHub hosts 钉扎（2026-10-29）

**诊断结论**：本机 DNS 配置本身正常（校园网 172.16.1.8 / 172.16.1.88），但校园网 DNS 对 `github.com`、`api.github.com`、`codeload.github.com` 的解析被掐（其他域名如 baidu / npmmirror / raw.githubusercontent.com 解析正常）；TCP 层直连这几个域名对应的 IP 是通的（api 200、codeload 301、objects 404=可达）。`raw.githubusercontent.com` 则是 TCP 层被阻断（curl 返回 000，约 0.1s 即失败）。

**处置**：经用户确认，采用 hosts 钉扎（免提权、可逆）。

```
# 追加到 C:\Windows\System32\drivers\etc\hosts
20.205.243.168  api.github.com
20.205.243.166  github.com
20.205.243.165  codeload.github.com
185.199.109.133  objects.githubusercontent.com
```

- 备份：`.tmp/hosts.bak-20261029`（1039 字节，变更前原文件）
- 生效验证：`ipconfig /flushdns` 后 `https://api.github.com/rate_limit` 返回 200，不再需要 `--resolve`
- 未解决：`raw.githubusercontent.com` TCP 被阻断，hosts 无效，仍须走代理
- 回滚：`cp .tmp/hosts.bak-20261029 /c/Windows/System32/drivers/etc/hosts`
- 注意：GitHub 主站 IP 可能变动，若日后失效需重新解析（用 `Resolve-DnsName github.com -Server 223.5.5.5`）并更新 hosts；hosts 中已存在的第三方条目（bandisoft 屏蔽、geetest CDN pin）未改动
报告由 DSH agent 生成于 2026-10-29；star 数与更新时间均取自 GitHub API 实际返回值。

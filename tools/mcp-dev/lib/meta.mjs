/**
 * 服务器身份、协议版本与注入到系统提示的使用说明。
 * 说明文本会被 DSH 的 mcp-client 以「### MCP server: zhijian」段落注入系统提示，
 * 因此只写「什么时候用哪个工具」的硬约定，不写实现细节。
 */

/** 本服务器能协商的 MCP 协议版本（与 DSH 客户端 SDK 的支持列表保持一致）。 */
export const PROTOCOL_VERSIONS = [
    '2025-11-25',
    '2025-06-18',
    '2025-03-26',
    '2024-11-05',
    '2024-10-07',
];

/** 客户端请求的版本不在列表里时回落到的最新版本。 */
export const LATEST_PROTOCOL_VERSION = '2025-11-25';

export const SERVER_INFO = {
    name: 'zhijian-dev',
    title: '纸间课表开发后端',
    version: '1.0.0',
};

export const INSTRUCTIONS = [
    '本服务器是「纸间课表」(PaperTimetable，仓库根 D:\课程表) 的开发后端，',
    '把该仓库的构建、测试、静态检查、真机、发版、脚本与文档校验固定成工具。',
    '',
    '硬约定：',
    '1. 任何 Gradle 动作走 build / test / lint，不要手敲 gradlew；',
    '   开工先 env 探环境，再 map 读结构，不要凭空猜包路径。',
    '2. 改完 Kotlin 先 build(mode=compile)；提交前 test + lint + arch_check 必须全绿。',
    '3. 真机验证用 device(list/install/launch/logcat/screenshot)，包名后缀为 .debug。',
    '4. 发版前 release_status 过清单；改版本号用 version_bump(apply=true)，并同步 CHANGELOG。',
    '5. 改了 scripts/*.js 必须 jw_scripts(action=verify)，再用 sync 更新哈希与版本号。',
    '6. 新增或移动文档后 docs_check，保证 docs/README.md 索引与相对链接完整。',
    '7. 提交走 git：只 stage 明确列出的文件，提交信息 <type>(<scope>): 中文描述，不自动 push。',
    '8. 假教务联调用 mock_jw(start/stop/status)。',
].join('\n');

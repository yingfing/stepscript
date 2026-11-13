# 拾步 StepScript

录制回放式安卓自动化：**脚本优先，AI 只做"脚本医生"**。

你录制一遍操作，之后由脚本回放；脚本执行中每一步都做四重校验（前台 / 控件 / 弹窗 / 结果），
出现意外（广告弹窗、App 自行退出、控件找不到……）会自动：**截图 + 布局存档 + 静默退场 + 通知你**，
你随时打开"失败记录"，让 AI 基于现场生成**脚本补丁（diff）**，确认后才生效——AI 不操作手机，脚本库越修越稳，AI 用得越来越少。

## 功能一览（v1）

- **StepScript 脚本语言**：中文关键字、一行一个动作，语法只有一页纸（见 `core/`）
- **录制器**：无障碍监听你的自由操作 → 自动生成脚本步骤
- **悬浮拾取器**：在目标应用上"指哪取哪"，控件高亮，一键插入脚本
- **布局分析器**：节点树可视化 + XML/压缩文本双格式快照
- **回放引擎**：每步四重校验 + 兜底规则（`兜底 文本 "已签到" 则 跳过`）
- **失败处理**：截图 + 布局存档 + Shizuku 静默退场 + 通知 + 待处理列表
- **AI 脚本医生**：OpenAI 兼容接口（也支持本地 Ollama），产出补丁 → diff 确认 → 版本历史可回滚
- **应用选择器**：从已安装应用列表选，告别手填包名

## 构建

需要 Android Studio（Ladybug+）或命令行 Android SDK：

```bash
git clone https://github.com/yingfing/stepscript.git
cd stepscript
./gradlew :app:assembleDebug     # 或用 Android Studio 直接打开
```

`core` 模块是纯 Kotlin（脚本解析器/渲染器/diff），可单独跑测试：

```bash
./gradlew :core:test
```

## 首次使用

1. 安装后到 **系统设置 → 无障碍** 开启「拾步 · 界面采集与自动化」
2. （可选）安装 [Shizuku](https://shizuku.rikka.app/) 并授权，失败善后时才能 force-stop 目标应用；不装也能用（降级为回桌面）
3. 在「设置」里填 AI 接口（可以先用任何 OpenAI 兼容服务；本地 Ollama 填 `http://<电脑IP>:11434/v1`）

## MCP：让任何 AI 接管这台手机上的拾步

App 启动时会在 **127.0.0.1:8765** 起一个 MCP Server（Streamable HTTP / JSON-RPC 2.0）。

电脑侧接入（两步）：

```bash
adb reverse tcp:8765 tcp:8765
# 然后 MCP 客户端（Claude Desktop / 任何支持 MCP 的 AI）连接 http://127.0.0.1:8765/mcp
```

暴露的工具覆盖"用户能做的一切"：

| 类别 | 工具 |
|---|---|
| 脚本 | `list_scripts` `get_script` `write_script`（写入前强制语法校验，AI 写错直接打回）`run_script` |
| 界面 | `get_layout`（压缩文本+XML）`tap_element` `input_text` `press` `swipe` |
| 失败 | `list_failures` `get_failure` `ai_fix_failure`（AI 出补丁 diff，**不自动落库**） |

安全红线不变：AI 不直接获得危险操作权——写脚本必须过语法校验，AI 补丁只返回 diff，落库必须显式调 `write_script`；MCP 只监听本机回环，外部无法访问。

## 已知边界（诚实清单）

- 截图已接入无障碍 `takeScreenshot`（Android 11+ 自动生效；Android 10 及以下失败现场暂无截图，只有布局快照）
- 输入框逐字输入的 IME 通道未实现，依赖 `ACTION_SET_TEXT`（绝大多数场景够用）
- 反自动化检测的银行类应用无法支持（会检测无障碍），这是平台限制
- Shizuku force-stop 尚未接入（v1.1 计划），失败善后目前为"返回 + 回桌面"
- AI 补丁采纳后支持自动重跑验证（"采纳并重跑"），Android 13+ 需要授予通知权限才能收到失败通知

## License

MIT

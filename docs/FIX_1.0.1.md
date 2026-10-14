# 1.0.1：普通搜索按钮与 Ctrl+F 修复

## 问题与依据

在增强条件窗口点击“普通搜索…”后，原生查找条或 Find in Files 窗口未打开。

本机 IDEA 日志 `D:/JetBrainsData/IntelliJIdea2023.3/log/idea.log` 的两条记录直接指向相同原因：

- 2026-10-14 15:15:52，原日志第 45847～45850 行：`UnsupportedOperationException → ActionStub.actionPerformed(ActionStub.java:88) → ProjectSearchAction`。
- 2026-10-14 15:17:45，原日志第 45971～45974 行：`UnsupportedOperationException → ActionStub.actionPerformed(ActionStub.java:88) → CurrentFileSearchAction`。

IDEA 233 的 `ActionManagerImpl.getBaseAction()` 可能返回尚未实例化的原生 `ActionStub`。1.0.0 将它作为可执行动作直接调用，因此异常发生在原生窗口打开之前。旧测试只检查原动作不同于增强动作，没有验证返回对象是否可执行，也没有实际点击此按钮。

## 修复

- [NativeActionSupport.java](../src/main/java/com/zhoucb/searchenhance/ide/NativeActionSupport.java)：使用本机 233 SDK 的 `ActionManagerImplKt.convertStub()` 实例化延迟动作并缓存；原对象已可执行时直接保留；异常或未解析占位对象使用真正的原生动作实例，禁止直接返回 `ActionStubBase`。
- [SearchEnhanceDialog.java](../src/main/java/com/zhoucb/searchenhance/ui/SearchEnhanceDialog.java)：记录“普通搜索”入口日志，并在增强窗口关闭后明确按非模态状态调度原生动作。
- [plugin.xml](../src/main/resources/META-INF/plugin.xml)：移除原生 `Find` 动作覆盖，Ctrl+F 恢复 IDEA 默认当前文件查找；当前文件增强搜索改为 Tools 菜单独立动作 `SearchEnhance.FindInCurrentFile`，不注册快捷键。
- [EditConditionsAction.java](../src/main/java/com/zhoucb/searchenhance/action/EditConditionsAction.java)：修复未打开文件时 Tools 入口将空编辑器传给非空上下文构造参数的问题，明确使用空编辑器上下文。
- Ctrl+Shift+F 仍进入项目增强搜索，查询模板及原生结果窗口集成保持原有功能。

## 验证

- 本机 IntelliJ IDEA 2023.3.8 / IU-233.15619.7 SDK，Java 17 逐文件编译通过，不使用 Maven。
- 原有 403 个查询断言全部通过。
- 新增可执行原生动作断言，以及真正打开增强窗口、点击“普通搜索…”并检查原生界面的交互回归，全部通过。
- 当前文件独立入口、Tools/已打开文件：实际按钮点击后显示原生 `EditorSearchSession` 查找条。
- 项目搜索/已打开文件、项目树/未打开文件、Tools/未打开文件：实际按钮点击后显示原生 `FindPopupPanel`，增强窗口已关闭。
- 原生 Find 动作类型、Ctrl+F 键位和“不返回 ActionStubBase”断言通过。
- 报告：`build/reports/smoke-20261014-153809-953da635/smoke-report.md`。隔离的测试项目通过测试专用真实 IDEA 窗口完成交互，不进入用户现有配置。
- 安装包 `build/distributions/search-enhance-1.0.1.zip`。
- SHA256：`6C592F59ACB17F9813CF3B8BDE0187A22C3F2DF869865A6BD030F59772E4171D`。
- 集成所加载 JAR SHA256：`AA01F4E10D107EDCEAF674F3238A4BCDB50A14851B62001C8193E5270C9A9BD5`，与安装 ZIP 内 JAR 一致。

## 本次改动分类

| 类别 | 改动 |
| --- | --- |
| 数据表、业务参数、数据字典、DAO | 无 |
| 接口定义 / 插件动作 | 移除 Find 覆盖；新增独立当前文件动作；保留 FindInPath 覆盖 |
| 查询模板 | 无变更 |
| 脚本 | 沙箱与集成脚本默认使用 1.0.1 安装包 |
| 代码及测试 | 原生动作实例化与缓存、按钮非模态调度、真实按钮交互回归 |

未修改或重新分配 Ctrl+F 的 Keymap 设置；该快捷键从原生动作定义中恢复。插件声明需重启，安装 1.0.1 后应重启 IDEA。

## 远程操作

本次没有 SSH 或数据库 SQL。仅对现有 GitHub 仓库推送修复提交、推送 `v1.0.1` 标签，并创建对应 Release 上传 ZIP 和 SHA256 文件；保留 1.0.0 标签及附件。

执行的远程命令和 API：`git push origin main`、`git push origin v1.0.1`、`git ls-remote origin refs/heads/main refs/tags/v1.0.1`；`POST /repos/FlyMeToTheMars911/idea-search-enhance/releases`，上传两个发布附件，并用 `GET /repos/FlyMeToTheMars911/idea-search-enhance/releases/tags/v1.0.1` 核对发布元数据。

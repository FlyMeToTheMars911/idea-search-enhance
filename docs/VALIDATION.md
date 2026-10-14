# 验证与发布记录

本页保留 1.0.0 首次交付的验证记录；1.0.1 修复与新增按钮交互回归见 [FIX_1.0.1.md](FIX_1.0.1.md)。当前 Ctrl+F 已恢复原生行为，1.0.0 的“动作覆盖”记录属于旧版本。

验证日期：2026-10-14。

## 环境

- 实际运行的 IntelliJ IDEA：2023.3.8 Ultimate，`IU-233.15619.7`。
- 编译：Java 17，`javac --release 17`，使用本机该版 IDEA `lib/*.jar`。
- 编译按单个 Java 源文件执行；不使用 Maven。
- 最终安装包：`build/distributions/search-enhance-1.0.0.zip`。
- ZIP SHA256：`79AF46BD1D0F4C33FC8EA41309E2AEF1256769051EC54E803E6F01C08D0E78C2`。

## 已通过检查

| 检查 | 结果 |
| --- | --- |
| 11 个生产 Java 源文件，逐文件 SDK 编译 | 通过 |
| 纯查询回归测试 | 403 个断言通过 |
| 独立随机布尔真值对照 | 300 组，包含在 403 个断言中 |
| 速查表全部模板、字面特殊符号、大小写、完整词及 Unicode 换行 | 通过 |
| 不完整双字段、非法数字、多行普通文本及无效字符串片段校验 | 通过 |
| 长行、重复词缺失后项、多引号及转义字符压力检查 | 通过；测试线程设有超时，避免挂住构建 |
| 正式 ZIP 目录和插件描述符 | `search-enhance/lib/search-enhance.jar`，含 `META-INF/plugin.xml` |
| Gradle Wrapper JAR | 已与 Gradle 官方 SHA256 对照；分发文件另配置 SHA256 |
| 独立沙箱准备脚本 | 通过，不修改用户当前 IDEA 配置 |
| 真实 IDEA 233 加载正式插件、覆盖 Find / FindInPath | 通过 |
| 原生默认快捷键 | Find 保留 Ctrl+F / Alt+F3，FindInPath 保留 Ctrl+Shift+F |
| 真实 FindManager AND / OR / NOT、边界和正反向匹配 | 通过 |
| 14 种真实 Swing 条件编辑器初始化、输入校验、离屏绘制 | 全部通过，生成 14 张 PNG |
| 原生当前文件查找条及既有 Session 复用、后续查找模型 | 通过 |

最终集成测试所加载 JAR 的 SHA256 与交付 ZIP 内 JAR 一致：`EF53FF12E9AA8229F57B4879CA1B9060CA4463EF51FAA3667E8C918B5D0033BA`。

IDEA 原生反向查找的 offset 是严格上界：仅接受 `match.end < offset`。测试按该版平台语义验证，避免将恰好结束于文件末尾的匹配误认为产品缺陷。

## 验证边界

自动集成在独立配置的 IDEA 2023.3.8 进程内执行，真实初始化 Swing 控件并离屏绘制，不在用户当前配置中安装，也没有人工点击验收。项目搜索的原生 Find in Files 弹窗和结果窗口由平台原有实现提供，未进行整条界面点击链路的自动化验收。

Gradle 分发未在本次构建中下载执行；交付包由已通过的离线 PowerShell 构建脚本生成。

## 本地复现命令

```powershell
.\scripts\build.ps1
.\scripts\run-sandbox.ps1 -PrepareOnly
.\scripts\smoke-test.ps1
git diff --cached --check
```

集成详细报告和 14 张截图位于本地忽略的 `build/reports/smoke-*/`。README 中保留一张真实界面预览。

## 远程仓库操作

没有 SSH、远程服务器命令或数据库 SQL。本次 GitHub 操作使用本机已有 Git 凭据，未把密码或令牌写入仓库。

- `GET /user`：确认已有凭据的 GitHub 账号。
- `GET /repos/FlyMeToTheMars911/idea-search-enhance`：检查目标仓库是否存在。
- `POST /user/repos`：新建私有仓库 `idea-search-enhance`。
- 仅暂存本插件源代码、构建与测试脚本、Wrapper、文档；执行 `git commit`。
- `git remote add origin https://github.com/FlyMeToTheMars911/idea-search-enhance.git`。
- `git push -u origin main`，推送本次提交。
- `git tag v1.0.0` 和 `git push origin v1.0.0`，记录交付版本。
- `POST /repos/FlyMeToTheMars911/idea-search-enhance/releases`，创建 v1.0.0 发布。
- 向发布上传安装 ZIP 和 SHA256 校验文件，并读取发布元数据核对。
- `git ls-remote origin refs/heads/main refs/tags/v1.0.0`，核对远程提交。

仓库、提交和发布链接以实际 GitHub 元数据及本项目 Git 历史为准。

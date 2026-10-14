# 本次开发变更审阅

| 类别 | 本次改动 |
| --- | --- |
| 数据表 | 无 |
| 业务参数 | 无服务端业务参数；应用级偏好 nativeFirst=true、showNativeButton=true；界面支持大小写、逐条件完整词选项 |
| 接口定义 | 无 HTTP、RPC 或外部接口；FindInPath 按偏好选择入口；保留原生 Find / Ctrl+F；Tools 菜单提供独立增强动作；Settings 页面 ID search.enhance.preferences |
| 数据字典 | 无数据库字典；新增本地 MatchType 枚举，14 种界面搜索场景 |
| 查询模板 | 新增字面文本、任意一个、顺序、行首行尾、紧邻、仅空白、赋值/比较、多值、数字、前缀、调用形式、字符串、排除成员访问模板 |
| DAO 层 | 无 |
| 脚本 | scripts/build.ps1 离线逐文件检查、测试及打包；scripts/run-sandbox.ps1 独立沙箱；scripts/smoke-test.ps1 真实平台集成检查；Gradle Wrapper 与 Gradle 构建配置 |
| 插件定义 | META-INF/plugin.xml；平台范围 233.15619.7 至 233.*；启用/禁用需重启 |
| 本地持久化 | 当前项目 workspace.xml 中 SearchEnhanceSettings；保存条件草稿、选中分组和大小写设置 |
| 应用级偏好 | options/SearchEnhancePreferences.xml；保存默认打开方式和原生按钮显示选项 |
| 日志 | 使用 IDEA Logger；记录入口和分组数量，不打印搜索关键词；本项目没有 LoggerUtils |
| 依赖 | Java 17 + 本机 IDEA SDK，无新增运行时第三方库，无 Maven |

## 性能与范围

实际扫描及范围选择交给 IDEA 原生引擎，不另建索引、不自行遍历全项目、不访问数据库。查询用行边界和不跨行字符范围限制；文本通过字面转义进入表达式。组合条件通常需要检查整个候选行，建议大项目使用原生 Scope、Directory、File mask 缩小范围。

## Git 提交范围

仅提交本插件源代码、构建脚本、测试和说明。忽略 work/、build/、.gradle/、.idea/、本机设置和生成安装包；安装包作为单独交付物。

1.0.1 的专门修复、日志依据和本次分类见 [FIX_1.0.1.md](FIX_1.0.1.md)。

1.0.2 的原生按钮入口、全局设置和本次分类见 [FEATURE_1.0.2.md](FEATURE_1.0.2.md)。

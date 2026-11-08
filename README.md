# 单词记忆 App（B4 主 App + P1 桌面小组件伴生模块）

Android 课程设计项目。纯离线单词记忆应用：导入词库 → 自研「艾宾浩斯-Δ」算法排出每日复习队列 → 桌面小组件随时显示还剩多少词，并支持一键过词。

## 状态

| 项 | 值 |
|---|---|
| 构建 | ✅ `./gradlew clean :app:assembleDebug` 通过 |
| 单元测试 | ✅ **24 个用例全绿**（算法 11 / CSV 解析 9 / 提醒定时 4） |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，**3.57 MB** |
| 代码规模 | **59 个 Kotlin 文件 / 5611 行** |
| 已知缺陷 | 10 个，**全部已修复**（见 [08-联调问题与修复清单](docs/08-联调问题与修复清单.md)） |
| 交付说明 | [13-最终交付说明](docs/13-最终交付说明.md) |

## 技术栈（实际）

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.3.21（AGP 9 内置，不单独 apply 插件） |
| UI | **View 体系 + RecyclerView**（早期为 Compose，第 6 阶段按课程要求变更） |
| 状态 | ViewModel + StateFlow（`sealed interface UiState` 全状态渲染） |
| 数据 | Room 2.8.5（KSP）+ Flow 响应式查询，**8 张表** |
| 异步 | Kotlin Coroutines |
| 后台 | WorkManager 2.9.1（每日提醒、小组件跨天刷新） |
| 桌面组件 | AppWidget + RemoteViews（P1 独立模块，不使用 Glance） |
| 构建 | Gradle 9.6.0 / AGP 9.4.0 / compileSdk 37 / minSdk 26 |

> 不使用任何跨平台框架；不使用 Hilt / RxJava / Paging / Glance 等大纲外依赖（选型理由见 `docs/05`）。

## 模块结构

```
:app          主 App（6 个 Activity / ViewModel / 导航）
:widget       P1 伴生模块（AppWidget + RemoteViews + 快捷「已复习」）
:core-data    Room / DAO / Repository / CSV 解析器 / 复习队列引擎
:core-domain  艾宾浩斯-Δ 算法（纯 Kotlin，零 Android 依赖，可纯 JVM 单测）
```

依赖方向：`:app → :widget → :core-data → :core-domain`（另有 `:app → :core-data`）。
**`:widget` 不引用 `:app` 任何类**；app 层不接触 Room 类型（走 `DataFactory`）。

> 关于"app 为什么依赖 widget"：library 的 manifest 只有被 app 依赖才会合并进 APK，否则桌面看不到组件。

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-需求规格说明书](docs/01-需求规格说明书.md) | 范围界定、FR/NFR、边界值、20 条验收标准、追溯矩阵 |
| [02-功能清单与伴生模块设计](docs/02-功能清单与伴生模块设计.md) | 28 项功能清单、模块划分、P1 小组件设计 |
| [03-仓库与分支规范](docs/03-仓库与分支规范.md) | 目录结构、分支规划、提交规范、16 次提交清单 |
| [04-界面原型说明](docs/04-界面原型说明.md) | 5 个界面线框、控件规格、状态矩阵 |
| [05-技术选型报告](docs/05-技术选型报告.md) | 分层架构、依赖逐项论证、工具链基线、ADR |
| [06-数据库设计](docs/06-数据库设计.md) | 8 表字段级设计、索引映射、事务与迁移、容量估算 |
| [07-小组件与数据层连通方案](docs/07-小组件与数据层连通方案.md) | 进程链路、四条刷新链路、尺寸与视觉规范 |
| [08-联调问题与修复清单](docs/08-联调问题与修复清单.md) | 7 个问题的现象/根因/修复 |
| [09-课程设计报告](docs/09-课程设计报告.md) | **报告汇总稿（10 章）** |
| [10-测试用例与回归报告](docs/10-测试用例与回归报告.md) | 32 条手工回归用例 + 缺陷统计 |
| [11-答辩PPT大纲](docs/11-答辩PPT大纲.md) | 10 页大纲 + 5 个高频提问答法 |
| [12-录屏脚本](docs/12-录屏脚本.md) | 两段 3 分钟分镜 |
| [13-最终交付说明](docs/13-最终交付说明.md) | 校验结论、交付清单、PDF 导出规范 |
| [14-答辩PPT完整内容](docs/14-答辩PPT完整内容.md) | 逐页文稿 + 口播稿 |
| [15-代码问答卷核心考点](docs/15-代码问答卷核心考点.md) | 31 个高频问答 |
| [16-录屏执行与水印说明](docs/16-录屏执行与水印说明.md) | 分镜细化 + FFmpeg/剪映水印 |

## 16 次提交与阶段

| # | 阶段 | 提交 |
|---|---|---|
| 1 | 需求定义与仓库初始化 | `chore(repo)` |
| 2 | 界面原型 / 技术选型 / 数据模型 | `docs(design)` |
| 3 | 四模块工程骨架 | `build` |
| 4 | Room 数据层 | `feat(data)` |
| 5 | CSV 词库导入 | `feat(import)` |
| 6 | 单词列表 + 详情 | `feat(ui)` |
| 7 | 复习队列引擎 + v2 迁移 | `feat(review)` |
| 8 | 艾宾浩斯算法完整版 | `feat(algo)` |
| 9 | 复习卡片交互页 | `feat(review-ui)` |
| 10 | 权限流程 + 全状态 UI + 崩溃兜底 | `feat(perm-ui)` |
| 11 | 桌面小组件骨架 | `feat(widget)` |
| 12 | 小组件数据同步 | `feat(widget-sync)` |
| 13 | 小组件交互 + UI 打磨 | `feat(widget-ui)` |
| 14 | 每日提醒 + 联调修复 | `feat(reminder)` |
| 15 | 测试补齐 + 文档初稿 | `docs(test)` |
| 16 | 最终交付整理 | `docs(release)` |

## 里程碑 tag

`lab01-env` → `lab02-ui` → `lab03-nav` → `lab04-list` → `lab05-room` → `lab06-viewmodel` → `lab07-coroutine` → `lab08-work` → `lab09-widget` → `lab10-final`

## 构建与测试

```bash
# 环境：JDK 25 + SDK Platform android-37.0 + Gradle 9.6.0 + AGP 9.4.0
# 注意：AGP 9.4.0 是本机 Android Studio 2026.2.1 的支持上限，升到 9.5 会导致 IDE 同步失败

./gradlew clean :app:assembleDebug          # 打包
./gradlew :core-domain:test                 # 算法单测（无需模拟器）
./gradlew :core-data:testDebugUnitTest      # CSV 解析单测
./gradlew :app:testDebugUnitTest            # 提醒定时单测
./gradlew test                              # 全部单测（24 个）
```

首次运行若 Gradle 报 `Failed to load native library`，是因为沙箱不允许写 `~/.gradle/native`——
在普通终端（非受限环境）执行即可。

## 提交规范

Conventional Commits + 中文正文，一次提交一件事。详见 [03-仓库与分支规范](docs/03-仓库与分支规范.md) 第 4 节。

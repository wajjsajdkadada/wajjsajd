# 单词记忆 App（B4 主 App + P1 桌面小组件伴生模块）

Android 课程设计项目。纯离线单词记忆应用：导入词库 → 自研「艾宾浩斯-Δ」算法排出每日复习队列 → 桌面小组件随时提醒今天该背哪些词。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3（导航 `navigation-compose`） |
| 状态 | ViewModel + StateFlow（`sealed interface UiState` 全状态渲染） |
| 数据 | Room（6 表，Flow 响应式查询） |
| 异步 | Kotlin Coroutines |
| 后台 | WorkManager（每日提醒、小组件跨天刷新） |
| 桌面组件 | AppWidget + RemoteViews（P1 独立模块，不使用 Glance） |
| 构建 | Gradle KTS + Version Catalog |

> 不使用任何跨平台框架（Flutter / React Native / uni-app）；不使用 Hilt / RxJava 等大纲外依赖。

## 模块结构

```
:app          主 App（Compose UI / ViewModel / 导航 / 深链）
:widget       P1 伴生模块（AppWidget + RemoteViews + 配置 Activity）
:core-data    Room / DAO / Repository / 导入解析器
:core-domain  艾宾浩斯-Δ 算法（纯 Kotlin，零 Android 依赖，可单测）
```

依赖方向单向：`:app → :core-data → :core-domain`，`:widget → :core-data → :core-domain`，`:app` 与 `:widget` 互不依赖。

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-需求规格说明书](docs/01-需求规格说明书.md) | 范围界定、FR/NFR、边界值、20 条验收标准、追溯矩阵 |
| [02-功能清单与伴生模块设计](docs/02-功能清单与伴生模块设计.md) | 28 项功能清单、模块划分、P1 小组件设计、复杂度自检 |
| [03-仓库与分支规范](docs/03-仓库与分支规范.md) | 目录结构、分支规划、提交规范、16 次提交清单 |

## 进度

| 阶段 | 内容 | 提交 | 实验课 | 状态 |
|---|---|---|---|---|
| 1 | 需求定义与项目初始化 | #1 | 实验 1 前置 | ✅ 已完成 |
| 2 | 工程骨架与依赖配置 | #2 | 实验 1 | ⏳ |
| 3 | 主题、导航与界面骨架 | #3 | 实验 2、3 | ⏳ |
| 4 | Room 数据层 | #4 | 实验 5 | ⏳ |
| 5 | 词库与单词列表 | #5、#6 | 实验 4 | ⏳ |
| 6 | 单词详情 | #7 | 实验 6 | ⏳ |
| 7 | 词库导入 | #8、#9、#10 | 实验 7 | ⏳ |
| 8 | 复习算法与队列 | #11、#12、#13 | 实验 7、8 | ⏳ |
| 9 | 桌面小组件与后台任务 | #14、#15 | 实验 8、9 | ⏳ |
| 10 | 权限降级与验收 | #16 | 实验 10 | ⏳ |

## 里程碑 tag

`lab01-env` → `lab02-ui` → `lab03-nav` → `lab04-list` → `lab05-room` → `lab06-viewmodel` → `lab07-coroutine` → `lab08-work` → `lab09-widget` → `lab10-final`

## 构建

```bash
# 需 Android Studio（Ladybug 及以上）与 JDK 17
./gradlew :app:assembleDebug      # 打包主 App
./gradlew :core-domain:test       # 算法单元测试
./gradlew test                    # 全模块测试
```

> 当前为第 1 阶段：仅文档与仓库骨架，Gradle 工程在第 2 阶段建立，故上述命令暂不可用。

## 提交规范

Conventional Commits + 中文正文，一次提交一件事。详见 [03-仓库与分支规范](docs/03-仓库与分支规范.md) 第 4 节。

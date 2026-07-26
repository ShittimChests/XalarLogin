# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

XalarLogin：Paper 26.2（Minecraft 新版本号方案）离线模式服务器的注册/登录认证插件。玩家用 `/reg <密码> <重复密码>` 注册、`/a <密码>` 登录，登录前被完全冻结。语言 Java（release 25），无测试套件。

## 构建

本机 PATH 里没有 java/mvn，工具链装在 `~/tools/`（无 sudo 免密，勿尝试 apt 安装）：

```bash
JAVA_HOME=~/tools/jdk-25.0.3+9 ~/tools/apache-maven-3.9.16/bin/mvn -B -ntp package
```

产物：`target/XalarLogin-1.0.0.jar`。仅依赖 `io.papermc.paper:paper-api:[26.2.build,)`（provided），无 shade、无第三方运行时依赖——SQLite 驱动（`org.sqlite.JDBC`）由 Paper 服务端自带，新增依赖前先确认服务端是否已内置。

网络注意：本机走 `127.0.0.1:7890` 代理，且本地 DNS 会把 Maven Central 解析到内网地址。Maven 代理已配置在 `~/.m2/settings.xml`（Maven 不读 `http_proxy` 环境变量，删掉该文件构建必挂）。直接跑 `java` 需要联网时要手动加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。

## 本地验证

无游戏客户端，验证方式是启动真实 Paper 服务端看插件加载与控制台命令。可复用 scratchpad 中的测试服（若已清理，从 `https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest` 取下载地址；需 `eula=true`、`online-mode=false`）。用命名管道保留控制台 stdin：

```bash
mkfifo console.in && (sleep 6000 > console.in &) && \
JAVA=~/tools/jdk-25.0.3+9/bin/java; $JAVA -Xmx2G -jar paper-26.2-*.jar --nogui < console.in
# 另一终端发命令：echo "xalar unregister Foo" > console.in ; echo stop > console.in
```

启动成功的标志：日志出现 `[XalarLogin] XalarLogin 已启用` 且 `plugins/XalarLogin/` 下生成 `config.yml` 和 `data.db`。玩家侧体验（冻结、提示、注册登录流程）只能由用户连服验收。

## 架构

核心是一个以 `SessionManager.Phase` 为中心的状态机：`LOADING → NEED_REGISTER | NEED_LOGIN → LOGGED_IN`。

- **`SessionManager`** — 每个在线玩家一个 `Session`：当前 phase、进服时从数据库缓存的 `passwordHash`（登录校验不再查库）、失败计数、提示/超时两个 `BukkitTask`。
- **`listener/RestrictionListener`** — 两个职责：(1) 会话生命周期——`initializePlayer` 在 Join（及 onEnable 时遍历在线玩家，兼容 /reload）异步查库后置 phase 并启动提示/超时任务；若开启 `ip-session-enabled` 且进服 IP 等于库中 `last_ip`，直接 `markLoggedIn` 免密放行；(2) 冻结——对未 `LOGGED_IN` 玩家取消移动/聊天/命令/交互/破坏/伤害等事件。命令白名单 `ALLOWED_COMMANDS` 必须与 plugin.yml 里 reg/a 命令及其别名保持同步。
- **`command/*`** — 四个命令类都遵循同一模式：主线程做参数与 phase 校验 → 异步线程做 PBKDF2 与 SQL → `runTask` 回主线程改状态、发消息。`RegisterCommand` 在异步处理期间把 phase 置回 `LOADING` 防止重复提交。
- **`storage/Database`** — 单连接 SQLite，方法 `synchronized`；单表 `accounts`，主键为玩家 UUID，管理员删号按 name（不区分大小写），因为离线/在线模式 UUID 不同。`last_ip` 列记录最近成功登录 IP（注册/登录时写入，`updatePassword` 会清空它使免密会话作废）；加列用构造器里的 `PRAGMA table_info` 迁移模式，新增列请沿用。
- **消息系统** — 所有文案在 `config.yml` 的 `messages.*`，`&` 颜色码经 `LegacyComponentSerializer.legacyAmpersand()` 转 Component。`XalarLoginPlugin.message()` 带前缀用于聊天，`bareMessage()` 无前缀用于踢出界面；占位符是成对变长参数 `("{min}", "6")`。新增消息务必同时加到 `config.yml`，缺 key 会显示「缺少消息配置」。

## 线程规则（改动时必须遵守）

- PBKDF2（约几十毫秒/次，故意的）和一切 SQL 只能在异步线程跑；`Session` 状态变更、`player.kick()`、事件处理只能在主线程。
- 异步回调回到主线程后必须重新拿 Session 并检查 `player.isOnline()` 与当前 phase——玩家可能已退服或状态已变。

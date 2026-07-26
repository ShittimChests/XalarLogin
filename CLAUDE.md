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

启动成功的标志：日志出现 `[XalarLogin] 存储后端: SQLITE` 与 `[XalarLogin] XalarLogin 已启用`，且 `plugins/XalarLogin/` 下生成 `config.yml` 和 `data.db`。玩家侧体验（冻结、提示、注册登录流程）只能由用户连服验收。

本机没有 MySQL 也没有可用的 Docker，**MySQL 后端的建表与增删改语句无法在本地跑通**，只能验到「驱动能加载 + URL 拼装正确 + 配置校验分支」。改动 MySQL 相关 SQL 后要如实说明这部分未经真实数据库验证。把 `storage.type` 设成 mysql 并指向一个没有监听的端口，报「Communications link failure」而不是「找不到 JDBC 驱动」，即可确认驱动可用。

验证密码哈希可以脱离服务端，用 Python 独立算一遍（存储格式 `迭代数:base64(salt):base64(hash)`）：

```python
hashlib.pbkdf2_hmac('sha256', pw.encode(), salt, iterations, dklen=32)
```

## 架构

核心是一个以 `SessionManager.Phase` 为中心的状态机：`LOADING → NEED_REGISTER | NEED_LOGIN → LOGGED_IN`。

- **`SessionManager`** — 每个在线玩家一个 `Session`：当前 phase、进服时从数据库缓存的 `passwordHash`（登录校验不再查库）、`busy` 抢占标志、提示/超时两个 `BukkitTask`。`create()` 返回它新建的实例本身，调用方要持有这个引用（见下方并发规则）。`markLoggedIn(Player)` 除了改 phase 还会 `player.updateCommands()`，把登录期间裁掉的补全列表还回去。
- **`LoginThrottle`** — 跨会话的密码失败计数与锁定，按玩家名和来源 IP 各记一份。失败计数**不能**放回 `Session`：那样踢出后重连就清零，等于可以无限爆破。纯内存，重启清空。两个维度的计数**分开返回、分开锁定**（`recordFailure` 返回 `Failures(byName, byIp)`，锁定走 `lockName`/`lockIp`），由 `LoginCommand` 各用各的阈值：玩家名用 `max-login-attempts`，IP 用它乘以 `ip-lockout-factor`（默认 5 倍）。别改回「取两者最大值套同一个阈值」——NAT/CGNAT 后面几十个玩家共用一个出口 IP，那样一个人输错三次就把整栋楼锁在门外。
- **`listener/RestrictionListener`** — 三个职责：(1) 锁定拦截——`onPreLogin` 在 `AsyncPlayerPreLoginEvent` 上查 `LoginThrottle`，被锁的直接 `disallow`，不让玩家进到世界里再踢（`PlayerLoginEvent` 在 Paper 26.2 已废弃，别用）；(2) 会话生命周期——`initializePlayer` 在 Join（及 onEnable 时遍历在线玩家，兼容 /reload）建会话、**立刻挂超时任务**、再异步查库后置 phase 并启动提示任务；若开启 `ip-session-enabled` 且进服 IP 等于库中 `last_ip`，直接 `markLoggedIn` 免密放行；(3) 冻结——对未 `LOGGED_IN` 玩家取消移动/聊天/命令/交互/破坏/伤害等事件。命令白名单由构造器从 `getCommand("reg"/"a")` 的名字与别名派生，改 plugin.yml 不需要再同步代码。
  - 超时任务要在**建会话时**就挂，不能等 phase 定下来：数据库卡住时会话会一直停在 `LOADING`，那样玩家会被无限期冻结在原地没人管。提示任务才需要等 phase（否则不知道该催 /reg 还是 /a）。
  - `ip-session-enabled` **默认 false**。离线模式下谁都能用别人的名字进服，而免密只比对出口 IP，等于同一 NAT 后面的人可以互相顶号并绕过 `LoginThrottle`。改默认值前先想清楚这点。
- **`command/*`** — 四个命令类都遵循同一模式：主线程做参数与 phase 校验 → 异步线程做 PBKDF2 与 SQL → `runTask` 回主线程改状态、发消息。`AdminCommand` 有 `unregister` 与 `passwd` 两个子命令；`passwd` 改完库之后必须同步在线玩家的 `session.passwordHash`，因为登录校验读的是会话缓存而不是数据库，不同步的话被改密的玩家仍能用旧密码登录。
- **`storage/Database` + `storage/Backend`** — 后端由 `config.yml` 的 `storage.type` 选（sqlite / mysql），两种 JDBC 驱动 Paper 都自带（`libraries/` 下的 sqlite-jdbc 与 mysql-connector-j），**不要为此加任何依赖**。方言差异集中在 `Backend` 枚举里（建表语句、`INSERT OR IGNORE` vs `INSERT IGNORE`、last_ip 列类型），其余 SQL 两边通用。单连接 + `synchronized`，每个操作走 `execute()` 包一层：MySQL 的连接会被服务端 `wait_timeout` 掐断，失败时验一次连接并重连重试（正常路径不调 `isValid()`，那是多余往返）。单表默认 `accounts`（MySQL 可改表名，会用白名单正则校验后拼进 SQL），主键为玩家 UUID，管理员按 name 操作（不区分大小写），因为离线/在线模式 UUID 不同。`register()` 用「冲突则忽略」并返回是否真的插入了——玩家在注册途中退服重连会让新会话查到「未注册」，裸 `INSERT` 之后会撞主键冲突把账号卡死。MySQL 侧**不要**把 `INSERT IGNORE` 换成 `INSERT ... ON DUPLICATE KEY UPDATE`：Connector/J 默认 `useAffectedRows=false` 返回「匹配行数」，冲突时也是非 0，`register()` 的「>0 即插入成功」契约就废了。`last_ip` 列记录最近成功登录 IP（注册/登录时写入，`updatePassword`/`updatePasswordByName` 会清空它使免密会话作废）；加列迁移用 `DatabaseMetaData.getColumns()`（两种后端通用，别退回 `PRAGMA`），注意它的表名/列名参数是 **LIKE pattern**，下划线要用 `getSearchStringEscape()` 转义，新增列请沿用 `escapePattern()`。
  - `storage.mysql.properties` 原样拼进 JDBC URL，所以过一层黑名单（`BANNED_PROPERTIES`）：`autoDeserialize` 等四个是连到恶意数据库时的 RCE / 任意文件读取入口，`databaseTerm` 会让 `getCatalog()` 返回 null 从而搞坏建表迁移。默认值用 `sslMode=PREFERRED`，别退回 `useSSL=false&allowPublicKeyRetrieval=true`——那个组合下中间人能还原出数据库密码。
  - **上层依赖这里的串行化**：进服的 `findAccount` 与 `/xalar passwd` 的 `updatePasswordByName` 之间有读改覆盖的窗口，现在被单连接 + `synchronized` 挡住了。换连接池之前必须先给 `initializePlayer` 的回调补会话级校验。
- **配置错误一律 fail loud** —— `Database.create` 抛异常时 `onEnable` 直接 `disablePlugin`，绝不退回 SQLite：那会让本该连 MySQL 的服务器悄悄建一个空库，表现成「所有人都没注册过」。
- **消息系统** — 所有文案在 `config.yml` 的 `messages.*`，`&` 颜色码经 `LegacyComponentSerializer.legacyAmpersand()` 转 Component。`XalarLoginPlugin.message()` 带前缀用于聊天，`bareMessage()` 无前缀用于踢出界面；占位符是成对变长参数 `("{min}", "6")`。**替换发生在反序列化之后**（`render()` 用 `Component.replaceText`），这样占位符的值是纯文本——先拼字符串再整体反序列化会让玩家名里的 `&` 变成颜色码。新增消息务必同时加到 `config.yml`，缺 key 会显示「缺少消息配置」。
- **密码哈希** — `PasswordHasher.hash(password, iterations)`，迭代数写进哈希串前缀，所以调 `password-hash-iterations` 不会让老账号失效。`hash()` 与 `verify()` 必须共用同一个 `[MIN_ITERATIONS, MAX_ITERATIONS]` 区间（走 `clampIterations()`）：只在 verify 侧设上限的话，配置里多打一个 0 就会写出 verify 永远拒绝的哈希，表现成「注册成功但密码永远错」，而且改密和管理员重设都会落进同一个坑。

## 线程与并发规则（改动时必须遵守）

- PBKDF2（默认 60 万迭代，约 0.2 秒/次，故意的）和一切 SQL 只能在异步线程跑；`Session` 状态变更、`player.kick()`、事件处理只能在主线程。**唯一的例外是 `onPreLogin`**（`AsyncPlayerPreLoginEvent` 本身就是异步的），所以它只读 `LoginThrottle`（ConcurrentHashMap）和配置，绝不碰 `Session`——那时 `Player` 对象还不存在。
- **任何会派发 PBKDF2/SQL 的命令都必须先 `session.busy.compareAndSet(false, true)` 抢占**，并在主线程回调里 `set(false)` 释放。没有这把锁，玩家连发命令就能同时排进 N 次 PBKDF2 打满异步线程池，还能在失败计数生效前并发试出远超上限的密码。
- 异步回调回到主线程后，用 **`plugin.sessions().get(uuid) != session`** 比对会话实例，而不是只看 phase。玩家退服重连后 map 里换成了新会话，它同样处于 `LOADING`，光看 phase 会把上一次连接的数据用到这次身上。同时仍要检查 `player.isOnline()` 与当前 phase。
- 事件处理器一律 `EventPriority.LOWEST`（退服清理用 `MONITOR`）：认证判定必须早于第三方插件，它们大多不看事件是否被取消。

## 已知的、插件修不了的问题

Paper 在构造 `PlayerCommandPreprocessEvent` **之前**就把命令原文写进日志（`ServerGamePacketListenerImpl` 里 `SpigotConfig.logCommands` 的判断早于事件），所以 `/reg`、`/a`、`/changepw`、`/xalar passwd` 的密码必然明文落盘，取消事件也拦不住。唯一办法是把 `spigot.yml` 的 `commands.log` 设为 `false`；`onEnable` 里的 `warnIfCommandLoggingEnabled()` 会在没改的时候打警告。不要试图用插件代码「修复」它。

离线模式下任何人都能用任意名字进服，所以**别人可以故意用某个玩家的名字连错密码，把这个名字锁定**。不按名字锁的话换个 IP 就能绕过节流，所以这是必要的代价，不是缺陷。管理员用 `/xalar passwd` 或 `/xalar unregister` 可以立即解锁。

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

XalarLogin：Paper 26.2（Minecraft 新版本号方案）离线模式服务器的注册/登录认证插件。玩家用 `/reg <密码> <重复密码>` 注册、`/a <密码>` 登录，登录前被完全冻结。语言 Java（release 25），无测试套件。

## 构建

本机 PATH 里没有 java/mvn，工具链装在 `~/tools/`（无 sudo 免密，勿尝试 apt 安装）：

```bash
JAVA_HOME=~/tools/jdk-25.0.3+9 ~/tools/apache-maven-3.9.16/bin/mvn -B -ntp package
```

产物：`target/XalarLogin-1.0.0.jar`。运行时仅依赖 `io.papermc.paper:paper-api:[26.2.build,)`（provided），无 shade、无第三方运行时依赖——SQLite 驱动（`org.sqlite.JDBC`）由 Paper 服务端自带，新增依赖前先确认服务端是否已内置。JUnit 是 `test` 范围，不进 jar。

`mvn test` 只跑不依赖 Bukkit 的纯逻辑（`LoginThrottle` 的时间窗口与阈值判定、`PasswordHasher`、`Backend`、`Database` 的配置校验与 URL 拼装），52 个测试约 2 秒，改这几个类后务必跑一遍。新增回归测试后请做一次变异验证（把修复撤掉，确认测试变红，再恢复）——**用文件备份来回滚，别用 `git checkout <file>`**，那会把工作区里其它未提交的改动一起冲掉。`LoginThrottle` 的方法都把「当前时刻」当参数收，所以时间窗口能直接断言，不用等真实时钟——新增时序行为时请沿用这个设计。`Database.create` 的校验分支也能脱离服务端测：两个 JDBC 驱动都不在测试 classpath 上，所以「配置合法」的表现是报驱动缺失，靠这个差别就能把校验逻辑全覆盖。

网络注意：本机走 `127.0.0.1:7890` 代理，且本地 DNS 会把 Maven Central 解析到内网地址。Maven 代理已配置在 `~/.m2/settings.xml`（Maven 不读 `http_proxy` 环境变量，删掉该文件构建必挂）。直接跑 `java` 需要联网时要手动加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。

## 本地验证

无游戏客户端，验证方式是启动真实 Paper 服务端看插件加载与控制台命令。可复用 scratchpad 中的测试服（若已清理，从 `https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest` 取下载地址；需 `eula=true`、`online-mode=false`）。用命名管道保留控制台 stdin：

```bash
mkfifo console.in && (sleep 6000 > console.in &) && \
JAVA=~/tools/jdk-25.0.3+9/bin/java; $JAVA -Xmx2G -jar paper-26.2-*.jar --nogui < console.in
# 另一终端发命令：echo "xalar unregister Foo" > console.in ; echo stop > console.in
```

启动成功的标志：日志出现 `[XalarLogin] 存储后端: SQLITE` 与 `[XalarLogin] XalarLogin 已启用`，且 `plugins/XalarLogin/` 下生成 `config.yml` 和 `data.db`。玩家侧体验（冻结、提示、注册登录流程）只能由用户连服验收。

**MySQL 后端可以在本机真机验证**，不需要 sudo、不需要 Docker：起一个私有 mysqld 实例即可（系统装了 mysql-server，但 root 走 auth_socket 要 sudo，所以别用系统实例）。

```bash
MYD=<scratchpad>/mysql-test; mkdir -p $MYD/data $MYD/tmp
mysqld --no-defaults --initialize-insecure --datadir=$MYD/data --basedir=/usr --log-error=$MYD/init.log
mysqld --no-defaults --datadir=$MYD/data --basedir=/usr --port=13306 \
  --socket=/tmp/xl13306.sock --mysqlx=OFF --pid-file=/tmp/xl13306.pid \
  --log-error=$MYD/mysqld.log --tmpdir=$MYD/tmp --secure-file-priv=$MYD/tmp &
mysql --protocol=TCP -h 127.0.0.1 -P 13306 -u root -e "CREATE DATABASE xalarlogin"
```

socket 路径必须短（>107 字符 mysqld 会拒绝启动），所以放 `/tmp` 而不是 scratchpad 深目录。

两个 JDBC 驱动**不在 pom 里**（运行时由 Paper 的 `libraries/` 提供），跑集成验证时用 `mvn dependency:get` 单独取来手工拼 classpath，**不要为此改 pom**：

```bash
mvn dependency:get -Dartifact=com.mysql:mysql-connector-j:9.2.0
mvn dependency:get -Dartifact=org.xerial:sqlite-jdbc:3.49.1.0
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "target/classes:$(cat /tmp/cp.txt):<两个驱动 jar>" MysqlIT.java
```

改动 SQL 或 `Database` 后请跑一遍这类集成验证，尤其是**建表、迁移、`INSERT IGNORE` 的返回值契约**——这三样在单元测试里看不出问题。已验证过的关键事实：Connector/J 9.2.0 默认配置下 `INSERT IGNORE` 冲突时确实返回 0；`getColumns` 的 catalog 参数是**等值比较不是 LIKE 模式**（见下方 `Database` 一节）。

验证密码哈希可以脱离服务端，用 Python 独立算一遍（存储格式 `迭代数:base64(salt):base64(hash)`）：

```python
hashlib.pbkdf2_hmac('sha256', pw.encode(), salt, iterations, dklen=32)
```

## 架构

核心是一个以 `SessionManager.Phase` 为中心的状态机：`LOADING → NEED_REGISTER | NEED_LOGIN → LOGGED_IN`。

- **`SessionManager`** — 每个在线玩家一个 `Session`：当前 phase、进服时从数据库缓存的 `passwordHash`（登录校验不再查库）、`busy` 抢占标志、提示/超时两个 `BukkitTask`。`create()` 返回它新建的实例本身，调用方要持有这个引用（见下方并发规则）。`markLoggedIn(Player)` 除了改 phase 还会 `player.updateCommands()`，把登录期间裁掉的补全列表还回去。
- **`LoginThrottle`** — 跨会话的密码失败计数与锁定，按玩家名和来源 IP 各记一份。失败计数**不能**放回 `Session`：那样踢出后重连就清零，等于可以无限爆破。纯内存，重启清空。两个维度的计数**分开返回、分开锁定**（`recordFailure` 返回 `Failures(byName, byIp)`，锁定走 `lockName`/`lockIp`），阈值判定在 `LoginThrottle.exceeded()`：玩家名用 `max-login-attempts`，IP 用它乘以 `ip-lockout-factor`（默认 5 倍）。别改回「取两者最大值套同一个阈值」——NAT/CGNAT 后面几十个玩家共用一个出口 IP，那样一个人输错三次就把整栋楼锁在门外。判定特意留在这个类里而不是 `LoginCommand`，就是为了能脱离 Bukkit 测试（`LoginThrottleTest.exceededUsesSeparateThresholds` 钉住了它）。
  - **自动路径上只能用 `clearName()`，绝不能清 IP 维度。** 离线模式下注册账号零成本，如果「成功登录」能清 IP 计数，攻击者随时可以注册一个自己的名字登录一次把它归零，`ip-lockout-factor` 就形同虚设。IP 计数平时只靠保留期自然过期与锁定到期归零来回收。`clearIp()` 存在，但**只给 `/xalar unlock` 这条管理员显式命令用**——别把它挂到登录/注册成功之类的自动路径上。要整体放宽就调 `ip-lockout-factor`，设 0 即完全关闭。
  - `lock(key, now, 0)` 表示**只归零、不锁定**（`lockedUntil == now`，`remainingLockSeconds` 立刻返回 0）。`lockout-seconds: 0` 走的就是这条：玩家能立即重连，且重连后仍有完整次数。不这么做的话计数会一直停在阈值上，他每次只剩一次机会。
  - `lock()` 里的 `counter.failures = 0` **不能删**。计数的归零不靠 `recordFailure` 的保留期自然过期：那要求保留期严格短于锁定时长，而 `LoginCommand` 给保留期设了 60 秒下限（`Math.max(lockoutSeconds, 60L)`）。`lockout-seconds` 填任何小于 60 的值时，锁定到期后第一次输错就会因为计数还停在阈值上而立刻再锁一轮，无限循环，玩家每个窗口只剩一次机会。`LoginThrottleTest.lockoutExpiryResetsCounter` 钉住了这条。
  - `Counter.lockedUntil` 必须是 `volatile`：写在主线程，而 `remainingLockSeconds` 是 `onPreLogin` 从异步线程读的。`ConcurrentHashMap` 只保证 map 结构本身的可见性，不保证 `computeIfAbsent` 之后对 Counter 内部普通字段的写对其他线程可见——漏了它，进服拦截可能读到过期的 0 而放行已被锁定的来源。
- **`listener/RestrictionListener`** — 三个职责：(1) 锁定拦截——`onPreLogin` 在 `AsyncPlayerPreLoginEvent` 上查 `LoginThrottle`，被锁的直接 `disallow`，不让玩家进到世界里再踢（`PlayerLoginEvent` 在 Paper 26.2 已废弃，别用）；(2) 会话生命周期——`initializePlayer` 在 Join（及 onEnable 时遍历在线玩家，兼容 /reload）建会话、**立刻挂超时任务**、再异步查库后置 phase 并启动提示任务；若开启 `ip-session-enabled` 且进服 IP 等于库中 `last_ip`，直接 `markLoggedIn` 免密放行；(3) 冻结——对未 `LOGGED_IN` 玩家取消移动/聊天/命令/交互/破坏/伤害等事件。
  - **命令白名单存的是 `Command` 实例，不是字符串**（构造器从 `getCommand("reg"/"a")` 取），判定走 `isAuthCommand()`：拿服务端自己的命令表把标签解析成 `Command` 再比对。别改回「剥掉命名空间比字符串」——那样只要服务器上还有任何一条叫 `a`/`l`/`login`/`reg`/`register` 的第三方命令，未认证玩家用 `/<plugin>:<cmd>` 就能把它执行掉，而离线模式下他还可以顶着管理员的名字进来。
  - 冻结类 handler **不加 `ignoreCancelled = true`**。聊天频道、命令别名之类的插件常见做法是「先取消事件、再自己把内容放出去」，它们如果也在 LOWEST 且先于本插件加载，加了这个开关就会让本插件被跳过，正好抵消选 LOWEST 的意义。
  - `PlayerTeleportEvent` 与 `PlayerPortalEvent` **各自带 HandlerList**，`onMove` 收不到，必须分别注册（已注册）。**副作用是未认证期间一切传送都被取消**，hub / spawn-on-join 类插件在 `PlayerJoinEvent` 里的落点传送会失效，需要改成登录成功后触发——这是有意接受的代价，放行任何一类传送都等于让第三方插件能把未认证玩家搬走。`EntityDamageByEntityEvent` 相反，它没有独立 HandlerList，所以和 `EntityDamageEvent` 的 handler 是安全的冗余。新增冻结项前先用 `javap` 确认目标事件有没有自己的 `getHandlerList()`。
  - `onCommandSend` 跑 **HIGHEST** 而不是 LOWEST：它不做认证判定，只删集合元素，早跑的话后面的监听器还能加回去。补全建议另走 `onAsyncTabComplete`（`AsyncTabCompleteEvent`），不拦的话未认证玩家按 Tab 就能拿到全服玩家名与传送点。
  - 判定有两个：`isRestricted()` 对没有会话的玩家一律返回 true（**fail-closed，不要在这里加任何放宽**）；`isRestrictedEntity()` 在它之上多一条「服务端在线玩家表里查得到同一个实例」，**只给参数是任意 Player 实体的那几个 handler 用**（inventory click/drag/open、pickup、damage、damageByEntity）。Citizens 之类的玩家型 NPC 也是 Player，却不走登录流程、永远没有会话，只按 `isRestricted` 判会让打向 NPC 的伤害全被取消。
    - 判据**不要**换成 `getAddress() != null`：Paper 允许把 `server-ip` 配成 unix domain socket（本机 Velocity/BungeeCord 的常见部署），那种部署下 `getAddress()` 对每一个真实玩家都返回 null，整条判定会当场 fail-open。
    - 这条放宽**不许**用在传送冻结（`onTeleport`/`onPortal`）、移动、聊天、命令上——那些是纯认证执行路径，必须 fail-closed。曾经按「Citizens 也会传送 NPC」的理由把它挪过去，是错的。
  - 超时任务要在**建会话时**就挂，不能等 phase 定下来：数据库卡住时会话会一直停在 `LOADING`，那样玩家会被无限期冻结在原地没人管。提示任务才需要等 phase（否则不知道该催 /reg 还是 /a）。
  - `ip-session-enabled` **默认 false**。离线模式下谁都能用别人的名字进服，而免密只比对出口 IP，等于同一 NAT 后面的人可以互相顶号并绕过 `LoginThrottle`。改默认值前先想清楚这点。
- **`command/*`** — 四个命令类都遵循同一模式：主线程做参数与 phase 校验 → 异步线程做 PBKDF2 与 SQL → `runOnMain` 回主线程改状态、发消息。`AdminCommand` 有 `unregister`、`passwd`、`unlock` 三个子命令；`passwd` 改完库之后必须同步在线玩家的 `session.passwordHash`，因为登录校验读的是会话缓存而不是数据库，不同步的话被改密的玩家仍能用旧密码登录。
  - `passwd` 会先 `claimOnlineSessions()` 抢占所有在线同名玩家的 `session.busy`（和 `/changepw` 争同一把锁），任一个正忙就整条命令退回并回 `admin-target-busy`。不互斥的话，管理员改密与玩家自己改密并撞时数据库留最后写入的哈希、会话缓存留最后回调的，两者会对不上。
  - `unlock` 是 **IP 维度锁定唯一的定向解除手段**（`LoginThrottle.clearIp()` 只允许它调）。共用出口 IP 的服务器越线后，不给这个出口的话管理员只能干等锁定到期。别把 `clearIp()` 挂到登录成功之类的自动路径上，那正是 `clearName` 注释里说的那个洞。
  - 两个子命令的 `throttle().clearName()` 都要放在「找不到记录」的提前 return **之前**：离线模式下别人可以拿一个名字反复输错把它锁掉，而管理员多半正是为了解锁才敲命令；放在后面的话账号已不存在的名字就解不开了。
- **`storage/Database` + `storage/Backend`** — 后端由 `config.yml` 的 `storage.type` 选（sqlite / mysql），两种 JDBC 驱动 Paper 都自带（`libraries/` 下的 sqlite-jdbc 与 mysql-connector-j），**不要为此加任何依赖**。方言差异集中在 `Backend` 枚举里（建表语句、`INSERT OR IGNORE` vs `INSERT IGNORE`、last_ip 列类型），其余 SQL 两边通用。单连接 + `synchronized`，每个操作走 `execute()` 包一层：MySQL 的连接会被服务端 `wait_timeout` 掐断，失败时验一次连接并重连重试（正常路径不调 `isValid()`，那是多余往返）。单表默认 `accounts`（MySQL 可改表名，会用白名单正则校验后拼进 SQL），主键为玩家 UUID，管理员按 name 操作（不区分大小写），因为离线/在线模式 UUID 不同。`register()` 用「冲突则忽略」并返回是否真的插入了——玩家在注册途中退服重连会让新会话查到「未注册」，裸 `INSERT` 之后会撞主键冲突把账号卡死。MySQL 侧**不要**把 `INSERT IGNORE` 换成 `INSERT ... ON DUPLICATE KEY UPDATE`：Connector/J 默认 `useAffectedRows=false` 返回「匹配行数」，冲突时也是非 0，`register()` 的「>0 即插入成功」契约就废了。`last_ip` 列记录最近成功登录 IP（注册/登录时写入，`updatePassword`/`updatePasswordByName` 会清空它使免密会话作废）。`updateLastLogin` 带 `AND password_hash = ?` 条件：它是登录成功后异步补写的、不受 `session.busy` 保护，没这个条件的话可能在管理员刚清空 `last_ip` 之后又把它写回去；加列迁移用 `DatabaseMetaData.getColumns()`（两种后端通用，别退回 `PRAGMA`），注意它的表名/列名参数是 **LIKE pattern**，下划线要用 `getSearchStringEscape()` 转义，新增列请沿用 `escapePattern()`。
  - `storage.mysql.properties` 原样拼进 JDBC URL，所以过一层黑名单（`BANNED_PROPERTIES`）：`autoDeserialize` 等四个是连到恶意数据库时的 RCE / 任意文件读取入口，`databaseTerm` 会让 `getCatalog()` 返回 null 从而搞坏建表迁移。默认值用 `sslMode=PREFERRED`，别退回 `useSSL=false&allowPublicKeyRetrieval=true`——那个组合下中间人能还原出数据库密码。
  - **`host` / `database` / `port` 也要校验**（`SAFE_HOST` / `SAFE_DATABASE`），它们和 `properties` 拼进同一个 URL：只过滤 `properties` 的话，`database: 'xalarlogin?allowLoadLocalInfile=true'` 就绕过整份黑名单了。
  - `withTimeoutDefaults()` 在管理员没显式配置时补上 `connectTimeout=5000&socketTimeout=30000`。Connector/J 的读超时默认不限时，而 `execute()` 是 `synchronized` 的——一个挂住的读会一直占着这把锁，登录全卡在 LOADING，`onDisable` 里的 `close()` 也得排在它后面，`SHUTDOWN_DRAIN_MILLIS` 那 2 秒上限就形同虚设（那个上限只约束轮询循环，不约束 `close()`）。补在代码里而不是只写进 `config.yml`，是为了让升级上来的老配置也受益。
  - `execute()` 的 action **必须幂等**：重试跑的是整个 action，而语句可能是「服务端已执行成功、只是返回途中连接断了」。已知边角：`deleteByName` 重跑返回 0（管理员会看到「未找到玩家」而记录其实已删）、`migrateAddLastIp` 的 ALTER 重跑会撞重复列。
  - **这里的串行化只保证 SQL 的先后，保证不了主线程回调的先后。** 进服的 `findAccount` 与 `/xalar passwd` 的 `updatePasswordByName` 之间那个读改覆盖窗口**不是**被单连接挡住的（两个 worker 从 SQL 返回到 `runTask` 之间没有同步），挡住它的是 `Session.passwordGeneration`。见上面的线程规则。
  - `migrateAddLastIp` 里 `getColumns` 的 **catalog 参数不要转义**：JDBC 规范里它是精确名称不是 LIKE 模式，只有 schema/table/column 三个 pattern 参数才需要 `escapePattern()`。转义过的库名在 Connector/J 的等值比较下一条都匹配不到，于是每次启动都判为「缺列」，第二次启动 ALTER 就撞 `Duplicate column name` 直接停用插件。这条在真实 MySQL 8.0.46 上验证过两种写法的差别。
  - `close()` 之后 `execute()` 会**抛异常而不是重开连接**（`closed` 标志）。关服时 `awaitPendingTasks()` 只等 2 秒，晚到的调用如果重开一条，那条连接没有任何人会再关，`/reload` 反复触发就是每次泄漏一条，而且它会和新实例的 `Database` 同时写同一个库。
  - `updatePassword` / `updatePasswordByName` / `deleteByName` 都返回**实际影响的行数**，调用方必须把 0 当失败处理。多服共用一套 MySQL 时另一台服务器可能刚把这个账号删掉，把 0 当成功的话玩家会看到「密码修改成功」，下次进服才发现账号根本不存在。
- **配置错误一律 fail loud** —— `Database.create` 抛异常时 `onEnable` 直接 `disablePlugin`，绝不退回 SQLite：那会让本该连 MySQL 的服务器悄悄建一个空库，表现成「所有人都没注册过」。
  - **停用必须先把未认证玩家踢下线**（`onDisable` 里的 `kickUnauthenticated()`）。停用会摘掉所有冻结 handler、取消超时任务，留在服务器里的未认证玩家会当场解冻——离线模式下那可能是一个正顶着管理员名字的人。fail loud 的前提是停用等于关门，而不是开门。`sessions == null` 时不踢：那表示本实例从未冻结过任何人（插件是在玩家已在线时才首次加载的），那些玩家不归我们管。
- **默认值的改动够不到存量用户。** `saveDefaultConfig()` 只在文件不存在时写入，所以改 `config.yml` 里的默认值对已经跑起来的服务器毫无作用。安全相关的默认值变更必须配一条启动警告（现有的 `warnIfIpSessionEnabled()` 就是为此存在的——`ip-session-enabled` 的默认值从 true 改成了 false，但老配置里仍然是 true）。
- **消息系统** — 所有文案在 `config.yml` 的 `messages.*`，`&` 颜色码经 `LegacyComponentSerializer.legacyAmpersand()` 转 Component。`XalarLoginPlugin.message()` 带前缀用于聊天，`bareMessage()` 无前缀用于踢出界面；占位符是成对变长参数 `("{min}", "6")`。**替换发生在反序列化之后**（`render()` 用 `Component.replaceText`），这样占位符的值是纯文本——先拼字符串再整体反序列化会让玩家名里的 `&` 变成颜色码。新增消息务必同时加到 `config.yml`。
  - **`rawMessage()` 必须用单参数的 `getConfig().getString(path)`。** 带默认值的重载走 `MemorySection.get(path, def)`，直接返回传入的默认值，**不查** `JavaPlugin.reloadConfig()` 注册的 jar 内默认配置。用错重载的话，升级上来的老 `config.yml` 里每一个新增 key 都会显示成「缺少消息配置: xxx」而不是内置文案——`saveDefaultConfig()` 只在文件不存在时才写，老用户的文件不会被补。同理，新增**非** messages 的配置项时，代码里的硬编码默认值必须和 `config.yml` 里写的完全一致，否则老用户拿到的是前者。
- **密码哈希** — `PasswordHasher.hash(password, iterations)`，迭代数写进哈希串前缀，所以调 `password-hash-iterations` 不会让老账号失效。`hash()` 与 `verify()` 必须共用同一个 `[MIN_ITERATIONS, MAX_ITERATIONS]` 区间（走 `clampIterations()`）：只在 verify 侧设上限的话，配置里多打一个 0 就会写出 verify 永远拒绝的哈希，表现成「注册成功但密码永远错」，而且改密和管理员重设都会落进同一个坑。

## 线程与并发规则（改动时必须遵守）

- PBKDF2（默认 60 万迭代，约 0.2 秒/次，故意的）和一切 SQL 只能在异步线程跑；`Session` 状态变更、`player.kick()` 只能在主线程。
- **有三个事件处理器本身就跑在异步线程上**：`onPreLogin`（`AsyncPlayerPreLoginEvent`）、`onChat`（`AsyncChatEvent`，Paper 的玩家聊天就是异步事件）、`onAsyncTabComplete`。它们只允许读 `LoginThrottle`（ConcurrentHashMap）、读会话的 volatile 字段、读配置，以及 Adventure 的 `sendMessage`；**不能改 `Session` 状态、不能 `kick()`**。`onPreLogin` 更是连 `Session` 都碰不得——那时 `Player` 对象还不存在。别再写「onPreLogin 是唯一的异步 handler」，那句话曾经写进注释又被照抄进本文件，而 `onChat` 一直是异步的。
- **异步回调回主线程一律走 `plugin.runOnMain(...)`，不要直接 `getScheduler().runTask(...)`。** 关服时 `awaitPendingTasks()` 等的就是这些任务，而它们收尾时 `isEnabled()` 已经是 false，`CraftScheduler` 会抛 `IllegalPluginAccessException`，在控制台留下一条像是数据库出错的异常栈。`runOnMain` 负责把它挡掉。
- **任何会派发 PBKDF2/SQL 的命令都必须先 `session.busy.compareAndSet(false, true)` 抢占**，并在主线程回调里 `set(false)` 释放。没有这把锁，玩家连发命令就能同时排进 N 次 PBKDF2 打满异步线程池，还能在失败计数生效前并发试出远超上限的密码。
- 异步回调回到主线程后，用 **`plugin.sessions().get(uuid) != session`** 比对会话实例，而不是只看 phase。玩家退服重连后 map 里换成了新会话，它同样处于 `LOADING`，光看 phase 会把上一次连接的数据用到这次身上。同时仍要检查 `player.isOnline()` 与当前 phase。
- **「先读库、再写回会话」的路径还要比对 `session.passwordGeneration`。** 派发异步查询前记下代号，回调里发现变了就放弃写回。会话实例比对挡不住这一类：实例没变，但 `/xalar passwd` 已经在这期间把新哈希写进同一个会话了。**不要**用「单连接 + `synchronized` 保证了回调顺序」来省掉这个判断——串行化只管 SQL 的先后，两个 worker 从 SQL 返回到调用 `runTask` 之间没有任何同步，调度器按 `runTask` 的调用顺序排队。改写会话哈希请一律走 `Session.setPasswordHash()`（它负责推进代号），只有进服加载那条路径直接写字段，因为它得先比对代号。
- 事件处理器一律 `EventPriority.LOWEST`（退服清理用 `MONITOR`）：认证判定必须早于第三方插件，它们大多不看事件是否被取消。

## 已知的、插件修不了的问题

Paper 在构造 `PlayerCommandPreprocessEvent` **之前**就把命令原文写进日志（`ServerGamePacketListenerImpl` 里 `SpigotConfig.logCommands` 的判断早于事件），所以 `/reg`、`/a`、`/changepw`、`/xalar passwd` 的密码必然明文落盘，取消事件也拦不住。唯一办法是把 `spigot.yml` 的 `commands.log` 设为 `false`；`onEnable` 里的 `warnIfCommandLoggingEnabled()` 会在没改的时候打警告。不要试图用插件代码「修复」它。

离线模式下任何人都能用任意名字进服，所以**别人可以故意用某个玩家的名字连错密码，把这个名字锁定**。不按名字锁的话换个 IP 就能绕过节流，所以这是必要的代价，不是缺陷。管理员用 `/xalar passwd` 或 `/xalar unregister` 可以立即解锁。

# XalarLogin

离线模式（offline-mode）Paper 服务器的注册/登录认证插件。玩家进服后先注册或登录，否则完全无法行动，防止账号被冒名顶替。

- 适用服务端：**Paper 26.2**（需 Java 25 运行环境）
- 数据存储：**SQLite 或 MySQL 任选**，两种驱动都由 Paper 自带，零外部依赖
- 密码安全：PBKDF2-SHA256 加盐哈希（默认 60 万次迭代），不存明文

---

## 玩家指南

### 第一次进服（注册）

进服后你会被冻结（不能移动、聊天、互动），每隔几秒收到提示。输入：

```
/reg 你的密码 再输一遍密码
```

例如：`/reg 123456 123456`。两次密码必须一致，默认至少 6 位。注册成功后自动登录，直接开玩。

### 之后进服（登录）

```
/a 你的密码
```

例如：`/a 123456`。也可以用 `/l` 或 `/login`。

### 同 IP 免密登录

用密码成功登录一次后，**下次从同一个网络（IP 相同）进服会自动登录**，不用再输密码。换了网络（IP 变化）或改过密码后，需要重新输入一次密码。

### 修改密码

登录后输入：

```
/changepw 旧密码 新密码
```

改完密码后，下次进服需要重新输一次新密码。

### 注意事项

- 进服后 **60 秒内**未注册/登录会被踢出，重新进服即可
- 密码输错 **3 次**会被踢出，并且 **5 分钟内无法再用这个名字进服**（重连不会重置次数）
- 忘记密码请联系管理员重置账号

---

## 管理员指南

### 安装

1. 把 `XalarLogin-1.0.0.jar` 放入服务器的 `plugins/` 文件夹
2. **把服务器根目录 `spigot.yml` 里的 `commands.log` 改为 `false`**（见下方「必做」）
3. 重启服务器，看到日志 `[XalarLogin] XalarLogin 已启用` 即成功
4. 首次启动会自动生成 `plugins/XalarLogin/config.yml`（配置）和 `data.db`（账号数据库）

插件升级：直接覆盖旧 jar 重启即可，数据库结构变更会自动迁移。

#### ⚠️ 必做：关闭命令日志，否则密码明文落盘

Paper 会把玩家执行的每条命令原文写进 `logs/latest.log` 和控制台，也就是说：

```
[12:34:56 INFO]: Steve issued server command: /a 我的密码
```

**插件无法拦截这条日志** —— 服务端写日志的时机早于插件事件触发（`SpigotConfig.logCommands`
的判断在 `PlayerCommandPreprocessEvent` 构造之前），取消事件也来不及。日志文件通常权限宽松、
会被打包发给别人排障、可能被日志上报插件转发，等于密码直接泄露。

四条带密码的命令全部受影响：`/reg`、`/a`、`/changepw`，以及管理员的 `/xalar passwd`。

唯一的办法是在服务器根目录的 `spigot.yml` 里改：

```yaml
commands:
  log: false
```

改完重启服务器。插件在启动时会检查这一项，仍为 `true` 时会在控制台打出醒目的安全警告。

### 管理命令

| 命令 | 说明 |
|---|---|
| `/xalar unregister <玩家名>` | 删除该玩家的账号记录。玩家若在线会被踢出，重新进服后需重新注册 |
| `/xalar passwd <玩家名> <新密码>` | 直接把该玩家的密码改成新密码。玩家**不需要在线**，也不需要知道旧密码 |
| `/xalar unlock <玩家名\|IP>` | 解除登录失败锁定。参数填玩家名或 IP 都行，两个维度都会清 |

`unregister` 与 `passwd` 的玩家名都不区分大小写，都会顺带解除该**玩家名**的锁定。

**`unlock`** 是解除 **IP 维度**锁定的唯一手段：IP 计数平时不会因为谁登录成功而清零（理由见「防爆破」一节），
所以共用出口 IP 的网络被整体锁住时，用它可以立刻放行，不必等锁定自然到期。

**`passwd`** 用于玩家忘记密码——比 `unregister` 更省事，玩家不用重新注册，
背包、权限之类挂在 UUID 上的东西也不受影响。执行后：

- 该玩家的免密登录立即作废，下次进服必须输新密码
- 如果玩家正在线上，会收到「管理员已重设你的密码」的提示，且会话内立刻改用新密码

> ⚠️ 新密码会出现在你的命令里，也就会进控制台和日志（同上面「关闭命令日志」一节）。
> 建议改完后让玩家自己用 `/changepw` 再改一次。

`unregister` 与 `passwd` 的回显都会带上实际影响的条数。离线模式下 `Steve` 和 `steve` 是两个
不同 UUID、两个独立账号，而这两条命令按名字忽略大小写，所以看到「共 2 条」说明服务器上存在
大小写不同的同名账号，两个都被改/删了 —— 留意是不是误操作。

权限：`xalarlogin.admin`（默认仅 OP），控制台也可执行。

### 存储后端：SQLite 或 MySQL

默认用 SQLite，单文件零配置，绝大多数单服直接用就行。**多个服务器要共享同一份账号**时改用 MySQL。
两种驱动 Paper 都自带（`libraries/` 下的 sqlite-jdbc 与 mysql-connector-j），切换不用装任何东西。

```yaml
storage:
  type: mysql          # sqlite 或 mysql
  mysql:
    host: localhost
    port: 3306
    database: xalarlogin      # 必须是已经存在的库
    user: xalarlogin
    password: '你的密码'
    table: accounts           # 表名，插件会自动创建
    properties: 'sslMode=PREFERRED&characterEncoding=utf8&serverTimezone=UTC'
```

要点：

- **库要你自己先建好**，插件只自动建表不建库：
  `CREATE DATABASE xalarlogin DEFAULT CHARSET utf8mb4;`
- `table` 只允许字母、数字、下划线。多个服务器想各用各的账号，改这个值区分即可
- `host`、`database`、`port` 也会被校验（这三个和 `properties` 一样是拼进 JDBC URL 的，
  不卡住的话 `database: 'xalarlogin?allowLoadLocalInfile=true'` 就能绕过下面那份参数黑名单）。
  IPv6 地址请用方括号包起来，例：`host: '[2001:db8::1]'`
- 改完 `storage` 段**必须重启服务器**。`/reload` 其实会按新配置重新连接数据库，但配置写错时插件会当场停用，而停用会把所有正在冻结中的未认证玩家断开——有人在线时别用 `/reload` 折腾存储配置
- 配置写错（库名为空、后端名拼错、连不上）时插件会**直接停用并在控制台说明原因**，
  不会静默退回 SQLite —— 那样会让本该连 MySQL 的服务器悄悄建一个空库，
  表现成「所有人都没注册过」，比直接报错危险得多
- 切换后端**不会自动迁移已有数据**。SQLite 的数据在 `plugins/XalarLogin/data.db`，
  需要迁移的话得自己把 `accounts` 表导过去
- **`properties` 里不要关 TLS。** 默认的 `sslMode=PREFERRED` 表示数据库支持 TLS 就加密。
  网上常见的 `useSSL=false&allowPublicKeyRetrieval=true` 千万别抄：MySQL 8 默认的
  `caching_sha2_password` 认证下，这个组合允许中间人塞入自己的 RSA 公钥，从而还原出你的
  数据库密码。数据库和服务端不在同一台机器上时尤其危险
- 出于同样的理由，`autoDeserialize`、`allowLoadLocalInfile`、`allowLoadLocalInfileInPath`、
  `allowUrlInLocalInfile`、`allowMultiQueries`、`databaseTerm`、`propertiesTransform`、
  `socketFactory`、`queryInterceptors` 这几个参数会被插件直接拒绝并在启动时报错。
  含百分号（`%`）的 `properties` 也会被拒绝——驱动会对它做解码，否则可以借此绕过上面这份名单
- 没写 `connectTimeout` / `socketTimeout` 时插件会补上 **5 秒 / 30 秒**。Connector/J 的读超时
  默认不限时，数据库变成网络黑洞（丢包而不是拒连）时一次查询能挂到 TCP 自己放弃为止，期间
  所有人的登录都卡住、关服也得排在它后面。要用别的值就在 `properties` 里显式写

### 配置项（config.yml）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `storage.type` | sqlite | 存储后端，`sqlite` 或 `mysql` |
| `storage.mysql.*` | — | MySQL 连接信息，见上一节 |
| `login-timeout-seconds` | 60 | 进服后多少秒未认证被踢出 |
| `max-login-attempts` | 3 | 密码错误多少次被踢出并锁定该玩家名 |
| `lockout-seconds` | 300 | 锁定时长（秒），期间被锁定的玩家名 / IP 无法进服。设为 0 表示只踢出不锁定（可立即重连，且次数会重新给满）|
| `ip-lockout-factor` | 5 | IP 维度的锁定阈值 = `max-login-attempts` × 此值。设为 0 表示不按 IP 锁定 |
| `min-password-length` | 6 | 密码最小长度 |
| `remind-interval-seconds` | 5 | 未登录时的提示间隔（秒） |
| `ip-session-enabled` | **false** | 同 IP 免密登录开关，默认关闭，开启前请读下方安全权衡。**从旧版升级请手动改**，见下 |
| `password-hash-iterations` | 600000 | PBKDF2 迭代次数，越高越难爆破但登录越慢。有效范围 10 万 ~ 1000 万 |

关于 `password-hash-iterations`：迭代次数会写进每条密码哈希里，所以**调整它不会让任何老账号失效** ——
老密码继续按注册时的次数校验，只有新注册和改密码才会用上新值。60 万是 OWASP 对 PBKDF2-SHA256
的建议值，单次校验约 0.2 秒（跑在异步线程，不卡主线程）。低配机器可以适当调低。超出有效范围的
配置会被自动钳到边界，不会写出校验不了的哈希。

### 防爆破

密码错误次数按**玩家名**和**来源 IP** 分别累计，存在服务端内存里，**踢出后重连不会清零**。
成功登录会立即清除记录，服务端重启后计数清空。两个维度用**各自的阈值**：

- 玩家名：错 `max-login-attempts` 次（默认 3）即锁定该名字
- IP：错 `max-login-attempts × ip-lockout-factor` 次（默认 15）才锁定该地址

**锁定到期后计数归零**，玩家重新拿到完整的 `max-login-attempts` 次机会，不会「一进来输错一次就
又被锁五分钟」。`lockout-seconds` 设成 0 时同样会归零，只是不锁定、可以立即重连。
只有越线的那个维度会被锁：被 IP 阈值兜住时不会连带把玩家名也锁上，反之亦然。

成功登录只清除**该玩家名**的失败记录，不清除 IP 的。离线模式下注册账号是零成本的，如果成功登录
能清 IP 计数，攻击者随时可以注册一个自己的账号登录一次把它归零，这道防线就没用了。IP 计数靠
「保留期内没有新失败」和「锁定到期」自行回收。

IP 阈值之所以宽这么多，是因为宿舍、家庭 NAT、运营商 CGNAT 后面往往几十个玩家共用一个出口 IP。
两个维度共用阈值的话，一个人手滑输错三次就会把同网络的所有人一起锁在门外五分钟。
如果你的服务器面向一个封闭小圈子、不担心误伤，可以把 `ip-lockout-factor` 调小收紧。

> 注意：离线模式下任何人都能用任意名字进服，所以**别人可以故意用你的名字连错密码来锁定你的账号**。
> 这是玩家名维度锁定的固有代价（不按名字锁的话换个 IP 就绕过了）。遇到被针对的情况，
> 管理员可以用 `/xalar passwd` 或 `/xalar unregister` 立即解除该玩家的锁定。

所有玩家可见的文字都在 `messages` 段落里，支持 `&` 颜色代码，改完重启服务器生效。配置文件里缺少的项会自动使用插件内置默认值，升级后无需手动补条目。

### 关于同 IP 免密登录的安全权衡

**这个功能默认关闭（`ip-session-enabled: false`），开启前请读完本节。**

> ⚠️ **从旧版本升级的服务器请手动检查这一项。** 它以前的默认值是 `true`，而升级只会覆盖 jar、
> 不会改你已经存在的 `plugins/XalarLogin/config.yml`。仍然开着的话插件启动时会在控制台打一条
> 安全警告，看到就去把它改成 `false`。

免密判断依据只有一条：「上次成功登录的 IP == 本次进服 IP」。而离线模式下任何人都能用别人的
名字进服。两者相加意味着——在网吧、校园网、宿舍、家庭合租、运营商 CGNAT、共用 VPN 出口等
**多人共享同一出口 IP** 的环境里，同网络的其他人用你的名字进服就能直接顶号，不需要密码，
而且完全绕过上面那套失败锁定。

只有在你确信每个玩家都有独立公网 IP 时，才建议开启。

另外：修改密码（`/changepw` 与 `/xalar passwd`）会立即作废免密会话；管理员 `unregister` 会直接删号，
两者都会强制下次输密码。

### 数据说明

- SQLite 后端的账号数据在 `plugins/XalarLogin/data.db`（单文件），备份服务器时一并备份即可；
  MySQL 后端的数据在你自己的库里，按你的数据库备份策略走
- 密码以 PBKDF2-SHA256 加盐哈希存储，无法从数据库反查明文；玩家忘记密码用
  `/xalar passwd` 直接改一个新的，或用 `/xalar unregister` 让他重新注册
- 账号按玩家 UUID 记录（离线模式下由玩家名派生），改名等同于新账号
- 两种后端的表结构一致：`accounts(uuid, name, password_hash, registered_at, last_login, last_ip)`

---

## 从源码构建

需要 JDK 25 和 Maven：

```bash
mvn package
```

产物：`target/XalarLogin-1.0.0.jar`。唯一依赖 `paper-api`（provided），无需 shade。

---

### License

AAR

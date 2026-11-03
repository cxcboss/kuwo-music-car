# 其它 App / AI 对接指南

适用版本：**1.0.01 / 74001，音乐接口 API v3**。本文件给客户端开发者使用；修改酷我补丁工程先读 [AI 接手必读](../docs/AI接手必读.md)。完整机器合同见 [api-contract.json](api-contract.json)，全部方法见 [接入说明](接入说明.md)。v3 共 76 个入口，兼容 v1/v2。

## 接入前先确认

- 两个 App 安装在同一台 Android 设备，酷我包名为 `cn.kuwo.kwmusiccas`。
- 入口为 `content://cn.kuwo.kwmusiccar.bridge`，使用 `ContentResolver.call()` 和 `ResultReceiver`，不是 HTTP 服务。
- 默认允许 `com.cabindeck` 和酷我自身 UID。其它 App 必须先把实际包名加入酷我工程的 `config/bridge_clients.txt`，再由维护者重新构建、签名和安装。只改客户端参数不能取得访问权。
- 首次打开酷我并完成原隐私确认。账号和会员内容仍受酷我原权限限制。
- 客户端先读 `capabilities`，以实际返回的 `apiVersion` 和 `methods` 判断能力。1.0.0 的 API v2 没有本文件标注为“v3 新增”的方法。

不要在客户端复刻酷我私有 HTTP 请求、提取账号凭证或解析私有数据库。音乐检索和内容获取由酷我原业务客户端完成。

## 最小接入

复制 [KuwoBridgeClient.java](KuwoBridgeClient.java) 到客户端源码。其 Java package 为 `com.cabindeck.integration.kuwo`，其它项目可以按自身目录修改 package/import。Manifest 的 `queries` 位于 `manifest` 下，与 `application` 同级：

```xml
<queries>
    <provider android:authorities="cn.kuwo.kwmusiccar.bridge" />
    <package android:name="cn.kuwo.kwmusiccas" />
</queries>
```

```java
KuwoBridgeClient client = new KuwoBridgeClient(context);
client.getCapabilities(result -> {
    // OK 后检查 apiVersion、methods；不要仅凭已安装包名判断版本。
});
client.initialize(result -> {
    // serviceReady=true 才读取音乐内容；未就绪时读取 getStartupStatus。
});
client.getState(result -> {
    if (!"OK".equals(result.getString("status"))) return;
    Bundle state = result.getBundle("state");
    // state.song、state.playing、state.positionMs 等由客户端接入自己的状态层。
});
```

所有 `client.request(method, params, callback)` 的最终回调均在客户端主线程，返回 `status`、`requestId` 及业务字段。SDK 处理底层 `ACCEPTED` 和回调超时；`ACCEPTED` 只表示请求入队，不能当作业务完成。页面/服务销毁时调用 `client.close()`。

首次未完成隐私确认返回 `PRIVACY_REQUIRED`；服务未连接返回 `NOT_READY`。`initialize` 只准备后台服务，不打开页面、不播放。等待服务就绪采用有限次数重读；失败后等待用户操作，不做循环拉活。

## 可以直接对接的功能

| 客户端功能 | 方法 | 说明 |
|---|---|---|
| 当前歌曲、状态、播放进度 | `getState`、`getPlaybackInfo`、`getSongInfo` | 歌名、歌手、专辑、时长、歌曲标识、队列下标 |
| 封面 | `getArtwork` | 缓存封面通过受限 PNG URI 读取，不能在主线程读图 |
| 行歌词 | `getLyrics`、`getCurrentLyric` | 带行时间轴，作为逐字歌词缺失时的回退 |
| 逐字/逐词歌词 | `getWordLyrics`、`getCurrentWordLyric` | **v3 新增**；仅提供宿主实际下载的 LRCX 时间轴 |
| 猜你喜欢、每日推荐 | `getRecommendedTracks`、`getDailyRecommendations` | 返回歌曲及短期结果页引用 |
| 每日热歌、每日回顾 | `getDailyHotTracks`、`getDailyReviewTracks` | **v3 新增**；原包已标记废弃，上游可用性见末节 |
| 以歌曲为种子的电台推荐 | `getRecommendedTracksForSong` | **v3 新增**；并不保证每首都是相似歌曲 |
| 热门/最新歌单、首页分类歌单 | `getRecommendedPlaylists`、`getFeaturedPlaylists` | 后者 **v3 新增** |
| 歌单分类及分类下歌单 | `getPlaylistCategories`、`getPlaylistsByCategory` | **v3 新增**；分类 → 歌单 → 歌曲 |
| 推荐专辑 | `getRecommendedAlbums` | **v3 新增**；宿主推荐数字专辑 |
| 在线歌单、专辑、歌手、排行榜 | `getOnlinePlaylistItems`、`getAlbumTracks`、`getArtistTracks`、`getCharts`、`getChartTracks` | 基础信息另有对应的 `get…Info` |
| 歌曲/专辑/歌手/歌单搜索 | `search`、`searchAlbums`、`searchArtists`、`searchPlaylists` | 热词 `getHotKeywords`，联想 `getSearchSuggestions` |
| 队列、收藏、历史、用户歌单 | `getQueue`、`getListItems(listId=favorites)`、`getHistory`、`getLists` 等 | 本机已加载列表；写操作需要原账号和列表 revision |
| 播放与模式 | `play`、`pause`、`next`、`previous`、`seek`、`setMode` 等 | 用户主动操作时调用；读取和重连不触发播放 |
| 后台启动配置与状态 | `initialize`、`getStartupConfig`、`setStartupConfig`、`getStartupStatus` | 只准备原服务，`autoPlay=false` |

以上是音乐 App 的同机能力，不是车辆控制接口。完整音量/列表修改方法及参数以机器合同为准。

## 推荐、歌单和分类的参数

共同分页参数：`page:int` 从 0 开始，最大 10000；`pageSize:int` 默认 30，范围 1..50。所有业务 ID 都用 **String**，不要传 int/long。返回的 `items` 是 `ArrayList<Bundle>`，不是 JSON 字符串，也不是酷我私有 Parcelable。

| method | 额外参数 | 返回 items | 后续调用 |
|---|---|---|---|
| `getRecommendedTracks` | 无 | `Song[]` | `playResultItem` 或 `SongRef` |
| `getDailyRecommendations` | 只使用 page | `Song[]` | 同上 |
| `getDailyHotTracks` | 只使用 page | `Song[]` | 同上；上游废弃入口 |
| `getDailyReviewTracks` | 只使用 page | `Song[]` | 同上；上游废弃入口 |
| `getRecommendedTracksForSong` | `id`：歌曲 ID，例如当前 `song.trackId` | `Song[]` | 同上 |
| `getRecommendedPlaylists` | `sort`：`hot` 或 `new`，默认 hot | `CatalogItem[]` | `getOnlinePlaylistItems(id)` |
| `getFeaturedPlaylists` | 无 | `CatalogItem[]` | `getOnlinePlaylistItems(id)` |
| `getPlaylistCategories` | 无 | `PlaylistCategory[]` | `getPlaylistsByCategory(id)` |
| `getPlaylistsByCategory` | `id`：分类 ID | `CatalogItem[]` | `getOnlinePlaylistItems(id)` |
| `getRecommendedAlbums` | 无 | `CatalogItem[]` | `getAlbumTracks(id)` |

数据结构：

```text
Song:
  index:int                         本结果页下标
  trackId, trackKey:string           歌曲 ID 与当前歌曲引用标识，不能混用
  title, artist, album:string
  artistId, albumId:string
  durationMs:long
  coverUrl, artworkUri:string        可能为空

CatalogItem:
  id, name, coverUrl:string

PlaylistCategory:
  CatalogItem 的字段
  sectionName:string                上游分类区块
  groupName:string                  上游分组，例如语种/风格，以实际响应为准
  parentId:string                   有父级时提供
```

分类 ID、歌单 ID、歌曲 ID 属于不同对象。分类 ID 只能先传给 `getPlaylistsByCategory`，不能直接当歌单 ID 播放。客户端可按 `sectionName` 和 `groupName` 分组显示，名称与分类项使用返回值，不硬编码上游目录。

```java
Bundle page = new Bundle();
page.putInt("page", 0);
page.putInt("pageSize", 30);
client.request("getPlaylistCategories", page, result -> {
    if (!"OK".equals(result.getString("status"))) return;
    java.util.ArrayList<Bundle> categories = result.getParcelableArrayList("items");
    // 渲染分类；用户选中某项后，将它的 id 传给下一步。
});

Bundle category = new Bundle();
category.putString("id", selectedCategoryId);
category.putInt("page", 0);
category.putInt("pageSize", 30);
client.request("getPlaylistsByCategory", category, result -> {
    // OK 后渲染歌单，再按歌单 id 请求 getOnlinePlaylistItems。
});
```

`selectedCategoryId` 等变量由客户端自己的选择状态提供。取得歌单歌曲后，响应携带 `resultRequestId`，歌曲 `index` 为本页下标。仅在用户点击播放时调用：

```java
Bundle play = KuwoBridgeClient.resultItem(selectedResultRequestId, selectedSongIndex);
client.request("playResultItem", play, result -> {
    // accepted 表示交给原播放器，之后读 getState 确认状态。
});
```

播放结果页会以这一页替换队列。想自选组合队列，使用合同中的 `playQueue(songRefs, startIndex)`。歌曲结果页每 UID 只保留 2 页、全局 16 页，120 秒到期；`resultRequestId` 不是永久歌单 ID，过期后重新获取。分类/歌单/专辑目录项本身没有可播放的 `resultRequestId`。

分页返回 `paging`：

- `remote`：由原业务客户端分页；`hasMoreEstimated=true`，空页后停止，不当作准确总数。
- `host_fixed`：每日推荐、每日热歌和每日回顾使用宿主固定页大小，忽略 pageSize；每页最多传 50 项。若 `truncated=true`，不能宣称完整枚举了该页。
- `local`：首页分类歌单、歌单分类、推荐专辑由上游一次获取集合，桥内按 page/pageSize 切片；提供 `total`，`hasMoreEstimated=false`。换页会重新取上游集合，不是长期快照。

搜索及曲库请求共用每 UID 2 个、全局 8 个并发配额，上游等待 20 秒，SDK 等待 25 秒。联想输入自行防抖，离开页面可 `cancelRequest(targetRequestId)`；不要同时拉取所有栏目和全部页。

## 逐字歌词

原逐行接口来自媒体会话歌词 JSON，只有行时间；v3 在原歌词下载完成回调中额外复制原始 LRCX 字块。没有通过平均分配行时长生成假逐字数据，也不会为了请求接口自动播放或额外下载指定歌曲。

逐字接口只读取**当前歌曲**。普通 LRC、TXT、无歌词或下载尚未完成时，`status` 仍可为 `OK`，应继续检查 `wordTimingAvailable`。它为 false 时清除旧逐字高亮并回退到 `getLyrics/getCurrentLyric`。

### 获取时间轴

`getWordLyrics` 参数：`trackKey:string` 可选但建议传入；`revision:long` 首次省略、续页必带；`page` 和 `pageSize` 同上。

```java
Bundle query = new Bundle();
query.putString("trackKey", currentTrackKey);
query.putInt("page", 0);
query.putInt("pageSize", 50);
client.request("getWordLyrics", query, result -> {
    if (!"OK".equals(result.getString("status"))) return;
    if (!result.getBoolean("wordTimingAvailable")) {
        // 清空旧时间轴，按 reason 区分等待或回退逐行歌词。
        return;
    }
    java.util.ArrayList<Bundle> words = result.getParcelableArrayList("items");
    String key = result.getString("trackKey");
    long revision = result.getLong("revision");
    // hasMore=true 时继续下一页，带回相同 key 与 revision，合并 items。
});
```

响应头：

| 字段 | 类型 | 含义 |
|---|---|---|
| `trackKey` | String | 当前歌曲引用 |
| `revision` | long | 当前进程逐字歌词修订号；与逐行歌词 revision 独立 |
| `lyricType` | String | 上游类型，如 LRCX、LRC、TXT；未取得时为空 |
| `resultCode` | int | 成功 20000、开始 20001、无歌词 20002、失败 20003；未捕获为 -1 |
| `wordTimingAvailable` | boolean | 有当前歌曲可用的原始逐字时间轴 |
| `source` | String | 可用时为 `native_lrcx`，其它情况为空 |
| `reason` | String | 可用时为空，否则见下表 |
| `offsetMs` | long | 原歌词全局偏移；返回的时间已经应用，不要再加一次 |
| `truncated` | boolean | 原行超过 1024 个 UTF-16 单元时被截断，相应字块未导出 |
| `total`、`page`、`pageSize` | int | 字块总数及分页信息 |
| `hasMore` | boolean | 是否还有下一页 |
| `items` | ArrayList&lt;Bundle&gt; | `WordLyric[]` |

无逐字数据的 `reason`：`NO_TRACK` 无当前歌，`LYRICS_PENDING` 尚未取得/正在下载，`LYRICS_FAILED` 下载或解析失败，`NO_WORD_TIMING` 无逐字时间（含普通 LRC、TXT 或无歌词）。

单个 `WordLyric`：

```text
index:int                           整个逐字时间轴中的下标
lineIndex:int                       原始 LRCX 行下标
wordIndexInLine:int                  原行中的字块下标
charStart:int, charEnd:int           lineText 的 UTF-16 区间 [start, end)
text:string                         对应字块，可能包含多个字或一个单词
lineText:string                     原始行文本，最多 1024 个 UTF-16 单元
lineStartMs:long                    行开始时间，已应用全局偏移
startMs:long, endMs:long             相对歌曲起点的字块起止时间，已应用全局偏移
durationMs:long                     endMs - startMs，可能为 0
```

按逐字数据自身的 `lineIndex` 合并行。原始行与逐行 API 可能采用不同换行，**不能把两套接口的行下标混用**。Java/Kotlin/JS 字符串使用 UTF-16，跨到按 Unicode 码点计数的语言时需转换；`charEnd` 不包含末端字符。

输入 trackKey 与当前歌曲不同返回 `STALE_TRACK`；续页 revision 已变化返回 `STALE_LYRICS`。丢弃旧页，重新获取第一页。进程重启或客户端重连后也重新获取，不能长期保存 revision。

### 当前字块与高亮

`getCurrentWordLyric` 可带同样的 `trackKey` 和 `revision`，不分页。返回相同的歌曲、歌词状态和修订号字段，另有：

```text
positionMs:long                     当前播放位置
sampledAtElapsedMs:long             读取快照时的 SystemClock.elapsedRealtime()
index:int                          当前或最近已开始字块；首字前/无数据为 -1
active:boolean                     播放位置是否仍在该字块区间内
progress:float                     0..1；零时长字块为 1
word:Bundle                        index >= 0 时提供
next:Bundle                        还有下一个字块时提供
```

字块间隙及末字结束后保留最近字块，`active=false`、`progress=1`。`active` 描述时间轴区间，不表示播放器正在播放；暂停时仍需读 `getState().playing`。

平滑显示推荐一次取完当前歌曲的逐字分页，配合 `getState` 的 `positionMs`、`updatedAtElapsedMs`、`playing` 在客户端按单调时钟计算当前位置，按屏幕刷新绘制。普通播放速率下：

```text
播放中位置 = 状态 positionMs + 当前 elapsedRealtime - 状态 updatedAtElapsedMs
暂停位置   = 状态 positionMs
字块进度   = clamp((位置 - startMs) / durationMs, 0, 1)
零时长块   = 位置达到 startMs 时直接完成
```

不要每帧跨进程查询。切歌、seek、暂停/继续和重新进入前台时重读状态。监听 `content://cn.kuwo.kwmusiccar.bridge/lyrics` 或 SDK 的 `onLyricsChanged()` 后重读歌词；通知只有变化信号，不包含逐帧进度。`state` 通知用于重读播放状态。切歌立即清空旧行和高亮，异步响应应用前再次核对 trackKey。

## 错误与权限

客户端业务入口统一处理一次 `status`，再把已校验的数据交给内部模型，不重复逐层校验。除上述状态外，常见情况如下：

| status | 客户端处理 |
|---|---|
| `FORBIDDEN` | 核实实际安装包名是否在酷我白名单 |
| `PRIVACY_REQUIRED` / `LOGIN_REQUIRED` | 引导用户在酷我完成原确认/登录 |
| `NOT_READY` / `START_BLOCKED` | 读取启动状态，等待用户操作或有界等待 |
| `UPSTREAM_ERROR` / `TIMEOUT` | 显示该模块加载失败；upstreamCode 若有则是上游错误码 |
| `BUSY` | 减少并发，当前请求结束后按需加载 |
| `STALE_SEARCH` | 歌曲结果引用过期，重新获取相应页 |
| `STALE_TRACK` / `STALE_LYRICS` | 丢弃旧歌词，读取当前歌曲的第一页 |
| `STALE_LIST` | 重读列表 revision，再由用户决定写操作 |
| `UNSUPPORTED` | 能力清单未支持，不调用该方法 |
| `INVALID_ARGUMENT` | 检查参数类型、范围及 ID 来源 |

`OK`、`accepted=true` 或 `stateConfirmed=true` 都不能证明已出声或完成云端同步。读取推荐、分类和歌词不会触发播放；客户端也不能在刷新/重连时自动重放播放、删除或音量操作。

## 已发现但尚未开放的候选能力

| 能力 | 当前结论 |
|---|---|
| 歌手地区/性别分类、歌手专辑目录 | 原包存在相关业务模型和入口，可后续补合同与适配；v3 不提供，不自行猜方法名 |
| 更完整的歌单/专辑简介、作者、收藏数 | 当前只返回已核实的基础字段，扩展需核实各模型实际返回值 |
| 指定非当前歌曲的逐字歌词 | v3 不提供主动下载接口；只读取当前歌曲的原始歌词回调 |
| 账号云端全部歌单、跨设备同步 | 现有列表接口仅为本机已加载数据，未承诺云端完整枚举 |
| 播放源 URL、会员权限绕过、任意文件/数据库 | 不属于本对接合同 |

## 本版验证范围

2026-11-03 在已驻车车机上，通过酷我自身 UID 的实际 Provider 调用完成以下检查，未触发播放：

| 项目 | 结果 |
|---|---|
| 安装版本与能力清单 | 1.0.01 / 74001，API v3，76 项入口 |
| 每日热歌 / 每日回顾 | 分别返回 30 / 29 首；虽然本次可用，原包仍标为 deprecated |
| 歌曲电台推荐 | 返回 5 首歌曲 |
| 首页分类歌单 / 推荐专辑 | 本次集合分别为 72 / 70 项，第一页各读取 10 项 |
| 歌单分类 → 分类下歌单 → 歌单歌曲 | 分类集合 73 项，分类 ID 146 返回歌单，再成功读取该歌单歌曲 |
| 当前歌曲逐字歌词 | 暂停状态的《不浪漫罪名》返回 LRCX、480 个字块、native_lrcx 来源；当前字块和进度快照可读 |
| 歌词引用校验 | 错误 trackKey 返回 STALE_TRACK，旧 revision 返回 STALE_LYRICS |
| 原始歌词转换 | 独立快照使用原包 LRCX 类验证全局偏移、UTF-16、零时长字块和跨行；普通 LRC 及失败结果不生成逐字时间 |
| 构建 | 签名/对齐及 342 项结构检查通过，其中包含 131 个宿主反射方法符号 |

这些数量是本次服务响应，不是固定栏目大小。没有在另一客户端 App 中完成播放中的高亮渲染验收，也没有测试声音输出、真实开机或跨设备同步。默认白名单之外的 App 仍需先由维护者加入包名；自身 UID 检查不能代替特定客户端集成验收。更多限制见 [版本更新](../docs/版本更新.md)。

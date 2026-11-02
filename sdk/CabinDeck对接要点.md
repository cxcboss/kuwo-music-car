# CabinDeck 音乐卡片接入顺序

只复制 SDK 和适配自己的音乐数据层，不需要把酷我 APK 内部的 Java/混淆类拷进 CabinDeck。本项目没有直接修改 CabinDeck 仓库。

## 连接和恢复

1. 用 applicationContext 创建 KuwoBridgeClient，注册 Listener。
2. 读取 capabilities。apiVersion>=2 时启用本文新增方法，旧版本保留 v1 方法。
3. 调用 initialize。PRIVACY_REQUIRED 交给正常的首次启动流程；不自动确认协议或弹起页面。
4. 若 serviceReady=false，只有限次读取 getStartupStatus；就绪后读 getState、getLyrics、getArtwork。离线/服务不可用时清空对应内容，不填入假歌名、假封面或假歌词。
5. 宿主重新显示时重读一次；销毁时 close，并清理图片线程与延时任务。不要定时无限重启酷我。

## 数据到卡片的映射

| 卡片元素 | 数据或方法 |
|---|---|
| 歌名、歌手、专辑、歌曲标识 | getState.state.song；使用 trackKey 判断切歌 |
| 当前封面 | song.artworkUri 或 getArtwork；在图片线程 readArtwork(uri,512) |
| 网络封面地址 | song.coverUrl；可为空，不是音频播放源 |
| 播放/暂停状态 | state.playing 和 state.state |
| 播放进度 | positionMs、durationMs、updatedAtElapsedMs；本地推进，暂停/seek/切歌时重读 |
| 歌词 | getLyrics 时间轴，resultCode=20000；当前行可按需 getCurrentLyric |
| 收藏按钮 | getFavoriteState，点击 setFavorite 或 toggleFavorite |
| 播放模式菜单 | setMode single / sequential / repeat_all / shuffle，之后读回状态 |
| 队列 | getQueue 返回 revision 和绝对 index；playItem、removeQueueItem、clearQueue |
| 我的收藏列表 | getListItems(listId=favorites)；不要直接编辑收藏列表数据 |
| 我的歌单 | getPlaylists / getPlaylistInfo / getListItems；修改前读取可写能力与 revision |
| 搜索页 | search；歌手/专辑/歌单筛选分别调用 searchArtists/searchAlbums/searchPlaylists |
| 专辑、歌手、在线歌单、榜单 | getAlbumTracks/getArtistTracks/getOnlinePlaylistItems/getChartTracks |
| 选中在线曲目播放 | playResultItem(resultRequestId,index)；或以结果 SongRef 调用 playQueue |
| 开机启动开关 | getStartupConfig / setStartupConfig；silent=true 和 autoPlay=false 固定 |

## UI 交互注意

- 未实现或无数据的区域留空或使用应用既有空态，不在卡片上增加接口解释文案。
- 使用当前实际歌曲状态渲染；控制命令的 accepted 不能用来提前假定已播放。
- 搜索输入采用约300ms防抖，并取消上一条外部搜索请求；回调同时核对输入代次。
- 曲库/搜索结果页内下标与队列整列表下标不同，不直接复用。
- 列表变化后旧 revision 返回 STALE_LIST，重读后由用户再次选择，不自动重放写操作。
- 收藏、删除、清空和音量修改只在明确点击后发出；超时后先读实际状态，避免重复动作。
- 切歌先更新 trackKey，清空上首歌词/封面；封面 IO 回调回来时再核对 trackKey 和自身加载代次。
- artwork/list 通知仅提示变化，刷新时合并已在进行的同类请求。进度和歌词高亮由卡片本地时间轴推进，避免频繁跨进程轮询。
- readArtwork 必须放在图片/IO线程。不能把 ContentResolver.openInputStream 或 PNG 解码放进绘制、滑动和播放按钮的 UI 回调。

## SDK 调用示例

```java
// Inside the existing card or data owner:
Bundle source = KuwoBridgeClient.currentSong(currentTrackKey);
client.setFavorite(source, true, result -> {
    if ("OK".equals(result.getString("status"))) {
        boolean favorite = result.getBoolean("favorite");
        // Render from this actual state; cloud synchronization is separate.
    }
});

Bundle page = new Bundle();
page.putString("listId", "favorites");
page.putInt("page", 0);
page.putInt("pageSize", 30);
client.request("getListItems", page, result -> {
    if (!"OK".equals(result.getString("status"))) return;
    String revision = result.getString("revision");
    java.util.ArrayList<Bundle> songs = result.getParcelableArrayList("items");
    // Keep this revision with this page; do not bind a later revision to old rows.
});
```

所有字段、参数、错误码、权限边界和完整67个方法见接入说明.md及api-contract.json。本文是接入说明，不代表 CabinDeck 已完成集成或实车验证。

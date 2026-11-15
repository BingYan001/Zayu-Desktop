<img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&height=300&section=header&text=Zayu-Desktop&desc=%E4%BA%91%E6%9E%90%20%C2%B7%20%E7%BD%91%E7%9B%98%E5%88%86%E4%BA%AB%E9%93%BE%E6%8E%A5%E8%A7%A3%E6%9E%90%E4%B8%8E%E9%AB%98%E9%80%9F%E4%B8%8B%E8%BD%BD&fontSize=64&fontColor=ffffff&descSize=16&animation=fadeIn&fontAlignY=40&descAlignY=55" />

# 杂鱼Max 桌面版 v1.3.6

> 强制更新：1
> 最低强制更新版本：v1.3.0
> 发布日期：2026-11-15
> 安卓版：BingYan001/ZayuMax ｜ 桌面版：BingYan001/Zayu-Desktop

## 品牌改名：YunX → Zayu

这一版把内部遗留的 `yunx` 命名全部换掉了，装完之后看到的东西都统一叫杂鱼Max / Zayu：

| 位置 | 改名前 | 改名后 |
| --- | --- | --- |
| 启动器 | `YunX-Desktop.exe` | `Zayu-Desktop.exe` |
| 安装目录 | `%LOCALAPPDATA%\Programs\YunX-Desktop` | `%LOCALAPPDATA%\Programs\Zayu-Desktop` |
| 数据库 | `yunx.db` | `zayu.db` |
| 日志 | `yunx-desktop.log` | `zayu-desktop.log` |
| 环境变量 | `YUNX_DESKTOP_DATA_DIR` | `ZAYU_DESKTOP_DATA_DIR` |
| 仓库 | `tidain/YunX-Desktop` | `BingYan001/Zayu-Desktop` |

**从旧版升级不会丢账号**：首次启动时旧库 `yunx.db` 连同 `-wal` / `-shm` 三件套会原子改名成
`zayu.db`；旧数据目录 `~/.yunx-desktop` 与 `~/.yunx-pc` 也会照常复制/迁移过来。

> 装过旧版的机器建议先卸载旧版再装这一版（安装目录名变了，会并存两份）。

## 新增：5 家网盘

网盘支持从 10 家扩到 15 家，新增的 5 家全部走完整链路：分享解析 → 目录浏览 → 取直链 → 转存 → 临时清理。

| 平台 | 分享解析 | 游客可用 | 登录方式 |
| --- | :--: | :--: | --- |
| **小飞机网盘**（feijipan） | ✓ | ✓ 全流程免登录 | 不需要 |
| **城通网盘**（ctfile） | ✓ | ✓ 全流程免登录 | 不需要 |
| **PikPak** | ✓ | 可浏览列表 | access_token / refresh_token |
| **联通云盘**（wopan） | ✓ | 可浏览列表 | Cookie / token |
| **天翼云盘**（189） | ✓ | 可浏览列表 | Cookie |

- **小飞机**：整套匿名链路，粘链接直接就能下，不弹登录。

- **城通**：两跳取链，服务端给的等待时间会真等满再取；带提取码的分享自动识别；目录翻页走分享信息里的签名地址。

- **PikPak**：access_token 与 refresh_token 都收，填 refresh_token 会自动换发。转存三步式 + 任务轮询；同名同大小的文件识别后跳过重复下载。下载 UA 带客户端版本号。

- **联通云盘**：请求体 AES/CBC 加密，header 带时间戳签名，三段状态码（HTTP / STATUS / RSP_CODE）全部通过才算成功。

- **天翼云盘**：接口恒带 noCache 与 Sign-Type；登录失效判定覆盖 401 与任意 3xx；直链里的 `&amp;` 还原成 `&`、`http://` 升级为 https。转存后按「文件名 + 大小」轮询确认新文件。

## 新增：三家 token 平台登录界面

PikPak / 联通 / 天翼 在网盘页各有一张卡片。未登录时点卡片进登录弹窗，已登录时点卡片直接切到解析页，更多菜单里可清除凭证。

凭证经 AES-256-GCM 加密后落盘（密钥在本机数据目录，不随备份外泄）。PikPak 的 refresh_token 会真发一次换发请求验证，天翼会真拉一次个人目录列表验证。

未登录不影响浏览分享内容里的文件列表 —— 取直链时才提示登录。

## 修复与优化

- **修复城通取链失败**：参数解析改为显式循环，不再依赖 inline lambda 内的 `continue`（低语言版本下行为不一致）。

- **修复网盘页假登录态**：上一版为规避「隐藏未登录」过滤把登录态写死，现改为由真实存储驱动。

- **修复天翼接口异常分支崩溃**：异常路径里误读属性导致。

## 界面

- 五家新平台各配官方图标。

- 液态玻璃 SKSL 折射改为按组件短边自适应，胶囊走独立比例；恢复 DWM 亚克力。

- 标题栏窗口控制钮改为 Win11 / Chrome 规格（10dp 细线图标、46×32 矩形热区、关闭 hover 系统红）。

---

# 云析桌面版 v1.3.3

> 发布日期：2026-10-31
> 安卓版：BingYan001/ZayuMax ｜ 桌面版：BingYan001/Zayu-Desktop

## 新增：P2P 下载（磁力 / BT 种子 / ed2k）

在网盘、视频、GitHub 之外，新增一条独立的高速下载链路 —— 直接吃**磁力链接**、**BT 种子文件**与 **ed2k（电驴）链接**，统一交给 Gopeed 引擎多线程下载。

- **ed2k 电驴链接下载**：粘贴 `ed2k://` 链接即可解析并加入下载，与磁力 / 种子走同一套引擎与任务管理。

- **本地种子文件**：新建下载弹窗新增「选择种子文件」，可直接挑选本机 `.torrent` 开始下载，无需依赖磁力链。

- **BT Tracker 管理二级页**：新增独立的 **Tracker 服务器**管理页，集中维护 tracker 列表；BT 任务会把这些 tracker 下发到引擎，\*\*修复公网磁力 / 种子一直卡在「解析中」\*\*的问题。

- **DHT 开关**：下载引擎页可开关 BT 的 DHT（分布式哈希表），按网络环境自行取舍。

- **P2P 任务 peer 概览**：下载中的磁力 / 种子 / ed2k 任务会显示「**种子 N · 连接 M · 节点 K**」，实时看到连接与做种情况。

## 修复与优化

- **修复 P2P 任务「继续」被内置下载器执行**：磁力 / 种子任务点「继续」曾可能被交给内置下载器而非引擎，现已正确路由回 Gopeed 引擎。

- **引擎失败原因可见**：此前引擎任务失败一律只显示「Gopeed 引擎下载失败」，现改为回读引擎返回的**真实错误信息**，便于定位（如 tracker 无响应、资源不可用等）。

- **状态文案统一**：任务状态「正在解析磁力…」改为「**正在解析…**」，已覆盖磁力 / 种子 / ed2k 三类链接。

- **修复内嵌浏览器原生包解压失败**：CEF 原生包重新换回 `tar.gz`，避免个别环境下 `tar.xz` 解压异常。

## 新增：界面与体验

- **下载列表滚动条常驻**：下载列表右侧显示常驻滚动条，任务多时一眼可见当前位置。

- **失败任务置顶**：失败的任务自动排到列表最前（含文件夹分组内部），失败项不再被淹没。

- **添加弹窗识别名称**：新建任务时「名称」输入框会随粘贴的链接**实时重算占位名称**，确认前即可看清将保存成什么名字。

- **主窗口启动位置可设置**：「设置 → 主窗口启动位置」可指定窗口首次出现的位置；支持进入**全屏遮罩拖拽站位**，把窗口摆到屏幕任意处后一键记录。

## 系统要求

- Windows 10 / 11 x64

## 下载

<https://github.com/BingYan001/Zayu-Desktop/releases>

- **便携版**：解压即用

- **安装版**：安装到 `%LOCALAPPDATA%\Programs\Zayu-Desktop`

> 视频预览与 DASH 合轨所需的 FFmpeg、以及 P2P / 外部下载所需的 **Gopeed 引擎**均**不随安装包发布**，首次使用时可在应用内「引擎管理」一键下载（装到应用私有目录，不改系统环境变量）。

## 反馈

<https://github.com/BingYan001/Zayu-Desktop/issues>

***

*杂鱼Max 的官方电脑端，GNU AGPL-3.0 开源。*

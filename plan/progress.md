# 融合进度

## 2026-10-29

### 融合（1.5.0 / code 9）

- [x] 克隆 FileViewer 作为融合基座（v1.4.2 / code 8）
- [x] 建立 plan 目录，记录项目地址、版本与跟进方式
- [x] 迁移离线文档查看器源码到 `feature/docviewer`（v0.8.6 / code 21）
- [x] 迁移 split 编译期桩到 `splitstubs`
- [x] 迁移 split 资源与 so：`feature/docviewer/src/main/assets`、`jniLibs/arm64-v8a`
- [x] 清单合并：文档 Activity、CAMERA 权限、FileProvider 路径（含 external-cache）
- [x] Application 引导合并：`DocViewerBootstrap.init` / 资源补挂 / Activity 生命周期回调（主题切换）
- [x] 文档类 VIEW 入口改用 `Theme.NextPlayer`；保持无桌面图标
- [x] ABI 收窄为 arm64-v8a（文档引擎只提供 arm64 so），不再产出其它 ABI 的空壳包
- [x] 构建通过并产出 APK（Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 / JDK 17 / SDK 37）

### 品牌与关于页（1.6.0 / code 10）

- [x] 应用名改为「轻阅」（`app_name`，debug 变体为「轻阅 (Debug)」）
- [x] 用 seedream 重新生成图标（深青→海蓝渐变 + 白色折角纸张 + 播放/图片符号）
- [x] 图标落地：旧版圆角 `ic_launcher(.webp)`、圆形 `ic_launcher_round`、自适应前景
      `ic_launcher_foreground`、Android 13 主题单色层 `ic_launcher_monochrome`（mdpi~xxxhdpi）
- [x] 图标生成可复现：源图 `plan/brand/qingyue-icon.jpg`，脚本 `plan/brand/make_icons.py`
- [x] 关于页：仓库链接改为 my-file-reader，新增「Document engine」条目指向 quark-doc-viewer
- [x] 文案：`app_tagline` 改为 "Video, images and documents in one place"，权限说明去掉 Next Player 字样
- [x] 构建通过并产出 APK（package `com.re.docviewer.debug`，1.6.0 / 10）

### 修复 PDF 崩溃（1.6.1 / code 11）

- [x] 定位：`pdfviewer` split dex 直接引用 `j$/util/concurrent/ConcurrentHashMap`、
      `j$/util/Objects`，而 `minSdk=24` 时 L8 判定这些 Java 8 回填类不需要，base dex 里没有，
      打开 PDF 时 `PdfiumSDK.<init>` 抛 `NoClassDefFoundError`
- [x] 试过在 `feature/docviewer` 里自建 `j$` 桩：D8 禁止 `j$.` 类与普通类合并到同一 dex，方案不可行（已回退）
- [x] 采用原壳同款配置：`android-minSdk = 23`，让 L8 打包完整脱糖库
      （旧版离线文档查看器同为 23，dex 内含 1008 个 `j$` 描述符）
- [x] `core-pip` 声明 minSdk 24，在 app 清单用 `tools:overrideLibrary="androidx.core.pip"` 放开
      （仅画中画受影响，API 26 以下本就不可用）
- [x] 校验：融合 APK 含 1388 个 `j$` 描述符，两个 split 引用的 `j$` 类全部可解析（缺失集为空）
- [x] 构建通过并产出 APK（1.6.1 / 11，`app-arm64-v8a-debug.apk` 52,999,561 B）

### 修复 PDF 顶栏重叠（1.6.2 / code 12）

- [x] 现象：PDF 顶栏返回键与标题文字重叠，分享 / 菜单也挤在左上角
- [x] 定位：`pdfviewer` split 的 `pdf_common_top_bar.xml` 在夸克应用内编译，
      `app:` 命名空间下的 `layout_constraint*` 保留的是夸克自己的资源 id（`0x7f03xxxx`）；
      宿主资源表里这一区间几乎为空（只剩 2 个 array），且宿主 ConstraintLayout 读的是自己的
      `0x7f04xxxx` attr id → 所有约束属性在 inflate 时被丢弃 → 顶栏控件全部堆在左上角
- [x] 同类排查：两个 split 的全部 XML 与 `resources.arsc` 里，只有
      `pdf_common_top_bar.xml`（13 处）与 `pdf_common_bottom_bar.xml`（6 处）跨包引用
      `0x7f03`；后者在本版 split 代码里未被 inflate（底栏由 `PDFBottomBar` 代码构建），
      `pdf_vertical_slider` 的 `app:seekBarRotation` 用的是 split 自己的 `0x7d03`，正常
- [x] 修复：新增 `PdfTopBarLayoutFix`，在 `setContentView(root)` 后按 XML 原语义重建
      顶栏 LayoutParams（返回键 32dp/start 16dp；标题 0dp/左右 98dp；分享 32dp/菜单左侧 8dp；
      菜单 32dp/end 12dp，均垂直居中）
- [x] 覆盖懒加载入口：`PageManagerView`（页面缩略图）会另建一个 `PDFTopBar`，
      给根容器挂 `OnHierarchyChangeListener`，面板加入时再修复一次
- [x] 构建通过并产出 APK（1.6.2 / 12，`app-arm64-v8a-debug.apk` 53,000,626 B）

### Office 主题色改造（1.6.3 / code 13）

- [x] 现状：office 界面（顶栏 / 底栏 / 状态栏）全部由 `com.uc.office` 代码构建，
      split 里只有 4 个 layout；底色写在 `OfficeBottomItem.initView()`（#FFFAFAFA）、
      `OfficeTopBar.mBarBackgroundColor`（-1 白色）、`BaseActivity.configureSystemUI()`
      （状态栏白色 + 浅色图标）里，资源层改不到
- [x] 强调色（资源层）：给 `ucoffice-master.apk` 的 6 个 drawable 二进制 XML 原位换色
      #FF0D53FF / #FF2828FF → #FF006A68、#339999FF → #33006A68、#FFD6D6FF → #FFCFE8E4
      （编辑光标 / 选中手柄 / 搜索光标 / 选区蒙层 / 传 PC 进度条），重打包 + zipalign，
      `aapt2 dump resources` 校验资源数仍为 311、id 未变
- [x] SPLIT 资产失效：`DocViewerBootstrap.SPLIT_ASSET_VERSION` 2 → 3，旧解压副本自动重解
- [x] 运行时换色：新增 `OfficeThemeColors`（`com.re.docviewer.office`），在
      `DocViewerBootstrap.onActivityCreated` 对 `com.uc.office.*` 调用
      - 状态栏 / 导航栏 → 主色 #FF006A68，并清 `LIGHT_STATUS_BAR`（API 26+ 另清 `LIGHT_NAVIGATION_BAR`）
      - 顶栏 → 反射 `OfficeTopBar.setBarBackgroundColor(#FFE6F4F3)`（同步 common/search/edit 三栏）
      - 底栏 / 底栏项 / 传 PC 项 → `setBackgroundColor(#FFE6F4F3)`
- [x] 时序：底栏要等文档解析完成（`openFileFinish`）才 addView，故对 `com.uc.office.widget.*`
      容器挂 `OnHierarchyChangeListener` 补刷，且只挂 split 自己的 ViewGroup（不侵入文档视图）
- [x] 构建通过并产出 APK（1.6.3 / 13，`app-arm64-v8a-debug.apk` 53,000,751 B）

### Office 弹窗主题色（1.6.4 / code 14）

- [x] 现状：弹窗同样是代码构建（`AbsOfficeDialog.initRootView()` 的白色 24dp 圆角卡片、
      `BaseOfficeDialog.createButton()` 的 #FF2828FF 字 + #FFE9EBFF 底、
      `OfficeMenuDialog.crateItemView()` 的 #FFF4F4F4 图标小方块），且弹窗不参与 Activity 生命周期
- [x] 入口：`OfficeDialogHelper` 有静态 `sCurrentDialog`（`showDialog()` 时写入，`releaseDialog()` 清空），
      反射读取后逐字段换色；没有 Dialog 级回调可挂，故以 100ms 轮询（Activity 销毁即停）
- [x] 换色内容：卡片 → #F2F8F7、图标小方块 → #DFF0EE、确认按钮 → #006A68 字 + #CFE8E4 底、
      取消按钮 → #E9F4F2 底；头部白色底衬与卡片同色
- [x] 跳过 `OfficeLoadingDialog`（它 `setContentView` 用自带的深色浮层）
- [x] 构建通过并产出 APK（1.6.4 / 14，`app-arm64-v8a-debug.apk` 53,003,177 B）

### 全面统一主题色 + 深色适配（1.7.0 / code 15）

原则：文件内容之外的所有界面统一用「轻阅青」，浅色 / 深色都跟随系统；
不复用夸克的白底 + 蓝强调色。

- [x] 宿主界面：`Theme.NextPlayer` 去掉 `DynamicColors` 父主题，`NextPlayerTheme` 默认
      `dynamicColor = false`，改为固定使用 core:ui 的 `md_theme_*`（浅色主色 #006A68 /
      深色主色 #4DDAD6），全仓库没有其它硬编码 Compose 颜色
- [x] 新增 `feature:docviewer` 资源 `values/colors.xml` 与 `values-night/colors.xml`：
      `doc_primary` / `doc_chrome` / `doc_surface` / `doc_dialog` / `doc_dialog_tile` /
      `doc_on_chrome` / `doc_on_dialog(_secondary)` 等，浅色深色各一套
- [x] `OfficeThemeColors` 改为按资源取色（深浅色自动跟随），并补齐文字 / 图标：
      顶栏底栏标题 #222222 → `doc_on_chrome`，深色下图标反色；状态栏浅色主色、深色 chrome
- [x] 底栏选中态：`OfficeBottomItem.select()` 里写死的 #2828FF 改为每 300ms 补刷为 `doc_primary`
      （此前记录的“已知遗留”由此消失）
- [x] 弹窗：卡片 / 图标方块 / 确认按钮全部走资源色，深色下为深青卡片
- [x] PDF 查看器：新增 `PdfThemeColors`，按 id 换色 —— 顶栏 `pdf_view_top_bar`、
      底栏 `pdf_view_bottom_bar`、页码条 `pageSlider`、错误页 / 加载页，页面区域只铺底色；
      在 `showViewer` 与 `onResume` 各调用一次（split 的顶栏底栏是 XML 白底 + #222 字）
- [x] 壳页面：`ErrorPanel`、`ParseErrorView`、`OfficeActivity` 加载页、宿主覆盖的
      `OfficeMenuDialog` 全部改用 `doc_*` 资源色
- [x] 构建通过并产出 APK（1.7.0 / 15，`app-arm64-v8a-debug.apk` 53,007,292 B）

### 回退查看器主题色（1.7.1 / code 16）

按反馈把 Word / Excel / PPT / PDF 查看器的配色全部退回夸克原样：

- [x] `assets/ucoffice/ucoffice-master.apk` 换回原始 split 包（5,239,160 B），
      1.6.3 里对 6 个 drawable（编辑光标、选中手柄、搜索光标、选区蒙层、传 PC 进度条）
      做的颜色补丁同时失效；`SPLIT_ASSET_VERSION` 升到 `4` 让已解压的旧副本重新释放
- [x] 删除 `OfficeThemeColors` / `PdfThemeColors` 及 `DocViewerBootstrap`、
      `PdfViewerActivity` 里的调用：顶栏、底栏、状态栏、导航栏、弹窗恢复原始颜色
- [x] 壳页面回退：`ParseErrorView`、`OfficeMenuDialog`、`ErrorPanel`、`OfficeActivity`
      的加载/错误页恢复原先的白底、默认按钮样式
- [x] 删除 `feature:docviewer` 的 `values/colors.xml`、`values-night/colors.xml`
- [x] 保留宿主界面改动（关闭动态取色、固定轻阅青），未受影响

### 构建结果

```
./gradlew assembleDebug
BUILD SUCCESSFUL
app-arm64-v8a-debug.apk   53,000,634 B  (1.7.1 / 16)
```

产物校验（aapt2）：
- label = 轻阅 (Debug)，versionName = 1.7.1，versionCode = 16，minSdk = 23
- application-icon 指向 `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标）
- 已注册 `OfficeActivity`（VIEW：pdf/msword/ms-excel/ms-powerpoint/ooxml）
- 已注册 `SaveFileActivity`、`PdfViewerActivity`、`QuarkOfficeActivity`、`QuarkOfficeTextViewActivity`
- APK 内含 `assets/ucoffice/ucoffice-master.apk`、`assets/pdfviewer/pdfviewer-master.apk`
  与 `lib/arm64-v8a/libpdfium.so`、`libpdfsdk_jni.so`、`libtiff*.so`

### 夸克式投屏（1.7.1 / code 16，轻阅投屏特别版）

夸克的投屏是远程下发的 Qigsaw 模块（`lebo_projection` / `youku_projection`）+ 乐播/优酷
商业 SDK，按 appId / 包名 / 签名授权，无法原样移植。这里做「夸克同款交互 + 自研 DLNA 传输」：

- [x] 新增模块 `core:cast`（零第三方依赖），`settings.gradle.kts` 注册，`feature:player` 依赖
- [x] `SsdpDiscovery`：M-SEARCH（ST=MediaRenderer:1，239.255.255.250:1900）发现设备，
      MulticastLock 加持，解析 description 取 UDN / friendlyName / AVTransport controlURL
- [x] `AvTransportClient`：SOAP 调 SetAVTransportURI / Play / Pause / Stop / Seek /
      GetPositionInfo / GetTransportInfo
- [x] `LocalMediaServer`：手机端随机端口 HTTP 服务，GET/HEAD + Range → 206，
      `content://` 有长度走 ContentResolver，无长度落盘 cacheDir
- [x] `CastManager`：IDLE / DISCOVERING / CONNECTING / CASTING 状态机，2s 轮询进度，
      投屏前暂停本地播放并从当前进度续投，停止或失败后恢复本地播放
- [x] UI：`ControlsTopView` 顶栏投屏按钮（投屏中切换实心图标）、`CastDevicePanel`
      居中弹层（设备列表 / 搜索中 / 无设备 / 连接失败 / 投屏中播放暂停 + 停止）
- [x] `NextIcons` 增加 `Cast` / `CastConnected`；`core:ui` 文案新增 14 条 `cast_*`
- [x] 权限：`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`
      （`ACCESS_LOCAL_NETWORK` 原清单已有）
- [x] `PlayerActivity` 持有 `CastManager`，`onDestroy` 释放
- [x] 构建通过（`:app:assembleDebug`），产出 `app-arm64-v8a-debug.apk`

限制：只投 DLNA 设备（不含乐播私有协议 / AirPlay / 屏幕镜像）；电视不支持的外挂字幕不会随投；
`ktlintCheck` 因构建环境拉不到 ktlint 依赖未执行，代码按 `call-layout` 规则人工排版；
SSDP / SOAP / Range 兼容性尚未真机验证。

### 待办 / 已知限制

- [ ] PDF / Office 界面本身来自夸克 split，无法换成 Compose 主题；Office 已在「Office 主题色改造」里手工换色，PDF 仍是夸克原配色
- [ ] PDF「编辑底栏印章/图片」依赖 `assets/t1.png`、`t1.jpg`，夸克侧缺失，当前不可达
- [ ] 文档查看仅 arm64-v8a（split so 只有 arm64），其它 ABI 已不产出
- [ ] 融合后应用不再提供桌面启动图标，只能由「打开方式 / 分享」唤起（与 FileViewer 一致）
- [ ] 文档入口目前只接 `VIEW`；`SEND`（分享单个文件）打开文档需要在 `OfficeActivity` 里补 `EXTRA_STREAM` 分支
- [ ] minSdk 为 23（为完整脱糖库），`core-pip` 被强制放开；API 23~25 上画中画不可用，其余功能未在老设备验证
- [ ] 仍需真机验证：docx/xlsx/pptx 打开、PDF 打开、另存为、打印
- [ ] 待真机确认：PDF 顶栏（返回键 / 标题 / 分享 / 菜单）不再重叠，页面缩略图页顶栏同样正常
- [ ] 待真机确认：Office 顶栏 / 底栏为浅青底、状态栏与导航栏为主色、Word 光标与选中手柄 / 搜索光标为青绿
- [ ] 待真机确认：Office 弹窗（菜单 / 文件详情 / 保存提示）为浅青卡片，确认按钮为青绿字 + 青绿底
- [ ] 已知遗留：底栏「选中态」文字 / 图标仍是 #FF2828FF（`OfficeBottomItem.select()` 内联字面量，dex 补丁不可行）

# bFontZoom — Burp Suite 字体缩放插件

为 Burp Suite 2026 提供 **Ctrl + 鼠标滚轮** 缩放数据包字体的功能，同时不破坏普通滚轮的上下浏览。

## 功能

- **Ctrl + 滚轮上/下** → 字体放大/缩小（步进 1pt，范围 6–72pt）
- **普通滚轮** → 完全不影响，保持 Burp 默认的上下滚动浏览
- **缩放级别持久化** → 通过 Java Preferences 保存到 Windows 注册表，重启 Burp 后自动恢复

## 运行环境

| 依赖 | 要求 |
|------|------|
| Burp Suite | 2026（使用 Montoya API） |
| Java | JDK 24（Burp 便携版自带） |
| 操作系统 | Windows |

**注意：** 本插件针对 Burp Suite 2026 设计，使用新的 Montoya API（`burp.api.montoya.BurpExtension`），不兼容旧版 `IBurpExtender`。

## 构建

```powershell
.\build.ps1
```

构建脚本会自动下载 Montoya API JAR（如尚未存在），编译源码并打包到 `target/bFontZoom-1.0.0.jar`。

### 依赖

- `lib/montoya-api-2026.4.jar` — [Maven Central](https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/2026.4/)

## 安装

1. 打开 Burp Suite → **Extender** → **Extensions**
2. 点击 **Add**，选择 Extension type: **Java**
3. 选择构建好的 `target/bFontZoom-1.0.0.jar`
4. 插件立即生效，无需重启 Burp

## 使用

1. 打开任意包含数据包的页面（如 Proxy → HTTP history、Repeater、Intruder 等）
2. **用鼠标点击**一下要缩放的内容区域（让组件获得焦点）
3. 按住 **Ctrl + 滚轮上/下** 即可缩放该区域的字体
4. 普通滚轮不受影响，继续正常上下浏览

## 项目结构

```
bp-font-zoom/
├── src/main/java/exp/fontzoom/
│   └── FontZoomExtender.java    # 插件核心代码
├── lib/
│   └── montoya-api-2026.4.jar   # Burp Montoya API 依赖
├── target/
│   ├── classes/                  # 编译产物（.class）
│   └── bFontZoom-1.0.0.jar      # 最终插件 JAR
├── build.ps1                    # 一键构建脚本
├── pom.xml                      # Maven 配置（备用构建方案）
└── .gitignore
```

## 核心实现思路

### 挑战

Burp Suite 2026 的 UI 架构与普通 Swing 应用不同：
- 不存在 `JTextPane`、`JEditorPane` 等标准文本组件
- 文本由混淆后的自定义类（`Zkwl`、`Zkw1`、`Zkwa` 等）渲染
- 滚轮事件集中在顶层窗口处理，子组件不直接接收
- Burp 便携版的 JDK 是 jlink 精简版，部分标准 AWT 方法不可用

### 解决方案

采用 **AWT Toolkit 级全局事件监听**：

```java
Toolkit.getDefaultToolkit().addAWTEventListener(
    event -> onWheelEvent((MouseWheelEvent) event),
    AWTEvent.MOUSE_WHEEL_EVENT_MASK
);
```

`Toolkit` 监听器在事件分发前拦截 ALL 鼠标滚轮事件，早于任何 Swing 组件处理：

1. **Ctrl + Scroll** → 调用 `e.consume()` 阻止 Burp 默认处理，然后对当前焦点组件执行 `setFont(font.deriveFont(newSize))`
2. **普通 Scroll** → 不消耗、不干预，事件正常流到 Burp 的 `JScrollPane`，上下浏览保持原样

### 目标组件定位

使用三级优先级链确定缩放哪个组件：

```
1. KeyboardFocusManager.getFocusOwner() — 当前获得焦点的组件
2. lastClickedJC — 最后点击的 JComponent（通过 MouseListener 追踪）
3. lastHoveredJC — 鼠标悬停的 JComponent（通过 mouseMove 追踪）
```

点击和悬停事件通过顶层 `Window` 的 `MouseListener` 捕获，用 `getComponentAt()` 递归向下查找最深层的非排除 `JComponent`（排除 `JScrollPane`、`JViewport` 等容器类）。

### 动态 Tab 支持

通过 `Window.addPropertyChangeListener("children", ...)` 监听 Burp 动态添加/删除 Tab 面板。

### 持久化

使用 `java.util.prefs.Preferences` 将缩放级别写入 Windows 注册表（`HKCU\Software\JavaSoft\Prefs\exp\fontzoom`），下次启动 Burp 时自动恢复。

## 许可证

MIT

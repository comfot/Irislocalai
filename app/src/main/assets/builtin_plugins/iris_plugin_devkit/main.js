// 插件开发套件：面板与入口；实际文件/打包/安装由宿主 plugin_dev 工具完成。
(function () {
  ToolPkg.registerToolboxUiModule({
    id: "panel",
    title: "插件开发套件",
    screen: "ui/panel.js"
  });
  ToolPkg.registerNavigationEntry({
    id: "devkit_nav",
    title: "插件开发套件",
    surface: "main_sidebar_plugins",
    route: "panel"
  });
  ToolPkg.registerSettingsEntry({
    id: "devkit_settings",
    title: "插件开发套件",
    surface: "advanced",
    screen: "ui/panel.js"
  });
})();

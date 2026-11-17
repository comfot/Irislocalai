// 运行时由宿主 VrmaGenerationEnvironment 在 PRoot 中启动，
// 本插件包只用于在插件列表/插件环境变量页中展示与启停；无需注册 Hook。
(function () {
  try { irisHost.log('iris.vrma_generator loaded') } catch (error) { }
})();

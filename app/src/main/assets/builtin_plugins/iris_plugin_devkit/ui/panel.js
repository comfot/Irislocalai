// 插件开发套件面板。所有文件/打包/安装操作都走宿主 plugin_dev 工具。
module.exports = function (ctx) {
  const [status, setStatus] = ctx.useState("status", "")
  const [message, setMessage] = ctx.useState("message", "")
  const [busy, setBusy] = ctx.useState("busy", false)
  const [project, setProject] = ctx.useState("project", "")
  const [template, setTemplate] = ctx.useState("template", "tool")
  const [fileName, setFileName] = ctx.useState("file", "manifest.json")
  const [content, setContent] = ctx.useState("content", "")
  const [capabilities, setCapabilities] = ctx.useState("capabilities", "")

  async function run(op, args) {
    const payload = Object.assign({ op: op }, args || {})
    return await ctx.callTool("plugin_dev", payload)
  }

  async function withBusy(label, action) {
    setBusy(true)
    setMessage(label)
    try {
      const result = await action()
      setMessage(result)
      return result
    } catch (error) {
      setMessage("失败：" + String(error && error.message || error))
    } finally {
      setBusy(false)
    }
  }

  async function refresh() {
    const result = await run("status")
    setStatus(result)
  }

  return ctx.UI.Column({ fillMaxWidth: true, onLoad: function () { refresh() } }, [
    ctx.UI.Text({ text: "插件开发套件", bold: true }),
    ctx.UI.Text({ text: "工程目录 filesDir/plugin_dev；ZIP 上限 256 MB。" }),
    ctx.UI.Row({ fillMaxWidth: true }, [
      ctx.UI.Button({ text: "刷新状态", enabled: !busy, onClick: function () { withBusy("读取状态…", refresh) } }),
      ctx.UI.Button({
        text: "查看能力",
        enabled: !busy,
        onClick: function () {
          withBusy("读取宿主能力…", async function () {
            const result = await run("capabilities")
            setCapabilities(result)
          })
        }
      })
    ]),
    capabilities ? ctx.UI.Text({ text: capabilities }) : ctx.UI.Text({ text: "" }),
    ctx.UI.Divider({}),
    ctx.UI.Text({ text: "新建工程", bold: true }),
    ctx.UI.OutlinedTextField({
      label: "工程名（字母/数字/点/下划线/中划线）",
      value: project,
      onValueChange: function (value) { setProject(value) }
    }),
    ctx.UI.OutlinedTextField({
      label: "模板：tool / ui / hook / host",
      value: template,
      onValueChange: function (value) { setTemplate(value) }
    }),
    ctx.UI.Button({
      text: "创建工程",
      enabled: !busy && project.trim().length > 0,
      onClick: function () {
        withBusy("创建工程…", async function () {
          const result = await run("create_project", { project: project.trim(), template: template.trim() || "tool" })
          await refresh()
          return result
        })
      }
    }),
    ctx.UI.Divider({}),
    ctx.UI.Text({ text: "编辑文件", bold: true }),
    ctx.UI.OutlinedTextField({
      label: "工程名",
      value: project,
      onValueChange: function (value) { setProject(value) }
    }),
    ctx.UI.OutlinedTextField({
      label: "文件路径",
      value: fileName,
      onValueChange: function (value) { setFileName(value) }
    }),
    ctx.UI.Row({ fillMaxWidth: true }, [
      ctx.UI.Button({
        text: "读取",
        enabled: !busy && project.trim().length > 0,
        onClick: function () {
          withBusy("读取文件…", async function () {
            const text = await run("read_file", { project: project.trim(), path: fileName.trim() })
            setContent(text)
            return "已读取 " + fileName
          })
        }
      }),
      ctx.UI.Button({
        text: "保存",
        enabled: !busy && project.trim().length > 0,
        onClick: function () {
          withBusy("保存文件…", async function () {
            const result = await run("write_file", {
              project: project.trim(),
              path: fileName.trim(),
              content: content
            })
            await refresh()
            return result
          })
        }
      })
    ]),
    ctx.UI.OutlinedTextField({
      label: "文件内容",
      value: content,
      multiLine: true,
      maxLines: 14,
      onValueChange: function (value) { setContent(value) }
    }),
    ctx.UI.Divider({}),
    ctx.UI.Text({ text: "校验、打包、安装", bold: true }),
    ctx.UI.Row({ fillMaxWidth: true }, [
      ctx.UI.Button({
        text: "校验",
        enabled: !busy && project.trim().length > 0,
        onClick: function () {
          withBusy("校验…", function () { return run("validate_project", { project: project.trim() }) })
        }
      }),
      ctx.UI.Button({
        text: "打包推送",
        enabled: !busy && project.trim().length > 0,
        onClick: function () {
          withBusy("打包并推送到导出目录…", function () { return run("package_project", { project: project.trim() }) })
        }
      })
    ]),
    ctx.UI.Row({ fillMaxWidth: true }, [
      ctx.UI.Button({
        text: "安装",
        enabled: !busy && project.trim().length > 0,
        onClick: function () {
          withBusy("安装中…", async function () {
            const result = await run("install_project", { project: project.trim(), enabled: true })
            await refresh()
            return result
          })
        }
      }),
      ctx.UI.Button({
        text: "重新加载",
        enabled: !busy,
        onClick: function () {
          withBusy("重载…", async function () {
            const result = await run("reload_plugin", { pluginId: "iris.plugin_devkit" })
            await refresh()
            return result
          })
        }
      })
    ]),
    ctx.UI.Divider({}),
    ctx.UI.Text({ text: message }),
    ctx.UI.Text({ text: status })
  ])
}

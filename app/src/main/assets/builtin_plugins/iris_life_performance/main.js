// 生活空间自动动作/表情：目录来自当前 3D 绑定；模型可在回复末尾输出
// <performance motion="别名" expression="别名"></performance>，该标签不展示给用户。
(function () {
  var CACHE_MS = 60 * 1000
  var expressionEntries = []
  var motionEntries = []
  var expressionKeywords = []
  var motionKeywords = []
  var loadedAt = 0
  var loading = null
  var catalogFile = 'performance_catalog.md'

  function vrmaGenerationEnabled() {
    try { return String(getEnv('VRMA_GENERATION') || '').toUpperCase() === 'TRUE' } catch (error) { return false }
  }

  function addEntry(entries, alias, target) {
    var key = String(alias || '').trim()
    var value = String(target || '').trim()
    if (!key || !value) return
    if (entries.some(function (item) { return item.alias === key && item.target === value })) return
    entries.push({ alias: key, target: value })
  }

  function isUsableKeyword(keyword) {
    var value = String(keyword || '').trim()
    if (!value) return false
    if (/^[\x00-\x7F]+$/.test(value)) return value.length >= 3
    return value.length >= 2 || /[\u4e00-\u9fa5]/.test(value)
  }

  function addKeyword(list, keyword, action) {
    var value = String(keyword || '').trim()
    var target = String(action || '').trim()
    if (!isUsableKeyword(value) || !target) return
    if (list.some(function (item) { return item.keyword === value && item.action === target })) return
    list.push({ keyword: value, action: target })
  }

  function parseCatalog(text, entries) {
    String(text || '').split('\n').forEach(function (line) {
      var binding = line.match(/^\s*-\s*(.+?)\s*→\s*(.+?)\s*$/)
      if (binding) {
        addEntry(entries, binding[1].trim(), binding[2].trim())
        return
      }
      var actions = line.match(/可添加(?:动作|表情)[^：:]*[：:]\s*(.+)$/)
      if (actions) {
        actions[1].split(/[、,，]/).forEach(function (name) {
          addEntry(entries, name.trim(), name.trim())
        })
      }
    })
  }

  function buildKeywords(entries) {
    var list = []
    entries.forEach(function (entry) {
      addKeyword(list, entry.alias, entry.alias)
      addKeyword(list, entry.target, entry.alias)
    })
    list.sort(function (a, b) { return b.keyword.length - a.keyword.length })
    return list
  }

  function formatEntries(entries, limit) {
    var names = []
    var seen = {}
    entries.forEach(function (entry) {
      var text = entry.alias === entry.target
        ? entry.alias
        : entry.alias + '→' + entry.target
      if (seen[text]) return
      seen[text] = true
      names.push(text)
    })
    return names.slice(0, limit).join('、')
  }

  function catalogMarkdown() {
    var lines = [
      '# 动作与表情目录',
      '',
      '同步来源：3D 控制 › 绑定配置。动作别名用于 motion_control，表情别名用于 expression_control。',
      '',
      '## 动作（' + motionEntries.length + '）',
      formatEntries(motionEntries, 200) || '（无）',
      '',
      '## 表情（' + expressionEntries.length + '）',
      formatEntries(expressionEntries, 200) || '（无）',
      '',
    ]
    return lines.join('\n')
  }

  function writeCatalogFile() {
    try {
      var directory = getPluginConfigDir()
      irisHost.writeText(directory + '/' + catalogFile, catalogMarkdown())
    } catch (error) { }
  }

  async function refreshCatalogs(force) {
    if (!force && Date.now() - loadedAt < CACHE_MS) return
    if (loading) return loading
    loading = (async function () {
      var expression = []
      var motion = []
      try {
        var expressionText = await toolCall('expression_control', { action: 'list' })
        parseCatalog(expressionText, expression)
      } catch (error) { }
      try {
        var motionText = await toolCall('motion_control', { action: 'list' })
        parseCatalog(motionText, motion)
      } catch (error) { }
      expressionEntries = expression
      motionEntries = motion
      expressionKeywords = buildKeywords(expression)
      motionKeywords = buildKeywords(motion)
      loadedAt = Date.now()
      writeCatalogFile()
      try {
        irisHost.log('life_performance catalogs: ' + expressionKeywords.length + ' expressions, ' + motionKeywords.length + ' motions')
      } catch (error) { }
    })()
    try {
      await loading
    } finally {
      loading = null
    }
  }

  function matchKeyword(text, list) {
    for (var i = 0; i < list.length; i++) {
      var item = list[i]
      if (text.indexOf(item.keyword) >= 0) return item.action
    }
    return null
  }

  function pickEntry(entries, hints) {
    for (var i = 0; i < hints.length; i++) {
      var hint = hints[i].toLowerCase()
      for (var j = 0; j < entries.length; j++) {
        var entry = entries[j]
        var alias = String(entry.alias || '').toLowerCase()
        var target = String(entry.target || '').toLowerCase()
        if (alias.indexOf(hint) >= 0 || target.indexOf(hint) >= 0) return entry.alias
      }
    }
    return ''
  }

  function semanticExpression(text) {
    var rules = [
      [["笑", "开心", "高兴", "快乐", "happy", "smile", "laugh"], ["开心", "高兴", "happy", "smile", "笑"]],
      [["哭", "难过", "伤心", "委屈", "sad", "cry"], ["难过", "伤心", "sad", "哭"]],
      [["生气", "愤怒", "angry"], ["生气", "怒", "angry"]],
      [["惊讶", "震惊", "意外", "surprised", "wow"], ["惊讶", "surprised"]],
      [["害羞", "脸红", "shy", "blush"], ["害羞", "shy"]],
      [["思考", "想想", "think", "hmm"], ["思考", "think"]],
    ]
    for (var i = 0; i < rules.length; i++) {
      if (rules[i][0].some(function (word) { return text.indexOf(word) >= 0 })) {
        var picked = pickEntry(expressionEntries, rules[i][1])
        if (picked) return picked
      }
    }
    return ''
  }

  function semanticMotion(text) {
    var rules = [
      [["挥手", "招手", "打招呼", "再见", "拜拜", "wave", "greet", "bye"], ["挥手", "招手", "打招呼", "wave", "greet"]],
      [["点头", "同意", "nod"], ["点头", "nod"]],
      [["摇头", "拒绝", "shake"], ["摇头", "shake"]],
      [["跳舞", "舞蹈", "dance"], ["舞", "dance"]],
      [["坐下", "坐着", "sit"], ["坐"]],
      [["站起", "站起来", "stand"], ["站"]],
    ]
    for (var i = 0; i < rules.length; i++) {
      if (rules[i][0].some(function (word) { return text.indexOf(word) >= 0 })) {
        var picked = pickEntry(motionEntries, rules[i][1])
        if (picked) return picked
      }
    }
    return ''
  }

  function attr(attrs, name) {
    var match = String(attrs || '').match(new RegExp(name + '\\s*=\\s*["\\\']([^"\\\']*)["\\\']', 'i'))
    return match ? match[1].trim() : ''
  }

  function isKnown(value, entries) {
    if (!value) return false
    return entries.some(function (entry) {
      return entry.alias === value || entry.target === value
    })
  }

  function promptBlock() {
    if (motionEntries.length === 0 && expressionEntries.length === 0) return ''
    var lines = [
      '【系统要求的表演标签】',
      '回复正文只写角色直接说出口的话，不要写动作、神态、表情、场景或第三人称旁白（例如“她笑了笑”“他抬起头”都不要写）。',
      '每次回复的最后必须单独输出一行表演标签，这是系统格式要求，优先于角色设定中“不要使用标签”的限制：',
      '<performance motion="动作别名" expression="表情别名"></performance>',
      '有合适的动作/表情就填写对应别名；没有合适的项时也必须输出空标签：',
      '<performance></performance>',
      '只使用下面列出的名称；标签不会展示给用户，也不要向用户解释。',
    ]
    if (motionEntries.length > 0) {
      lines.push('动作别名：' + formatEntries(motionEntries, 80))
    }
    if (expressionEntries.length > 0) {
      lines.push('表情别名：' + formatEntries(expressionEntries, 80))
    }
    return lines.join('\n')
  }

  ToolPkg.registerSystemPromptComposeHook({
    id: 'life_performance_prompt',
    function: async function (event) {
      var payload = (event && event.eventPayload) || {}
      try { irisHost.log('life_performance prompt space=' + String(payload.space || '') + ' len=' + String(payload.systemPrompt || '').length) } catch (error) { }
      if (payload.space !== 'LIFE') return
      if (vrmaGenerationEnabled()) {
        var vrmaBlock = [
          '【VRMA 动作控制】',
          '回复正文只写角色直接说出口的话，不要写动作、神态、表情、场景或第三人称旁白。',
          '在回复最后单独输出一行隐藏指令，描述角色要做的身体动作：',
          '<vrma_motion>英文动作描述，例如 wave happily and nod</vrma_motion>',
          '不要调用 motion_control、expression_control 或 vrma_generate 工具；宿主会根据 <vrma_motion> 自动生成。',
          '不要输出 <performance> 或表情/动作别名标签；只使用 <vrma_motion>。',
          '动作描述只写身体动作；没有动作时可省略该标签。标签不会展示给用户，也不要解释。',
        ].join('\n')
        return { systemPrompt: String(payload.systemPrompt || '') + '\n\n' + vrmaBlock }
      }
      await refreshCatalogs(false)
      var block = promptBlock()
      if (!block) return
      return { systemPrompt: String(payload.systemPrompt || '') + '\n\n' + block }
    },
  })

  ToolPkg.registerChatMessageHook({
    id: 'life_performance',
    function: async function (event) {
      var payload = (event && event.eventPayload) || {}
      if (payload.sender !== 'ai') return
      var text = String(payload.content || (payload.message && payload.message.content) || '')
      if (!text) return
      if (vrmaGenerationEnabled()) {
        var match = text.match(/<vrma_motion\b[^>]*>([\s\S]*?)<\/vrma_motion\s*>/i)
        var prompt = match ? String(match[1] || '').trim() : ''
        if (prompt) {
          try { await toolCall('vrma_generate', { prompt: prompt }) } catch (error) { }
        }
        return
      }
      await refreshCatalogs(false)
      var tag = text.match(/<performance\b([^>]*)>/i)
      var motion = tag ? attr(tag[1], 'motion') : ''
      var expression = tag ? attr(tag[1], 'expression') : ''
      if (!isKnown(motion, motionEntries)) motion = ''
      if (!isKnown(expression, expressionEntries)) expression = ''
      if (!motion) motion = matchKeyword(text, motionKeywords) || ''
      if (!expression) expression = matchKeyword(text, expressionKeywords) || ''
      if (!expression) expression = semanticExpression(text)
      if (!motion) motion = semanticMotion(text)
      if (expression) {
        try { await toolCall('expression_control', { action: expression }) } catch (error) { }
      }
      if (motion) {
        try { await toolCall('motion_control', { action: motion }) } catch (error) { }
      }
    },
  })
})();

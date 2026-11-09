(() => {
  'use strict'

  const status = document.getElementById('status')
  const canvas = document.getElementById('live2d-canvas')
  const config = window.__LIVE2D_CONFIG__ || {}
  // 通话页只露上半身：整体放大并把脚部移到画面下方之外。
  const portrait = !!config.portrait
  // 桌宠与通话页只展示不做交互：仍允许指针事件在页内被忽略。
  const interactive = config.interactive !== false
  const options = {
    expressions: config.expressions !== false,
    motions: config.motions !== false,
    idle: config.idle !== false,
    tap: config.tap !== false,
    mouth: config.mouth !== false,
  }
  const modelUrl = config.model || ''
  function showStatus(text) {
    if (!status) return
    status.hidden = false
    status.textContent = text
  }

  function hideStatus() {
    if (status) status.hidden = true
  }

  if (!modelUrl) {
    showStatus('没有选择 Live2D 模型')
    return
  }
  if (!window.PIXI || !PIXI.live2d || !PIXI.live2d.Live2DModel) {
    showStatus('Live2D 运行时未加载')
    return
  }

  const app = new PIXI.Application({
    view: canvas,
    width: window.innerWidth,
    height: window.innerHeight,
    backgroundAlpha: 0,
    antialias: true,
    autoStart: true,
    resolution: Math.min(window.devicePixelRatio || 1, 2),
    autoDensity: true,
    preserveDrawingBuffer: true,
  })
  PIXI.settings.SCALE_MODE = PIXI.SCALE_MODES.LINEAR

  let model = null
  let speaking = false
  let mood = 'neutral'
  let currentMood = ''
  let mouthPhase = 0
  let availableExpressions = []
  let availableMotions = []
  let zoom = 1
  let modelBaseWidth = 1
  let modelBaseHeight = 1
  let dragging = false
  let pointerId = null
  let lastX = 0
  let lastY = 0
  let moved = 0
  let pinchDistance = 0
  const pointers = new Map()
  const stage = document.getElementById('stage')

  function viewportWidth() {
    return stage.clientWidth || window.innerWidth || document.documentElement.clientWidth || 1
  }

  function viewportHeight() {
    return stage.clientHeight || window.innerHeight || document.documentElement.clientHeight || 1
  }

  function applyTransform() {
    if (!model) return
    const width = viewportWidth()
    const height = viewportHeight()
    if (portrait) {
      // 通话取景：直接按视口高度定缩放，不依赖模型宽度，避免宽画布把人物缩得很小。
      const bodyHeight = height * 1.35
      model.scale.set(bodyHeight / Math.max(1, modelBaseHeight))
      model.x = width / 2
      model.y = height * 1.47
      canvas.style.transform = 'none'
      return
    }
    const base = Math.min(width / Math.max(1, modelBaseWidth), height / Math.max(1, modelBaseHeight)) * 0.98
    model.scale.set(base * zoom)
    model.x = width / 2
    model.y = height * 0.99
    canvas.style.transform = 'none'
  }

  function fit() {
    const width = viewportWidth()
    const height = viewportHeight()
    if (width < 2 || height < 2) return
    app.renderer.resize(width, height)
    applyTransform()
    app.render()
  }

  const MOOD_ALIASES = {
    happy: ['happy', 'joy', 'smile', '笑', '开心', '高兴'],
    angry: ['angry', 'anger', '怒', '生气', '生气'],
    sad: ['sad', 'sorrow', '哭', '难过', '伤心'],
    surprised: ['surprised', 'surprise', '惊讶', '吃惊'],
    thinking: ['think', 'thinking', '思考', '沉思'],
    neutral: ['idle', 'neutral', 'normal', 'default', '普通'],
  }

  function safely(result) {
    if (result && typeof result.catch === 'function') result.catch(() => { })
  }

  function findName(list, aliases) {
    for (const alias of aliases) {
      const match = list.find((name) => name.toLowerCase() === alias.toLowerCase())
      if (match) return match
    }
    for (const alias of aliases) {
      const match = list.find((name) => name.toLowerCase().includes(alias.toLowerCase()))
      if (match) return match
    }
    return null
  }

  function applyMood() {
    if (!model || currentMood === mood) return
    const aliases = MOOD_ALIASES[mood] || []
    let expression = findName(availableExpressions, aliases)
    if (expression && options.expressions) {
      try { safely(model.expression(expression)) } catch (_) { }
    }
    let motion = findName(availableMotions, aliases)
    if (!motion) {
      if (mood === 'angry' && availableMotions.length > 1) motion = availableMotions[1]
      else if (mood === 'happy' && availableMotions.length > 0) motion = availableMotions[0]
    }
    if (motion && options.motions) {
      try { safely(model.motion(motion)) } catch (_) { }
    }
    currentMood = mood
  }

  let idleTimer = null

  function stopIdleMotion() {
    if (idleTimer) {
      clearInterval(idleTimer)
      idleTimer = null
    }
  }

  function startIdleMotion() {
    stopIdleMotion()
    if (!options.idle) return
    idleTimer = setInterval(() => {
      if (!model || speaking || pointers.size > 0) return
      const pool = availableMotions.filter((name) => /idle|待機|待机|呼吸|loop/i.test(name))
      const name = pool[0] || availableMotions[0]
      if (name) {
        try { safely(model.motion(name, undefined, { loop: true })) } catch (_) { }
      }
    }, 9000)
  }

  function clampOffsets() {
    zoom = Math.max(0.65, Math.min(1.8, zoom))
  }

  function pairDistance() {
    const values = Array.from(pointers.values())
    if (values.length < 2) return 0
    return Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y)
  }

  canvas.addEventListener('pointerdown', (event) => {
    if (!interactive) return
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
    try { canvas.setPointerCapture(event.pointerId) } catch (_) { }
    if (pointers.size === 1) {
      pointerId = event.pointerId
      dragging = true
      moved = 0
      lastX = event.clientX
      lastY = event.clientY
    } else {
      moved = 999
      pinchDistance = pairDistance()
    }
  })

  canvas.addEventListener('pointermove', (event) => {
    if (!interactive) return
    const current = pointers.get(event.pointerId)
    if (!current) return
    current.x = event.clientX
    current.y = event.clientY
    if (pointers.size >= 2) {
      const distance = pairDistance()
      if (distance > 0 && pinchDistance > 0) {
        zoom *= distance / pinchDistance
        clampOffsets()
        applyTransform()
      }
      pinchDistance = distance
      return
    }
    if (event.pointerId !== pointerId || !dragging) return
    const dx = event.clientX - lastX
    const dy = event.clientY - lastY
    moved += Math.abs(dx) + Math.abs(dy)
    lastX = event.clientX
    lastY = event.clientY
    if (model && typeof model.focus === 'function') {
      try { model.focus(event.clientX, event.clientY, true) } catch (_) { }
    }
  })

  function endPointer(event) {
    const wasPrimary = event.pointerId === pointerId
    pointers.delete(event.pointerId)
    try { canvas.releasePointerCapture(event.pointerId) } catch (_) { }
    if (wasPrimary) {
      if (moved < 8 && model && options.tap) {
        try {
          const name = availableMotions.length > 0
            ? availableMotions[Math.floor(Math.random() * availableMotions.length)]
            : 'A'
          safely(model.motion(name))
        } catch (_) { }
      }
      pointerId = null
      dragging = false
    }
    pinchDistance = 0
  }

  canvas.addEventListener('pointerup', endPointer)
  canvas.addEventListener('pointercancel', endPointer)
  canvas.addEventListener('wheel', (event) => {
    if (!interactive) return
    zoom *= event.deltaY > 0 ? 0.94 : 1.06
    clampOffsets()
    applyTransform()
  }, { passive: true })
  window.addEventListener('resize', fit)
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(() => fit()).observe(stage)
  }
  requestAnimationFrame(fit)
  setTimeout(fit, 300)

  function setSpeaking(value) {
    speaking = Boolean(value)
  }

  function setMood(value) {
    if (!value) return
    mood = String(value)
    applyMood()
  }

  window.__live2dSetSpeaking = setSpeaking
  window.__live2dSetMood = setMood

  window.irisLive2d = {
    setOptions(next) {
      if (!next) return
      if (typeof next.expressions === 'boolean') options.expressions = next.expressions
      if (typeof next.motions === 'boolean') options.motions = next.motions
      if (typeof next.idle === 'boolean') options.idle = next.idle
      if (typeof next.tap === 'boolean') options.tap = next.tap
      if (typeof next.mouth === 'boolean') options.mouth = next.mouth
      if (options.idle) startIdleMotion()
      else stopIdleMotion()
      currentMood = ''
      applyMood()
    },
    playMotion(name) {
      if (!model || !name) return
      try { safely(model.motion(String(name))) } catch (_) { }
    },
    setExpression(name) {
      if (!model || !name) return
      try { safely(model.expression(String(name))) } catch (_) { }
    },
    list() {
      return { expressions: availableExpressions, motions: availableMotions }
    },
  }

  PIXI.Ticker.shared.add(() => {
    if (!model || !model.internalModel || !model.internalModel.coreModel) return
    const core = model.internalModel.coreModel
    if (speaking && options.mouth) {
      mouthPhase += 0.3
      const open = 0.18 + 0.5 * Math.abs(Math.sin(mouthPhase))
      try { core.setParameterValueById('ParamMouthOpenY', open, 1) } catch (_) { }
    } else {
      mouthPhase = 0
      try { core.setParameterValueById('ParamMouthOpenY', 0, 1) } catch (_) { }
    }
  }, PIXI.UPDATE_PRIORITY.LOW)

  ;(async () => {
    try {
      model = await PIXI.live2d.Live2DModel.from(modelUrl, {
        autoInteract: false,
        autoUpdate: true,
      })
      model.anchor.set(0.5, 1)
      app.stage.addChild(model)
      modelBaseWidth = model.width || 1
      modelBaseHeight = model.height || 1
      fit()
      hideStatus()
      try {
        const json = await (await fetch(modelUrl)).json()
        const refs = json.FileReferences || {}
        const expressions = refs.Expressions || {}
        const motions = refs.Motions || {}
        availableExpressions = Array.isArray(expressions)
          ? expressions.map((item) => item.Name).filter(Boolean)
          : Object.keys(expressions)
        availableMotions = Object.keys(motions)
      } catch (_) { }
      currentMood = ''
      applyMood()
      startIdleMotion()
    } catch (error) {
      console.error('live2d model error', error)
      showStatus('Live2D 模型加载失败：' + (error && error.message ? error.message : '文件无法读取'))
    }
  })()
})()

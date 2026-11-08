(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const status = document.getElementById('status')
  const config = window.__MMD_CONFIG__ || {}
  const params = new URLSearchParams(location.hash.startsWith('#') ? location.hash.substring(1) : '')
  const modelUrl = config.model || (params.get('model') ? decodeURIComponent(params.get('model')) : '')
  const motionUrl = config.motion || ''
  const idleUrl = config.idle || 'assets/model/mmd-idle.vmd'

  window.addEventListener('error', (event) => {
    console.error('[mmd] window error', event.message, event.filename, event.lineno)
  })
  window.addEventListener('unhandledrejection', (event) => {
    console.error('[mmd] unhandled rejection', String(event.reason))
  })

  function showStatus(text) {
    if (!status) return
    status.hidden = false
    status.textContent = text
  }

  function hideStatus() {
    if (status) status.hidden = true
  }

  if (!modelUrl) {
    showStatus('没有选择 MMD 模型')
    return
  }
  if (!window.BABYLON || !window.BABYLONMMD) {
    showStatus('MMD 运行时未加载')
    return
  }

  let engine
  let scene
  let camera
  let runtime
  let model
  let modelRoot
  let fitRadius = 30
  let baseScale = 1
  let activeMood = null
  let mouthValue = 0
  const moodMorphs = {
    happy: ['笑い', 'にっこり', '喜び', '笑顔'],
    angry: ['怒り', 'ぷんぷん', '怒'],
    sad: ['困る', '泣き', '悲しみ', '涙'],
    surprised: ['びっくり', '驚き', '驚'],
    think: ['じと目', '考え', '困り顔'],
  }

  function setMorph(names, weight) {
    if (!model || !model.morph || !names) return
    for (const name of names) {
      try {
        model.morph.setMorphWeight(name, weight)
        return
      } catch (_) {
      }
    }
  }

  function resetMoodMorphs() {
    if (!activeMood) return
    for (const name of moodMorphs[activeMood] || []) {
      try { model.morph.setMorphWeight(name, 0) } catch (_) { }
    }
    activeMood = null
  }

  window.irisMmd = {
    setMood(mood) {
      if (!model) return
      if (mood === activeMood) return
      resetMoodMorphs()
      const names = moodMorphs[mood]
      if (!names) return
      setMorph(names, 0.85)
      activeMood = mood
    },
    setMouthOpen(value) {
      mouthValue = Math.max(0, Math.min(1, Number(value) || 0))
      if (!model) return
      setMorph(['あ', '口あ', '口_A', 'A', 'mouthOpen'], mouthValue * 0.85)
    },
    resetCamera() {
      if (!camera) return
      camera.alpha = -Math.PI / 2
      camera.beta = Math.PI / 2
      camera.radius = fitRadius
      camera.target.set(0, (model ? modelHeight * 0.52 : 10), 0)
    },
    setAnimation(url) {
      if (!model || !url) return Promise.resolve(false)
      return loadMotion(url)
    },
    resize() {
      resize()
    },
  }

  let modelHeight = 20

  const canvas = document.createElement('canvas')
  stage.appendChild(canvas)
  try {
    engine = new BABYLON.Engine(canvas, false, {
      preserveDrawingBuffer: true,
      stencil: true,
      alpha: true,
      premultipliedAlpha: false,
      powerPreference: 'high-performance',
    })
    baseScale = Math.max(0.74, 1 / Math.min(window.devicePixelRatio || 1, 1.35))
    engine.setHardwareScalingLevel(baseScale)
  } catch (error) {
    console.error('mmd engine error', error)
    showStatus('这台设备无法显示 MMD 模型（WebGL 不可用）')
    return
  }

  scene = new BABYLON.Scene(engine)
  scene.clearColor = new BABYLON.Color4(0, 0, 0, 0)
  scene.autoClear = true
  scene.skipPointerMovePicking = true
  scene.imageProcessingConfiguration.toneMappingEnabled = false
  scene.imageProcessingConfiguration.exposure = 1

  camera = new BABYLON.ArcRotateCamera(
    'mmdCamera',
    -Math.PI / 2,
    Math.PI / 2,
    40,
    new BABYLON.Vector3(0, 10, 0),
    scene,
  )
  camera.fov = 0.62
  camera.minZ = 1
  camera.maxZ = 1000
  camera.inertia = 0.55
  camera.panningSensibility = 0
  camera.wheelPrecision = 35
  camera.pinchPrecision = 90
  camera.useNaturalPinchZoom = true

  const pointers = new Map()
  let dragX = 0
  let dragY = 0
  let pinchDistance = 0
  let pinchRadius = 0
  canvas.addEventListener('pointerdown', (event) => {
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
    try { canvas.setPointerCapture(event.pointerId) } catch (_) { }
    if (pointers.size === 1) {
      dragX = event.clientX
      dragY = event.clientY
    } else if (pointers.size === 2) {
      const values = Array.from(pointers.values())
      pinchDistance = Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y)
      pinchRadius = camera.radius
    }
    event.preventDefault()
  }, { passive: false })

  canvas.addEventListener('pointermove', (event) => {
    const point = pointers.get(event.pointerId)
    if (!point) return
    point.x = event.clientX
    point.y = event.clientY
    if (pointers.size >= 2) {
      const values = Array.from(pointers.values())
      const distance = Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y)
      if (pinchDistance > 1 && distance > 1) {
        camera.radius = Math.max(
          camera.lowerRadiusLimit,
          Math.min(camera.upperRadiusLimit, pinchRadius * pinchDistance / distance),
        )
      }
    } else {
      camera.alpha -= (event.clientX - dragX) * 0.008
      camera.beta = Math.max(0.25, Math.min(1.55, camera.beta + (event.clientY - dragY) * 0.005))
      dragX = event.clientX
      dragY = event.clientY
    }
    event.preventDefault()
  }, { passive: false })

  function endPointer(event) {
    pointers.delete(event.pointerId)
    try { canvas.releasePointerCapture(event.pointerId) } catch (_) { }
  }
  canvas.addEventListener('pointerup', endPointer)
  canvas.addEventListener('pointercancel', endPointer)

  const hemi = new BABYLON.HemisphericLight('mmdHemi', new BABYLON.Vector3(0, 1, 0), scene)
  hemi.intensity = 0.8
  hemi.groundColor = new BABYLON.Color3(0.3, 0.3, 0.4)
  const key = new BABYLON.DirectionalLight('mmdKey', new BABYLON.Vector3(0.45, -1, 0.35), scene)
  key.intensity = 0.4
  key.position = new BABYLON.Vector3(8, 20, -12)

  function fitCamera() {
    const width = viewportWidth()
    const height = viewportHeight()
    const aspect = Math.max(0.2, width / height)
    const halfHeight = Math.max(1, modelHeight / 2)
    const fitHeight = halfHeight / Math.tan(camera.fov / 2)
    fitRadius = fitHeight * (aspect < 0.75 ? 0.72 : 0.85)
    camera.radius = fitRadius
    camera.lowerRadiusLimit = fitRadius * 0.32
    camera.upperRadiusLimit = fitRadius * 2.8
    camera.target.set(0, modelHeight * 0.52, 0)
  }

  async function loadMotion(url) {
    try {
      const animation = await new BABYLONMMD.VmdLoader(scene).loadAsync('mmdMotion', url)
      const handle = model.createRuntimeAnimation(animation)
      model.setRuntimeAnimation(handle)
      return true
    } catch (error) {
      console.warn('mmd motion failed', url, error)
      return false
    }
  }

  function applyModelBounds() {
    modelRoot.computeWorldMatrix(true)
    const bounds = modelRoot.getHierarchyBoundingVectors(true)
    const width = bounds.max.x - bounds.min.x
    const height = bounds.max.y - bounds.min.y
    if (!Number.isFinite(height) || height <= 0.001) return
    modelHeight = height
    modelRoot.position.x -= (bounds.min.x + bounds.max.x) / 2
    modelRoot.position.z -= (bounds.min.z + bounds.max.z) / 2
    modelRoot.position.y -= bounds.min.y
    modelRoot.computeWorldMatrix(true)
    camera.target.set(0, height * 0.52, 0)
    fitCamera()
  }

  async function load() {
    try {
      showStatus('正在初始化 MMD 物理…')
      const wasm = await BABYLONMMD.GetMmdWasmInstance(new BABYLONMMD.MmdWasmInstanceTypeSPR())
      runtime = new BABYLONMMD.MmdWasmRuntime(wasm, scene, new BABYLONMMD.MmdWasmPhysics(scene))
      runtime.register(scene)

      showStatus('正在加载 MMD 模型…')
      const container = await BABYLON.SceneLoader.LoadAssetContainerAsync(modelUrl, '', scene)
      container.addAllToScene()
      modelRoot = container.meshes[0]
      if (!modelRoot) throw new Error('模型没有可用网格')
      applyModelBounds()

      model = runtime.createMmdModel(modelRoot, {
        materialProxyConstructor: BABYLONMMD.MmdStandardMaterialProxy,
      })
      if (!model) throw new Error('MMD 运行时初始化失败')

      let loaded = false
      if (motionUrl) loaded = await loadMotion(motionUrl)
      if (!loaded) loaded = await loadMotion(idleUrl)
      runtime.playAnimation()
      if (!loaded) showStatus('模型已加载，但没有可用动作')
      else hideStatus()
      if (config.mood) window.irisMmd.setMood(config.mood)
      if (config.speaking) window.irisMmd.setMouthOpen(0.28)
      window.__irisMmdReady = true
    } catch (error) {
      console.error('mmd load error', error)
      showStatus('MMD 模型加载失败：' + (error && error.message ? error.message : '文件无法读取'))
    }
  }

  function viewportWidth() {
    return Math.max(1, stage.clientWidth || window.innerWidth || document.documentElement.clientWidth || 1)
  }

  function viewportHeight() {
    return Math.max(1, stage.clientHeight || window.innerHeight || document.documentElement.clientHeight || 1)
  }

  function resize() {
    const width = vw()
    const height = vh()
    if (width < 2 || height < 2) return
    stage.style.width = width + 'px'
    stage.style.height = height + 'px'
    engine.resize()
    fitCamera()
  }

  function vw() {
    return window.innerWidth || document.documentElement.clientWidth || stage.clientWidth || 1
  }

  function vh() {
    return window.innerHeight || document.documentElement.clientHeight || stage.clientHeight || 1
  }

  window.addEventListener('resize', resize)
  window.visualViewport?.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(resize).observe(stage)
    new ResizeObserver(resize).observe(document.documentElement)
  }
  requestAnimationFrame(resize)
  setTimeout(resize, 300)

  let lastQualityCheck = 0
  scene.onAfterRenderObservable.add(() => {
    const now = performance.now()
    if (now - lastQualityCheck < 4000) return
    lastQualityCheck = now
    if (now < 6000) return
    const fps = engine.getFps()
    const current = engine.getHardwareScalingLevel()
    if (fps > 0 && fps < 27 && current < baseScale + 0.45) {
      engine.setHardwareScalingLevel(Math.min(baseScale + 0.45, current + 0.1))
    } else if (fps > 50 && current > baseScale) {
      engine.setHardwareScalingLevel(Math.max(baseScale, current - 0.08))
    }
  })

  load()
  engine.runRenderLoop(() => scene.render())
})()

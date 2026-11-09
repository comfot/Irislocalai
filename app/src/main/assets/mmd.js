(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const status = document.getElementById('status')
  const config = window.__MMD_CONFIG__ || {}
  // 通话页只露上半身：抬高相机目标并拉近。
  const portrait = !!config.portrait
  const interactive = config.interactive !== false
  const params = new URLSearchParams(location.hash.startsWith('#') ? location.hash.substring(1) : '')
  const modelUrl = config.model || (params.get('model') ? decodeURIComponent(params.get('model')) : '')
  const motionUrl = config.motion || ''
  const idleUrl = config.idle || 'assets/model/mmd-idle.vmd'
  const idleMotionUrl = config.idleMotion || ''
  const motionAssetBase = 'https://iris.local/motionassets/'

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
  let headY = 0
  const headTmp = new BABYLON.Vector3()

  /** 用头骨的实际世界高度作为取景基准；找不到头骨时退回包围盒高度。 */
  function computeHeadY() {
    try {
      const skeletons = []
      try { if (modelRoot && modelRoot.skeleton) skeletons.push(modelRoot.skeleton) } catch (_) { }
      try { if (model && model.skeleton) skeletons.push(model.skeleton) } catch (_) { }
      try {
        if (scene && scene.skeletons) {
          for (const skeleton of scene.skeletons) skeletons.push(skeleton)
        }
      } catch (_) { }
      try {
        if (modelRoot && modelRoot.getChildMeshes) {
          for (const mesh of modelRoot.getChildMeshes(false)) {
            if (mesh && mesh.skeleton) skeletons.push(mesh.skeleton)
          }
        }
      } catch (_) { }
      const bones = []
      for (const skeleton of skeletons) {
        const list = skeleton && skeleton.bones ? skeleton.bones : []
        for (const bone of list) bones.push(bone)
      }
      const head = bones.find((bone) => {
        const name = (bone.name || '').toLowerCase()
        return name === '頭' || name === '头' || name === 'head' || name.includes('head') || name.includes('頭')
      })
      if (head && modelRoot) {
        const local = head.getAbsolutePosition()
        BABYLON.Vector3.TransformCoordinatesToRef(local, modelRoot.getWorldMatrix(), headTmp)
        if (Number.isFinite(headTmp.y) && headTmp.y > modelHeight * 0.2) {
          headY = headTmp.y
          return
        }
      }
      // 没有头骨时取最高骨骼作为头顶近似（跳过 IK/物理辅助骨）。
      let bestY = 0
      for (const bone of bones) {
        try {
          const name = (bone.name || '').toLowerCase()
          if (name.includes('ik') || name.includes('effector') || name.includes('エフェクタ') ||
            name.includes('skirt') || name.includes('スカート')) continue
          const point = bone.getAbsolutePosition()
          BABYLON.Vector3.TransformCoordinatesToRef(point, modelRoot.getWorldMatrix(), headTmp)
          if (Number.isFinite(headTmp.y) && headTmp.y > bestY) bestY = headTmp.y
        } catch (_) { }
      }
      if (bestY > 0) {
        headY = bestY
      }
    } catch (error) {
      console.warn('[mmd-frame] head bone lookup failed: ' + error)
    }
  }

  function portraitFocusY() {
    return headY > 0 ? headY * 0.92 : modelHeight * 0.88
  }

  /** 包围盒可能被骨骼撑大，有头骨时以头高作为身体高度。 */
  function portraitHeight() {
    return headY > 0 ? headY : modelHeight
  }

  function portraitVisibleHeight() {
    return headY > 0 ? headY * 0.5 : modelHeight * 0.45
  }
  let mouthValue = 0
  let mouthTarget = 0
  const moodMorphs = {
    happy: ['笑い', 'にっこり', '喜び', '笑顔'],
    angry: ['怒り', 'ぷんぷん', '怒'],
    sad: ['困る', '泣き', '悲しみ', '涙'],
    surprised: ['びっくり', '驚き', '驚'],
    think: ['じと目', '考え', '困り顔'],
    thinking: ['じと目', '考え', '困り顔'],
  }
  const blinkMorphs = ['まばたき', '瞬き', 'blink', 'ウィンク']
  let moodState = { names: null, target: 0, current: 0 }
  let blinkTimer = 2 + Math.random() * 3
  let blinkElapsed = -1

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

  function updateMood(dt) {    if (!moodState.names) return
    moodState.current += (moodState.target - moodState.current) * Math.min(1, dt * 8)
    setMorph(moodState.names, Math.max(0, Math.min(1, moodState.current)))
    if (moodState.target <= 0 && moodState.current < 0.01) {
      setMorph(moodState.names, 0)
      moodState.names = null
      moodState.current = 0
    }
  }

  function updateMouth(dt) {
    if (!model) return
    mouthValue += (mouthTarget - mouthValue) * Math.min(1, dt * 18)
    setMorph(['あ', '口あ', '口_A', 'A', 'mouthOpen'], Math.max(0, Math.min(1, mouthValue)) * 0.85)
  }

  function updateBlink(dt) {
    if (!model || !model.morph) return
    if (blinkElapsed >= 0) {
      blinkElapsed += dt
      const value = blinkElapsed < 0.08
        ? blinkElapsed / 0.08
        : Math.max(0, 1 - (blinkElapsed - 0.08) / 0.12)
      setMorph(blinkMorphs, value)
      if (blinkElapsed > 0.22) {
        blinkElapsed = -1
        setMorph(blinkMorphs, 0)
        blinkTimer = 2 + Math.random() * 3
      }
    } else {
      blinkTimer -= dt
      if (blinkTimer <= 0) blinkElapsed = 0
    }
  }

  window.irisMmd = {
    setMood(mood, intensity) {
      if (!model) return
      const names = moodMorphs[mood] || null
      if (!names) {
        moodState.target = 0
        return
      }
      if (moodState.names && moodState.names !== names) {
        setMorph(moodState.names, 0)
        moodState.current = 0
      }
      const target = Math.max(0, Math.min(1, Number(intensity) || 0.85))
      moodState.names = names
      moodState.target = target
      if (moodState.current > 1 || !Number.isFinite(moodState.current)) moodState.current = 0
    },
    setMouthOpen(value) {
      mouthTarget = Math.max(0, Math.min(1, Number(value) || 0))
      if (!model) return
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
      return playMotion(url, { loop: true, restart: true })
    },
    playMotion(url, loop) {
      return playMotion(url, { loop: loop !== false, restart: true })
    },
    playAsset(path, loop) {
      if (!path) return Promise.resolve(false)
      const url = motionAssetBase + path.split('/').map(encodeURIComponent).join('/')
      return playMotion(url, { loop: !!loop, restart: true })
    },
    stop() {
      stopMotion(false, false)
    },
    playCamera(path, loop) {
      const value = String(path || '')
      if (value.startsWith('shot:')) return applyShot(value.slice(5))
      return playCameraVmd(value, loop !== false)
    },
    playShot(name) {
      return applyShot(name)
    },
    stopCamera() {
      stopCameraVmd(true)
      stopShot(true)
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
    if (!interactive) return
    if (cameraVmd) stopCameraVmd(true)
    if (shotTransition || shotHome) stopShot(true)
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
    if (!interactive) return
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
    camera.radius = portrait ? (modelHeight * 0.45 / 2) / Math.tan(camera.fov / 2) : fitRadius
    camera.lowerRadiusLimit = fitRadius * 0.32
    camera.upperRadiusLimit = fitRadius * 2.8
    camera.target.set(0, modelHeight * (portrait ? 0.85 : 0.52), 0)
    if (portrait) {
      camera.beta = Math.PI / 2
      camera.alpha = -Math.PI / 2
      computeHeadY()
      camera.radius = (portraitVisibleHeight() / 2) / Math.tan(camera.fov / 2)
      camera.target.set(0, portraitFocusY(), 0)
    }
  }

  async function loadMotion(url) {
    return playMotion(url, { loop: true, restart: true })
  }

  const MOTION_BLEND_SECONDS = 0.35
  const MOTION_CACHE_MAX = 6
  const motionCache = new Map()
  let initialPose = null
  let currentMotionUrl = ''
  let playToken = 0
  let transition = null
  let heldPose = null
  let onceDeadline = 0
  let pendingHold = false
  let idleTimer = 0
  let cameraVmd = null
  let cameraHome = null
  let shotTransition = null
  let shotHome = null

  /** 收集所有实际参与骨骼动画的变换节点（babylon-mmd 可能挂在 mesh.skeleton 上）。 */
  function motionNodes() {
    const skeletons = new Set()
    try { if (modelRoot && modelRoot.skeleton) skeletons.add(modelRoot.skeleton) } catch (_) { }
    try { if (model && model.skeleton) skeletons.add(model.skeleton) } catch (_) { }
    try {
      if (modelRoot && modelRoot.getChildMeshes) {
        for (const mesh of modelRoot.getChildMeshes(false)) {
          if (mesh && mesh.skeleton) skeletons.add(mesh.skeleton)
        }
      }
    } catch (_) { }
    const nodes = []
    const seen = new Set()
    for (const skeleton of skeletons) {
      for (const bone of (skeleton.bones || [])) {
        let node = null
        try { node = bone.getTransformNode ? bone.getTransformNode() : null } catch (_) { }
        if (!node || seen.has(node)) continue
        seen.add(node)
        nodes.push(node)
      }
    }
    return nodes
  }

  function capturePose() {
    const pose = new Map()
    for (const node of motionNodes()) {
      pose.set(node, {
        position: node.position.clone(),
        quaternion: node.rotationQuaternion ? node.rotationQuaternion.clone() : null,
        rotation: !node.rotationQuaternion && node.rotation ? node.rotation.clone() : null,
      })
    }
    return pose
  }

  function applyPose(pose) {
    if (!pose) return
    for (const [node, value] of pose) {
      node.position.copyFrom(value.position)
      if (value.quaternion && node.rotationQuaternion) {
        node.rotationQuaternion.copyFrom(value.quaternion)
      } else if (value.rotation && node.rotation) {
        node.rotation.copyFrom(value.rotation)
      }
    }
  }

  function rememberClip(url, clip) {
    motionCache.delete(url)
    motionCache.set(url, clip)
    while (motionCache.size > MOTION_CACHE_MAX) {
      const oldest = motionCache.keys().next().value
      if (oldest === currentMotionUrl) break
      motionCache.delete(oldest)
    }
  }

  function clipSeconds(clip) {
    try {
      if (typeof clip.getLength === 'function') {
        const length = Number(clip.getLength())
        if (Number.isFinite(length) && length > 0) return length
      }
    } catch (_) { }
    const duration = Number(clip.duration)
    return Number.isFinite(duration) && duration > 0 ? duration : 2
  }

  /** 切换动作：从当前姿势 smoothstep 过渡到新动作，避免骨骼瞬间跳变。 */
  async function playMotion(url, options = {}) {
    if (!model || !url) return false
    const loop = options.loop !== false
    if (!options.restart && currentMotionUrl === url) return true
    const token = ++playToken
    let clip = motionCache.get(url)
    if (!clip) {
      try {
        clip = await new BABYLONMMD.VmdLoader(scene).loadAsync('mmdMotion', url)
      } catch (error) {
        console.warn('mmd motion failed', url, error)
        return false
      }
      if (!clip) return false
      rememberClip(url, clip)
    }
    if (token !== playToken || !model) return false
    const from = capturePose()
    try {
      model.setRuntimeAnimation(model.createRuntimeAnimation(clip))
    } catch (error) {
      console.warn('mmd motion attach failed', url, error)
      return false
    }
    currentMotionUrl = url
    clearTimeout(idleTimer)
    heldPose = null
    pendingHold = !!options.holdLast
    transition = { from, to: null, elapsed: 0, duration: MOTION_BLEND_SECONDS }
    onceDeadline = loop ? 0 : performance.now() + Math.max(clipSeconds(clip), 0.5) * 1000
    return true
  }

  function stopMotion(freeze, silent) {
    playToken += 1
    const from = capturePose()
    currentMotionUrl = ''
    onceDeadline = 0
    heldPose = null
    try { model.setRuntimeAnimation(null) } catch (_) { }
    if (freeze) {
      heldPose = from
      transition = null
    } else {
      heldPose = initialPose
      transition = { from, to: initialPose, elapsed: 0, duration: MOTION_BLEND_SECONDS }
    }
    if (!silent) scheduleIdle(260)
  }

  function scheduleIdle(delay) {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
      if (currentMotionUrl || !model) return
      const url = idleMotionUrl || motionUrl
      if (url) playMotion(url, { loop: true })
    }, delay)
  }

  /** VMD 镜头：61 字节关键帧 = frame + distance + target xyz + rotation xyz + 24B 插值 + fov + perspective。 */
  function parseCameraVmd(buffer) {
    const view = new DataView(buffer)
    let offset = 30 + 20
    if (buffer.byteLength < offset + 4) return []
    const bones = view.getUint32(offset, true)
    offset += 4 + bones * 111
    if (offset + 4 > buffer.byteLength) return []
    const morphs = view.getUint32(offset, true)
    offset += 4 + morphs * 23
    if (offset + 4 > buffer.byteLength) return []
    const count = view.getUint32(offset, true)
    offset += 4
    const keys = []
    for (let i = 0; i < count && offset + 61 <= buffer.byteLength; i++) {
      const frame = view.getUint32(offset, true)
      const distance = view.getFloat32(offset + 4, true)
      const x = view.getFloat32(offset + 8, true)
      const y = view.getFloat32(offset + 12, true)
      const z = view.getFloat32(offset + 16, true)
      const rx = view.getFloat32(offset + 20, true)
      const ry = view.getFloat32(offset + 24, true)
      const rz = view.getFloat32(offset + 28, true)
      const fov = view.getUint32(offset + 56, true)
      offset += 61
      if (!Number.isFinite(distance) || Math.abs(distance) < 0.001) continue
      keys.push({
        frame, distance, x, y, z, rx, ry, rz,
        fov: fov > 1 && fov < 180 ? fov : 30,
      })
    }
    return keys.sort((a, b) => a.frame - b.frame)
  }

  function sampleCameraKey(keys, frame) {
    if (!keys.length) return null
    if (frame <= keys[0].frame) return keys[0]
    const last = keys[keys.length - 1]
    if (frame >= last.frame) return last
    let low = 0
    let high = keys.length - 1
    while (low + 1 < high) {
      const mid = (low + high) >> 1
      if (keys[mid].frame <= frame) low = mid
      else high = mid
    }
    const a = keys[low]
    const b = keys[high]
    const span = b.frame - a.frame
    const t = span > 0 ? (frame - a.frame) / span : 0
    const mix = (p, q) => p + (q - p) * t
    return {
      frame,
      distance: mix(a.distance, b.distance),
      x: mix(a.x, b.x),
      y: mix(a.y, b.y),
      z: mix(a.z, b.z),
      rx: mix(a.rx, b.rx),
      ry: mix(a.ry, b.ry),
      rz: mix(a.rz, b.rz),
      fov: mix(a.fov, b.fov),
    }
  }

  function applyCameraKey(key) {
    if (!camera || !key) return
    const target = new BABYLON.Vector3(key.x, key.y, key.z)
    const rotation = BABYLON.Quaternion.RotationYawPitchRoll(key.ry, key.rx, key.rz)
    const matrix = BABYLON.Matrix.Identity()
    BABYLON.Matrix.FromQuaternionToRef(rotation, matrix)
    const forward = new BABYLON.Vector3(0, 0, 1)
    BABYLON.Vector3.TransformNormalToRef(forward, matrix, forward)
    const position = target.subtract(forward.scale(key.distance))
    camera.setPosition(position)
    camera.setTarget(target)
    camera.fov = Math.max(0.1, Math.min(2.1, key.fov * Math.PI / 180))
  }

  async function playCameraVmd(path, loop) {
    if (!camera || !path) return false
    try {
      const url = motionAssetBase + path.split('/').map(encodeURIComponent).join('/')
      const response = await fetch(url)
      if (!response.ok) return false
      const buffer = await response.arrayBuffer()
      const keys = parseCameraVmd(buffer)
      if (!keys.length) return false
      stopShot(false)
      shotHome = null
      stopCameraVmd(false)
      cameraHome = {
        alpha: camera.alpha,
        beta: camera.beta,
        radius: camera.radius,
        target: camera.target.clone(),
        fov: camera.fov,
        lower: camera.lowerRadiusLimit,
        upper: camera.upperRadiusLimit,
      }
      camera.lowerRadiusLimit = 0.1
      camera.upperRadiusLimit = 10000
      cameraVmd = {
        keys,
        elapsed: 0,
        duration: Math.max(0.2, keys[keys.length - 1].frame / 30),
        loop: loop !== false,
      }
      applyCameraKey(keys[0])
      return true
    } catch (error) {
      console.warn('mmd camera failed', path, error)
      return false
    }
  }

  function stopCameraVmd(restore = true) {
    cameraVmd = null
    if (!restore || !cameraHome || !camera) return
    camera.alpha = cameraHome.alpha
    camera.beta = cameraHome.beta
    camera.radius = cameraHome.radius
    camera.target.copyFrom(cameraHome.target)
    camera.fov = cameraHome.fov
    camera.lowerRadiusLimit = cameraHome.lower
    camera.upperRadiusLimit = cameraHome.upper
    cameraHome = null
  }

  const SHOT_PROFILES = {
    ecu: { radius: 0.42, target: 0.9, fov: 30, variants: ['close'] },
    bust: { radius: 0.78, target: 0.78, fov: 30, variants: ['bust', 'yawL45', 'yawR45'] },
    half: { radius: 1.15, target: 0.58, fov: 30, variants: ['half', 'yawL45', 'yawR45'] },
    threeQ: { radius: 1.45, target: 0.52, fov: 30, variants: ['threeQ', 'yawL90', 'yawR90'] },
    full: { radius: 1.9, target: 0.5, fov: 30, variants: ['full', 'high45', 'yawL90', 'yawR90'] },
    long: { radius: 2.8, target: 0.5, fov: 30, variants: ['long', 'high45'] },
  }
  const SHOT_ALIASES = {
    close: 'ecu', ecu: 'ecu', 特写: 'ecu',
    bust: 'bust', 胸: 'bust', 半身特写: 'bust',
    half: 'half', 半身: 'half',
    threeq: 'threeQ', '3/4': 'threeQ', 七分: 'threeQ', 七分身: 'threeQ',
    full: 'full', 全身: 'full',
    long: 'long', 远景: 'long',
  }
  const SHOT_WEIGHTS = { S: 0.55, A: 0.32, B: 0.13 }

  function normalizeShotName(name) {
    const key = String(name || '').trim().toLowerCase()
    return SHOT_ALIASES[key] || SHOT_ALIASES[key.replace(/[\s_-]/g, '')] || ''
  }

  function gradeOfVariant(variant) {
    if (['close', 'bust', 'half', 'threeQ', 'full', 'long'].includes(variant)) return 'S'
    return 'A'
  }

  function pickShotVariant(profile) {
    const roll = Math.random()
    const grade = roll < SHOT_WEIGHTS.S ? 'S' : roll < SHOT_WEIGHTS.S + SHOT_WEIGHTS.A ? 'A' : 'B'
    const graded = profile.variants.filter((v) => gradeOfVariant(v) === grade)
    const pool = graded.length ? graded : profile.variants
    return pool[Math.floor(Math.random() * pool.length)] || 'half'
  }

  function shotCameraTarget(distance, variant) {
    const profile = SHOT_PROFILES[distance] || SHOT_PROFILES.half
    const homeAlpha = shotHome ? shotHome.alpha : -Math.PI / 2
    const homeBeta = shotHome ? shotHome.beta : Math.PI / 2
    let alpha = homeAlpha
    let beta = homeBeta
    if (variant === 'yawL45') alpha -= 0.5
    if (variant === 'yawR45') alpha += 0.5
    if (variant === 'yawL90') alpha -= 0.9
    if (variant === 'yawR90') alpha += 0.9
    if (variant === 'high45') beta = Math.max(0.35, beta - 0.35)
    return {
      alpha,
      beta,
      radius: Math.max(0.2, modelHeight * profile.radius),
      target: new BABYLON.Vector3(0, modelHeight * profile.target, 0),
      fov: profile.fov * Math.PI / 180,
    }
  }

  function captureCamera() {
    return {
      alpha: camera.alpha,
      beta: camera.beta,
      radius: camera.radius,
      target: camera.target.clone(),
      fov: camera.fov,
    }
  }

  /** 景别语法：特写/胸/半身/七分/全身/远景，每个景别随机取 S/A/B 机位。 */
  function applyShot(name) {
    if (!camera) return false
    const distance = normalizeShotName(name)
    if (!SHOT_PROFILES[distance]) return false
    stopCameraVmd(false)
    cameraHome = null
    if (!shotHome) {
      shotHome = {
        alpha: camera.alpha,
        beta: camera.beta,
        radius: camera.radius,
        target: camera.target.clone(),
        fov: camera.fov,
        lower: camera.lowerRadiusLimit,
        upper: camera.upperRadiusLimit,
      }
    }
    camera.lowerRadiusLimit = 0.1
    camera.upperRadiusLimit = 10000
    const variant = pickShotVariant(SHOT_PROFILES[distance])
    const target = shotCameraTarget(distance, variant)
    shotTransition = {
      from: captureCamera(),
      to: target,
      elapsed: 0,
      duration: 1.1 + Math.random() * 0.35,
    }
    return true
  }

  function updateShot(dt) {
    if (!shotTransition || !camera) return
    shotTransition.elapsed += dt
    const k = Math.min(1, shotTransition.elapsed / shotTransition.duration)
    const eased = k * k * (3 - 2 * k)
    const from = shotTransition.from
    const to = shotTransition.to
    camera.alpha = from.alpha + (to.alpha - from.alpha) * eased
    camera.beta = from.beta + (to.beta - from.beta) * eased
    camera.radius = from.radius + (to.radius - from.radius) * eased
    camera.target.set(
      from.target.x + (to.target.x - from.target.x) * eased,
      from.target.y + (to.target.y - from.target.y) * eased,
      from.target.z + (to.target.z - from.target.z) * eased,
    )
    camera.fov = from.fov + (to.fov - from.fov) * eased
    if (k >= 1) shotTransition = null
  }

  function stopShot(restore = true) {
    shotTransition = null
    if (!restore || !shotHome || !camera) return
    camera.alpha = shotHome.alpha
    camera.beta = shotHome.beta
    camera.radius = shotHome.radius
    camera.target.copyFrom(shotHome.target)
    camera.fov = shotHome.fov
    camera.lowerRadiusLimit = shotHome.lower
    camera.upperRadiusLimit = shotHome.upper
    shotHome = null
  }

  function updateCameraVmd(dt) {
    if (!cameraVmd || !camera) return
    cameraVmd.elapsed += dt
    if (cameraVmd.elapsed >= cameraVmd.duration) {
      if (cameraVmd.loop) {
        cameraVmd.elapsed %= cameraVmd.duration
      } else {
        stopCameraVmd(true)
        return
      }
    }
    applyCameraKey(sampleCameraKey(cameraVmd.keys, cameraVmd.elapsed * 30))
  }

  function updateTransition(dt) {
    if (!transition) return
    transition.elapsed += dt
    const k = Math.min(1, transition.elapsed / Math.max(transition.duration, 1e-4))
    const eased = k * k * (3 - 2 * k)
    for (const [node, from] of transition.from) {
      const to = transition.to ? transition.to.get(node) : null
      const targetX = to ? to.position.x : node.position.x
      const targetY = to ? to.position.y : node.position.y
      const targetZ = to ? to.position.z : node.position.z
      node.position.set(
        from.position.x + (targetX - from.position.x) * eased,
        from.position.y + (targetY - from.position.y) * eased,
        from.position.z + (targetZ - from.position.z) * eased,
      )
      if (from.quaternion && node.rotationQuaternion) {
        const target = to && to.quaternion ? to.quaternion : node.rotationQuaternion
        BABYLON.Quaternion.SlerpToRef(from.quaternion, target, eased, node.rotationQuaternion)
      }
    }
    if (k >= 1) transition = null
  }

  const motionTicker = scene.onAfterAnimationsObservable || scene.onAfterRenderObservable
  if (motionTicker) {
    motionTicker.add(() => {
      const dt = Math.min(0.05, engine.getDeltaTime() / 1000)
      if (onceDeadline > 0 && performance.now() >= onceDeadline) {
        onceDeadline = 0
        const freeze = pendingHold
        stopMotion(freeze, true)
        if (!freeze) scheduleIdle(180)
        return
      }
      if (transition) {
        updateTransition(dt)
      } else if (heldPose) {
        applyPose(heldPose)
      }
      updateMood(dt)
      updateMouth(dt)
      updateBlink(dt)
    })
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
      runtime.playAnimation()
      initialPose = capturePose()
      const firstMotion = idleMotionUrl || motionUrl
      if (firstMotion) loaded = await playMotion(firstMotion, { loop: true, restart: true })
      if (!loaded) loaded = await playMotion(idleUrl, { loop: true, restart: true })
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
  engine.runRenderLoop(() => {
    updateCameraVmd(engine.getDeltaTime() / 1000)
    updateShot(engine.getDeltaTime() / 1000)
    // 通话取景每帧校正：模型加载、动作与物理都会重算包围盒/相机，
    // 只在 fitCamera 里设一次会被后续流程覆盖。
    if (portrait && camera && model) {
      camera.beta = Math.PI / 2
      camera.alpha = -Math.PI / 2
      camera.target.set(0, portraitFocusY(), 0)
      camera.radius = (portraitVisibleHeight() / 2) / Math.tan(camera.fov / 2)
    }
    scene.render()
  })
})()

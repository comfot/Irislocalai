import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import { VRMAnimationLoaderPlugin, createVRMAnimationClip } from '@pixiv/three-vrm-animation'

(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const status = document.getElementById('status')
  const params = new URLSearchParams(location.hash.startsWith('#') ? location.hash.substring(1) : '')
  const config = window.__CHARACTER_CONFIG__ || {}
  // 通话页需要上半身取景：抬高相机目标并拉近，而不是整体缩放。
  const portrait = !!config.portrait
  const interactive = config.interactive !== false
  const modelUrl = config.model || (params.get('model') ? decodeURIComponent(params.get('model')) : '')
  const modelType = (config.type || params.get('type') || '').toUpperCase()
  const accent = config.accent || params.get('accent') || '#0D84FF'
  function showStatus(text) {
    if (!status) return
    status.hidden = false
    status.textContent = text
  }

  function hideStatus() {
    if (status) status.hidden = true
  }

  if (!modelUrl) {
    showStatus('没有选择角色模型')
    return
  }
  if (modelType !== 'VRM' && modelType !== 'GLB') {
    showStatus('当前格式需要额外渲染运行时')
    return
  }

  let renderer
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' })
  } catch (error) {
    showStatus('这台设备无法显示 3D 角色（WebGL 不可用）')
    return
  }

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.15
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.setClearColor(0x000000, 0)
  stage.appendChild(renderer.domElement)

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(32, 1, 0.05, 80)
  const clock = new THREE.Clock()
  const raycaster = new THREE.Raycaster()
  const pointer = new THREE.Vector2()
  const target = new THREE.Vector3(0, 0.95, 0)
  let radius = 4
  let cameraY = 1.15
  let yaw = 0
  let dragging = false
  let pointerId = null
  let lastX = 0
  let lastY = 0
  let moved = 0
  let idleSince = performance.now()
  let model = null
  let vrm = null
  let vrmVersion = '1'
  let activeMotion = null
  let mixer = null
  let motionMixer = null
  let motionAction = null
  let motionBlend = null
  let vrmaMixer = null
  let vrmaAction = null
  let vrmaLoading = false
  let lastVrmPose = null
  const MOTION_BLEND_SECONDS = 0.35
  const restRotations = new Map()
  const restPoses = new Map()
  let motionEndpoint = typeof config.motion === 'string' ? config.motion : ''
  const motionIdle = config.motionIdle !== false
  const motionPack = String(config.pack || '')
  const vrmaEnabled = config.vrmaEnabled === true
  const vrmaLoader = new GLTFLoader()
  vrmaLoader.register((parser) => new VRMAnimationLoaderPlugin(parser))
  const idleMotionName = 'idle'
  const tapMotionName = motionPack === 'hast' ? 'right-摆动手臂' : 'wave'
  const motionAxisFix = Number(config.axisFix || 0)
  let modelHeight = 1.7
  const pointerPositions = new Map()
  let diagUntil = 0
  let diagLast = 0

  scene.add(new THREE.HemisphereLight(0xffffff, 0x8a94a6, 1.35))
  const key = new THREE.DirectionalLight(0xfff4e6, 2.2)
  key.position.set(2.8, 5.2, 4.2)
  key.castShadow = true
  key.shadow.mapSize.set(1024, 1024)
  key.shadow.camera.left = -3
  key.shadow.camera.right = 3
  key.shadow.camera.top = 4
  key.shadow.camera.bottom = -1
  key.shadow.camera.near = 0.5
  key.shadow.camera.far = 14
  key.shadow.bias = -0.0004
  scene.add(key)
  const fill = new THREE.DirectionalLight(0x9ec4ff, 0.8)
  fill.position.set(-4, 3, -3)
  scene.add(fill)

  const ground = new THREE.Mesh(
    new THREE.CircleGeometry(1.6, 48),
    new THREE.MeshBasicMaterial({ color: accent, transparent: true, opacity: 0.1, depthWrite: false }),
  )
  ground.rotation.x = -Math.PI / 2
  ground.position.y = 0.002
  scene.add(ground)

  const shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(10, 10),
    new THREE.ShadowMaterial({ opacity: 0.24 }),
  )
  shadow.rotation.x = -Math.PI / 2
  shadow.position.y = 0.001
  shadow.receiveShadow = true
  scene.add(shadow)

  const group = new THREE.Group()
  scene.add(group)

  function resize() {
    const width = stage.clientWidth || window.innerWidth || 1
    const height = stage.clientHeight || window.innerHeight || 1
    if (width < 2 || height < 2) return
    stage.style.width = width + 'px'
    stage.style.height = height + 'px'
    renderer.setSize(width, height)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    const fit = (modelHeight / 2) / Math.tan((camera.fov * Math.PI / 180) / 2)
    radius = Math.max(2.8, fit / Math.max(0.58, camera.aspect) * 1.35)
    if (portrait) {
      // 可见高度约半个身高：头到胸口铺满画面，与画布宽高比无关。
      radius = (modelHeight * 0.5 / 2) / Math.tan((camera.fov * Math.PI / 180) / 2)
      applyCamera()
      return
    }
    applyCamera()
  }

  const portraitTarget = new THREE.Vector3()
  function applyCamera() {
    if (portrait) {
      portraitTarget.set(0, modelHeight * 0.8, 0)
      camera.position.set(Math.sin(yaw) * radius * 0.72, modelHeight * 0.8, radius)
      camera.lookAt(portraitTarget)
      return
    }
    camera.position.set(
      target.x + Math.sin(yaw) * radius * 0.72,
      cameraY,
      target.z + Math.cos(yaw) * radius,
    )
    camera.lookAt(target)
  }

  function frameModel(loaded) {
    loaded.updateMatrixWorld(true)
    const box = new THREE.Box3().setFromObject(loaded)
    const size = box.getSize(new THREE.Vector3())
    if (!Number.isFinite(size.y) || size.y <= 0.0001) return
    modelHeight = 1.7
    const scale = modelHeight / size.y
    loaded.scale.multiplyScalar(scale)
    loaded.updateMatrixWorld(true)
    const fitted = new THREE.Box3().setFromObject(loaded)
    const center = fitted.getCenter(new THREE.Vector3())
    loaded.position.x -= center.x
    loaded.position.z -= center.z
    loaded.position.y -= fitted.min.y
    loaded.updateMatrixWorld(true)
  }

  function relaxArmsVrm() {
    if (!vrm) return
    const direction = vrmVersion === '0' ? -1 : 1
    const left = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0, 1.08 * direction))
    const right = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, 0, -1.08 * direction))
    vrm.humanoid.setNormalizedPose({
      leftUpperArm: { rotation: left.toArray() },
      rightUpperArm: { rotation: right.toArray() },
    })
  }

  function prepareModel(object, animations, loadedVrm) {
    model = object
    vrm = loadedVrm || null
    vrmVersion = vrm && vrm.meta && String(vrm.meta.metaVersion || '') === '0' ? '0' : '1'
    activeMotion = null
    if (!model) {
      showStatus('模型没有可显示的场景')
      return
    }
    model.traverse((item) => {
      if (item.isBone) {
        restRotations.set(item, item.quaternion.clone())
        restPoses.set(item, {
          position: item.position.clone(),
          quaternion: item.quaternion.clone(),
          scale: item.scale.clone(),
        })
      }
    })
    model.traverse((item) => {
      if (item.isMesh) {
        item.castShadow = true
        item.receiveShadow = true
        item.frustumCulled = false
        const isHair = /hair/i.test(item.name || '') || /hair/i.test(item.parent?.name || '')
        if (isHair) {
          item.visible = true
          const materials = Array.isArray(item.material) ? item.material : [item.material]
          materials.forEach((material) => {
            if (!material) return
            material.side = THREE.DoubleSide
            material.needsUpdate = true
          })
        }
      }
    })
    group.add(model)
    frameModel(model)
    if (animations && animations.length > 0 && !motionEndpoint && !vrmaEnabled) {
      mixer = new THREE.AnimationMixer(model)
      animations.forEach((clip) => mixer.clipAction(clip).play())
    }
    resize()
    hideStatus()
    if (vrm) {
      vrm.humanoid.resetNormalizedPose()
      relaxArmsVrm()
    }
    if (motionEndpoint) {
      if (motionIdle && !vrmaEnabled) playMotion(idleMotionName, true)
    }
  }

  function findBone(key) {
    let found = null
    const raw = String(key || '')
    const wanted = raw.toLowerCase()
    if (!wanted) return null
    let alias = wanted
    if (wanted.startsWith('left')) alias = 'l_' + wanted.slice(4)
    else if (wanted.startsWith('right')) alias = 'r_' + wanted.slice(5)
    else if (['hips', 'spine', 'chest', 'neck', 'head'].includes(wanted)) alias = 'c_' + wanted
    model.traverse((object) => {
      if (found || !object.isBone) return
      const name = object.name.toLowerCase()
      if (name.includes(alias) || name.includes(wanted) || name.replace(/_/g, '').includes(wanted)) {
        found = object
      }
    })
    return found
  }

  const sampleFrom = new THREE.Quaternion()
  const sampleTo = new THREE.Quaternion()
  const sampleResult = new THREE.Quaternion()

  class OneEuroFilter {
    constructor(minCutoff, beta, dCutoff) {
      this.minCutoff = minCutoff
      this.beta = beta
      this.dCutoff = dCutoff
      this.prev = null
      this.prevDeriv = 0
      this.prevTs = null
    }

    alpha(cutoff, dt) {
      const tau = 1 / (2 * Math.PI * cutoff)
      return 1 / (1 + tau / dt)
    }

    filter(value, ts) {
      if (this.prev === null || this.prevTs === null) {
        this.prev = value
        this.prevTs = ts
        return value
      }
      const dt = (ts - this.prevTs) / 1000
      if (dt <= 0 || dt > 1) {
        this.prev = value
        this.prevDeriv = 0
        this.prevTs = ts
        return value
      }
      const rawDeriv = (value - this.prev) / dt
      const aD = this.alpha(this.dCutoff, dt)
      const filteredDeriv = aD * rawDeriv + (1 - aD) * this.prevDeriv
      const cutoff = this.minCutoff + this.beta * Math.abs(filteredDeriv)
      const a = this.alpha(cutoff, dt)
      const filtered = a * value + (1 - a) * this.prev
      this.prev = filtered
      this.prevDeriv = filteredDeriv
      this.prevTs = ts
      return filtered
    }
  }

  class QuaternionOneEuroFilter {
    constructor(minCutoff, beta, dCutoff) {
      this.minCutoff = minCutoff
      this.beta = beta
      this.prev = new THREE.Quaternion()
      this.hasPrev = false
      this.prevTs = 0
      this.prevSpeed = 0
      this.speedFilter = new OneEuroFilter(dCutoff, 0, dCutoff)
      this.maxSpeed = 30
      this.maxAccel = 450
    }

    filter(q, ts, out) {
      const aligned = q.clone()
      if (this.hasPrev && this.prev.dot(aligned) < 0) {
        aligned.x *= -1; aligned.y *= -1; aligned.z *= -1; aligned.w *= -1
      }
      if (!this.hasPrev) {
        this.prev.copy(aligned)
        this.hasPrev = true
        this.prevTs = ts
        return out.copy(aligned)
      }
      const dt = (ts - this.prevTs) / 1000
      if (dt <= 0 || dt > 1) {
        this.prev.copy(aligned)
        this.prevTs = ts
        this.prevSpeed = 0
        return out.copy(aligned)
      }
      let speed = this.prev.angleTo(aligned) / dt
      const maxStep = this.prevSpeed + this.maxAccel * dt
      speed = Math.min(speed, Math.min(this.maxSpeed, maxStep))
      speed = this.speedFilter.filter(speed, ts)
      this.prevSpeed = speed
      const cutoff = this.minCutoff + this.beta * speed
      const tau = 1 / (2 * Math.PI * cutoff)
      const alpha = 1 / (1 + tau / dt)
      out.copy(this.prev).slerp(aligned, alpha)
      this.prev.copy(out)
      this.prevTs = ts
      return out
    }
  }

  const motionFilters = new Map()
  const filterQuaternion = new THREE.Quaternion()

  function smoothQuaternion(bone, values, ts) {
    let filter = motionFilters.get(bone)
    if (!filter) {
      filter = new QuaternionOneEuroFilter(1.6, 0.05, 1.0)
      motionFilters.set(bone, filter)
    }
    sampleFrom.fromArray(values)
    filter.filter(sampleFrom, ts, filterQuaternion)
    const result = filterQuaternion.toArray()
    if (!result.every((value) => Number.isFinite(value))) return values
    return result
  }

  function sampleTrack(track, time) {
    const times = track.times
    const count = times.length
    if (count === 0) return [0, 0, 0, 1]
    if (count === 1 || time <= times[0]) {
      sampleResult.fromArray(track.quats, 0)
      return sampleResult.toArray()
    }
    if (time >= times[count - 1]) {
      sampleResult.fromArray(track.quats, (count - 1) * 4)
      return sampleResult.toArray()
    }
    let low = 0
    let high = count - 1
    while (low + 1 < high) {
      const mid = (low + high) >> 1
      if (times[mid] <= time) low = mid
      else high = mid
    }
    const span = times[high] - times[low]
    const alpha = span > 1e-6 ? (time - times[low]) / span : 0
    sampleFrom.fromArray(track.quats, low * 4)
    sampleTo.fromArray(track.quats, high * 4)
    sampleResult.slerpQuaternions(sampleFrom, sampleTo, alpha)
    return sampleResult.toArray()
  }

  function advanceMotion(dt) {
    if (!vrm || !activeMotion) return
    const motion = activeMotion
    motion.time += dt
    if (!motion.loop && motion.time >= motion.duration) {
      activeMotion = null
      if (motionIdle) setTimeout(() => { playMotion(idleMotionName, true) }, 120)
      return
    }
    const time = motion.loop ? motion.time % motion.duration : motion.time
    if (motionBlend) motionBlend.elapsed += dt
    const k = motionBlend ? Math.min(1, motionBlend.elapsed / motionBlend.duration) : 1
    const eased = k * k * (3 - 2 * k)
    const pose = {}
    const from = new THREE.Quaternion()
    const target = new THREE.Quaternion()
    const blended = new THREE.Quaternion()
    const now = performance.now()
    for (const track of motion.tracks) {
      const torso = ['hips', 'spine', 'chest', 'neck', 'head'].includes(track.bone)
      const sampled = (!torso && motionPack !== 'hast')
        ? smoothQuaternion(track.bone, sampleTrack(track, time), now)
        : sampleTrack(track, time)
      if (motionBlend && motionBlend.from[track.bone]) {
        from.fromArray(motionBlend.from[track.bone])
        target.fromArray(sampled)
        blended.copy(from).slerp(target, eased)
        pose[track.bone] = { rotation: blended.toArray() }
      } else {
        pose[track.bone] = { rotation: sampled }
      }
    }
    vrm.humanoid.setNormalizedPose(pose)
    lastVrmPose = pose
    if (motionBlend && k >= 1) motionBlend = null
  }

  async function playMotion(name, loop) {
    if (!model || !motionEndpoint) return false
    stopVrma()
    try {
      if (motionPack === 'hast') {
        const alias = String(name || '').toLowerCase()
        if (alias === 'wave' || alias === '挥手' || alias === '摆手') name = 'right-摆动手臂'
        else if (alias === 'dance' || alias === '跳舞') name = 'body-自然活动'
        else if (alias === 'nod' || alias === '点头') name = 'body-自然活动'
      }
        const base = motionEndpoint.replace(/\/$/, '')
      const armOnly = motionPack === 'hast' && /^(left|right)-/.test(name)
      const response = await fetch(`${base}/motion?name=${encodeURIComponent(name)}`)
      if (!response.ok) return false
      const data = await response.json()
      if (vrm) {
        const tracks = []
        for (const track of data.tracks || []) {
          const bone = String(track.bone || '')
          if (!bone || !vrm.humanoid.getNormalizedBoneNode(bone)) continue
          // 单臂技能只跳过躯干/头部，另一只手臂仍使用动作数据里的自然姿态。
          if (armOnly && ['hips', 'spine', 'chest', 'neck', 'head'].includes(bone)) continue
          const times = Array.isArray(track.times) ? track.times : []
          const source = Array.isArray(track.quaternions) ? track.quaternions : []
          if (times.length === 0 || source.length < 4) continue
          const quats = new Float32Array(source.length)
          for (let i = 0; i < source.length; i += 4) {
            const flip = vrmVersion === '0'
            let x = flip ? -source[i] : source[i]
            let y = source[i + 1]
            let z = flip ? -source[i + 2] : source[i + 2]
            let w = source[i + 3]
            const length = Math.hypot(x, y, z, w)
            if (!Number.isFinite(length) || length < 1e-5) {
              x = 0; y = 0; z = 0; w = 1
            } else {
              x /= length; y /= length; z /= length; w /= length
            }
            quats[i] = x
            quats[i + 1] = y
            quats[i + 2] = z
            quats[i + 3] = w
          }
          tracks.push({ bone, times, quats })
        }
        if (tracks.length === 0) return false
        beginDiagnostics('switch ' + name + ' tracks=' + tracks.length + ' armOnly=' + armOnly)
        motionBlend = lastVrmPose ? {
          from: lastVrmPose,
          elapsed: 0,
          duration: MOTION_BLEND_SECONDS,
        } : null
        vrm.humanoid.resetNormalizedPose()
        relaxArmsVrm()
        refreshSkinning()
        motionFilters.clear()
        activeMotion = {
          name: data.name || name,
          tracks,
          duration: Math.max(0.1, Number(data.duration) || 2),
          loop: !!loop,
          time: 0,
        }
        return true
      }
      const tracks = []
      for (const track of data.tracks || []) {
        const bone = findBone(track.bone)
        if (!bone) continue
        const rest = restRotations.get(bone)
        const source = track.quaternions
        const composed = new Float32Array(source.length)
        const local = new THREE.Quaternion()
        const out = new THREE.Quaternion()
        for (let i = 0; i < source.length; i += 4) {
          let x = source[i]
          let y = source[i + 1]
          let z = source[i + 2]
          const w = source[i + 3]
          if (motionAxisFix === 1 || motionAxisFix === 3) z = -z
          if (motionAxisFix === 2 || motionAxisFix === 3) x = -x
          local.set(x, y, z, w)
          if (rest) out.copy(rest).multiply(local)
          else out.copy(local)
          composed[i] = out.x
          composed[i + 1] = out.y
          composed[i + 2] = out.z
          composed[i + 3] = out.w
        }
        tracks.push(new THREE.QuaternionKeyframeTrack(
          `${bone.name}.quaternion`,
          track.times,
          composed,
        ))
      }
      if (tracks.length === 0) return false
      const clip = new THREE.AnimationClip(data.name || name, data.duration || 2, tracks)
      if (!motionMixer) motionMixer = new THREE.AnimationMixer(model)
      const previous = motionAction
      motionAction = motionMixer.clipAction(clip)
      motionAction.setLoop(loop ? THREE.LoopRepeat : THREE.LoopOnce, loop ? Infinity : 1)
      motionAction.clampWhenFinished = !loop
      motionAction.reset().play()
      if (previous && previous !== motionAction && previous.isRunning()) {
        motionAction.crossFadeFrom(previous, MOTION_BLEND_SECONDS, true)
      }
      if (!loop && motionIdle) {
        const clipDuration = data.duration || 2
        setTimeout(() => { playMotion(idleMotionName, true) }, clipDuration * 1000 + 120)
      }
      return true
    } catch (error) {
      console.warn('motion error', error)
      return false
    }
  }

  function stopVrma() {
    if (vrmaAction) {
      vrmaAction.stop()
      vrmaAction = null
    }
    if (vrmaMixer) vrmaMixer.stopAllAction()
  }

  async function playVrma(url, loop) {
    if (!vrm || !url || vrmaLoading) return false
    vrmaLoading = true
    try {
      const gltf = await vrmaLoader.loadAsync(url)
      const animations = gltf.userData?.vrmAnimations || []
      if (animations.length === 0) {
        console.warn('VRMA: no animation data', url)
        return false
      }
      const clip = createVRMAnimationClip(animations[0], vrm)
      if (!vrmaMixer) vrmaMixer = new THREE.AnimationMixer(vrm.scene)
      const previous = vrmaAction
      vrmaAction = vrmaMixer.clipAction(clip)
      vrmaAction.setLoop(loop ? THREE.LoopRepeat : THREE.LoopOnce, loop ? Infinity : 1)
      vrmaAction.clampWhenFinished = !loop
      vrmaAction.reset().play()
      if (previous && previous !== vrmaAction && previous.isRunning()) {
        vrmaAction.crossFadeFrom(previous, MOTION_BLEND_SECONDS, true)
      }
      // 进入 VRMA 时暂停 HAST 手动姿态，避免两套轨道同时写 humanoid。
      activeMotion = null
      lastVrmPose = null
      motionBlend = null
      if (motionAction) motionAction.stop()
      if (motionMixer) motionMixer.stopAllAction()
      hideStatus()
      return true
    } catch (error) {
      console.warn('VRMA error', error)
      return false
    } finally {
      vrmaLoading = false
    }
  }

  window.irisMotion = {
    play(name) { return playMotion(name, false) },
    idle() { return playMotion(idleMotionName, true) },
    playVrma(url, loop) { return playVrma(url, loop === true) },
    stopVrma() { stopVrma() },
    setEndpoint(value) { motionEndpoint = String(value || '') },
    setExpression(name) {
      const manager = vrm && vrm.expressionManager
      if (!manager) return false
      const key = String(name || '').toLowerCase()
      const aliases = {
        '开心': 'happy', '高兴': 'happy', '难过': 'sad', '伤心': 'sad',
        '生气': 'angry', '惊讶': 'surprised', '放松': 'relaxed', '思考': 'think',
      }
      const expression = manager.getExpression(aliases[key] || key)
      if (!expression) return false
      for (const item of manager.expressions) item.weight = 0
      expression.weight = 1
      return true
    },
  }

  function loadModel() {
    showStatus('正在加载角色模型…')
    const onProgress = (event) => {
      if (event && event.total > 0) {
        showStatus(`正在加载角色模型… ${Math.min(100, Math.round(event.loaded / event.total * 100))}%`)
      }
    }
    const onError = (error) => {
      console.error('character model error', error)
      showStatus('角色模型加载失败：' + (error && error.message ? error.message : '文件无法读取'))
    }
    const loader = new GLTFLoader()
    loader.register((parser) => new VRMLoaderPlugin(parser))
    loader.load(
      modelUrl,
      (gltf) => {
        const scene = gltf.scene || gltf.scenes?.[0]
        const loadedVrm = gltf.userData?.vrm || null
        if (modelType === 'VRM' && !loadedVrm) {
          showStatus('该文件不是有效的 VRM 模型')
          return
        }
        if (loadedVrm) {
          VRMUtils.rotateVRM0(loadedVrm)
          // 保留模型自带的多个 skin；强制合并骨骼会让部分多材质/多 skin 的头发网格丢失。
          if (loadedVrm.lookAt) loadedVrm.lookAt.autoUpdate = false
        }
        prepareModel(scene, gltf.animations || [], loadedVrm)
      },
      onProgress,
      onError,
    )
  }

  function onPointerDown(event) {
    if (!interactive) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    pointerPositions.set(event.pointerId, { x: event.clientX, y: event.clientY })
    try { stage.setPointerCapture(event.pointerId) } catch (_) { }
    if (pointerPositions.size === 1) {
      pointerId = event.pointerId
      dragging = true
      moved = 0
      lastX = event.clientX
      lastY = event.clientY
    } else {
      moved = 999
      lastX = event.clientX
      lastY = event.clientY
    }
  }

  function pairDistance() {
    const values = Array.from(pointerPositions.values())
    if (values.length < 2) return 0
    return Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y)
  }

  function onPointerMove(event) {
    if (!interactive) return
    const current = pointerPositions.get(event.pointerId)
    if (!current) return
    current.x = event.clientX
    current.y = event.clientY
    if (pointerPositions.size >= 2) {
      const distance = pairDistance()
      if (distance > 0) {
        const previous = current.pinch || distance
        radius = Math.max(1.8, Math.min(9, radius * (previous / distance)))
        current.pinch = distance
        idleSince = performance.now()
        applyCamera()
      }
      return
    }
    if (event.pointerId !== pointerId || !dragging) return
    const dx = current.x - lastX
    const dy = current.y - lastY
    moved += Math.abs(dx) + Math.abs(dy)
    lastX = current.x
    lastY = current.y
    yaw -= dx * 0.008
    cameraY = Math.max(0.35, Math.min(2.6, cameraY + dy * 0.006))
    idleSince = performance.now()
    applyCamera()
  }

  function onPointerUp(event) {
    pointerPositions.delete(event.pointerId)
    try { stage.releasePointerCapture(event.pointerId) } catch (_) { }
    if (event.pointerId === pointerId) {
      if (interactive && moved < 6 && motionEndpoint) playMotion(tapMotionName, false)
      dragging = false
      pointerId = pointerPositions.keys().next().value ?? null
    }
  }

  stage.addEventListener('pointerdown', onPointerDown)
  stage.addEventListener('pointermove', onPointerMove)
  stage.addEventListener('pointerup', onPointerUp)
  stage.addEventListener('pointercancel', onPointerUp)
  stage.addEventListener('wheel', (event) => {
    if (!interactive) return
    radius = Math.max(1.8, Math.min(9, radius + event.deltaY * 0.004))
    idleSince = performance.now()
    applyCamera()
  }, { passive: true })
  window.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(resize).observe(stage)
  }
  requestAnimationFrame(resize)
  setTimeout(resize, 250)
  applyCamera()
  loadModel()

  function beginDiagnostics(reason) {
    diagUntil = performance.now() + 2200
    diagLast = 0
    console.warn('[iris-diag] begin ' + reason)
  }

  function diagnosticsTick() {
    const now = performance.now()
    if (now > diagUntil || now - diagLast < 120) return
    diagLast = now
    if (!model) return
    let body = null
    model.traverse((item) => {
      if (!body && item.isSkinnedMesh && /body/i.test(item.name || '')) body = item
    })
    if (!body) {
      console.warn('[iris-diag] no body mesh')
      return
    }
    const bones = body.skeleton ? body.skeleton.bones : []
    const badNames = []
    for (const bone of bones) {
      if (!Number.isFinite(bone.quaternion.x) || !Number.isFinite(bone.quaternion.w) ||
        !Number.isFinite(bone.position.x)) badNames.push(bone.name || '?')
    }
    const material = Array.isArray(body.material) ? body.material[0] : body.material
    console.warn('[iris-diag] ' + JSON.stringify({
      t: Math.round(now),
      motion: activeMotion ? activeMotion.name : '',
      tracks: activeMotion ? activeMotion.tracks.length : 0,
      calls: renderer.info.render.calls,
      tris: renderer.info.render.triangles,
      cam: [Number(camera.position.distanceTo(target).toFixed(2)), Number(target.y.toFixed(2))],
      body: {
        v: body.visible ? 1 : 0,
        mv: material && material.visible === false ? 0 : 1,
        side: material ? material.side : -1,
        bad: badNames.length,
        names: badNames.slice(0, 24),
        c: body.geometry && body.geometry.attributes && body.geometry.attributes.position
          ? body.geometry.attributes.position.count : 0,
      },
    }))
  }

  function sanitizeBones() {
    if (!model) return
    model.traverse((item) => {
      if (!item.isBone) return
      const rest = restPoses.get(item)
      if (!rest) return
      const q = item.quaternion
      const p = item.position
      const s = item.scale
      if (!Number.isFinite(q.x) || !Number.isFinite(q.y) || !Number.isFinite(q.z) || !Number.isFinite(q.w)) {
        q.copy(rest.quaternion)
      }
      if (!Number.isFinite(p.x) || !Number.isFinite(p.y) || !Number.isFinite(p.z)) {
        p.copy(rest.position)
      }
      if (!Number.isFinite(s.x) || !Number.isFinite(s.y) || !Number.isFinite(s.z)) {
        s.copy(rest.scale)
      }
    })
  }

  function refreshSkinning() {
    if (!model) return
    sanitizeBones()
    model.updateMatrixWorld(true)
    model.traverse((item) => {
      if (item.isSkinnedMesh && item.skeleton) item.skeleton.update()
    })
  }

  function animate(now = performance.now()) {
    requestAnimationFrame(animate)
    const dt = Math.min(0.05, clock.getDelta())
    if (mixer) mixer.update(dt)
    if (motionMixer) motionMixer.update(dt)
    if (vrmaMixer) vrmaMixer.update(dt)
    if (vrm) {
      advanceMotion(dt)
      vrm.update(dt)
      refreshSkinning()
    }
    if (model && !dragging && !activeMotion && !(motionAction && motionAction.isRunning()) && !(vrmaAction && vrmaAction.isRunning()) && now - idleSince > 6000) {
      group.rotation.y += dt * 0.045
    }
    if (model) {
      group.position.y = Math.sin(now * 0.0016) * 0.008
    }
    renderer.render(scene, camera)
    try { diagnosticsTick() } catch (error) { console.warn('[iris-diag] failed ' + (error && error.message ? error.message : error)) }
  }
  animate()
})()

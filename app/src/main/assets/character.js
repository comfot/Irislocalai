(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const status = document.getElementById('status')
  const params = new URLSearchParams(location.hash.startsWith('#') ? location.hash.substring(1) : '')
  const config = window.__CHARACTER_CONFIG__ || {}
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
  let mixer = null
  let modelHeight = 1.7
  const pointerPositions = new Map()

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
    applyCamera()
  }

  function applyCamera() {
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

  function humanoidBone(gltf, name) {
    const json = gltf.parser && gltf.parser.json
    if (!json || !json.nodes) return null
    const vrm1 = json.extensions && json.extensions.VRMC_vrm
    const vrm0 = json.extensions && json.extensions.VRM
    const humanBones = (vrm1 && vrm1.humanoid && vrm1.humanoid.humanBones) ||
      (vrm0 && vrm0.humanoid && vrm0.humanoid.humanBones)
    let index = null
    if (Array.isArray(humanBones)) {
      const entry = humanBones.find((item) => item.bone === name)
      index = entry ? entry.node : null
    } else if (humanBones) {
      const entry = humanBones[name]
      index = typeof entry === 'number' ? entry : entry && entry.node
    }
    if (index === null || index === undefined) return null
    const nodeName = json.nodes[index] && json.nodes[index].name
    return nodeName ? gltf.scene.getObjectByName(nodeName) : null
  }

  function relaxArms(gltf) {
    const left = humanoidBone(gltf, 'leftUpperArm')
    const right = humanoidBone(gltf, 'rightUpperArm')
    if (left) left.rotation.z += 1.08
    if (right) right.rotation.z -= 1.08
  }

  function loadModel() {
    showStatus('正在加载角色模型…')
    const loader = new THREE.GLTFLoader()
    loader.load(
      modelUrl,
      (gltf) => {
        model = gltf.scene || gltf.scenes?.[0]
        if (!model) {
          showStatus('模型没有可显示的场景')
          return
        }
        model.traverse((item) => {
          if (item.isMesh) {
            item.castShadow = true
            item.receiveShadow = true
            item.frustumCulled = false
            const materials = Array.isArray(item.material) ? item.material : [item.material]
            materials.forEach((material) => {
              if (!material) return
              material.side = THREE.DoubleSide
              material.needsUpdate = true
            })
          }
        })
        if (modelType === 'VRM') {
          model.rotation.y = Math.PI
          relaxArms(gltf)
        }
        group.add(model)
        frameModel(model)
        if (gltf.animations && gltf.animations.length > 0) {
          mixer = new THREE.AnimationMixer(model)
          gltf.animations.forEach((clip) => mixer.clipAction(clip).play())
        }
        resize()
        hideStatus()
      },
      (event) => {
        if (event && event.total > 0) {
          showStatus(`正在加载角色模型… ${Math.min(100, Math.round(event.loaded / event.total * 100))}%`)
        }
      },
      (error) => {
        console.error('character model error', error)
        showStatus('角色模型加载失败：' + (error && error.message ? error.message : '文件无法读取'))
      },
    )
  }

  function onPointerDown(event) {
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
      dragging = false
      pointerId = pointerPositions.keys().next().value ?? null
    }
  }

  stage.addEventListener('pointerdown', onPointerDown)
  stage.addEventListener('pointermove', onPointerMove)
  stage.addEventListener('pointerup', onPointerUp)
  stage.addEventListener('pointercancel', onPointerUp)
  stage.addEventListener('wheel', (event) => {
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

  function animate(now = performance.now()) {
    requestAnimationFrame(animate)
    const dt = Math.min(0.05, clock.getDelta())
    if (mixer) mixer.update(dt)
    if (model && !dragging && now - idleSince > 6000) {
      group.rotation.y += dt * 0.045
    }
    if (model) {
      group.position.y = Math.sin(now * 0.0016) * 0.008
    }
    renderer.render(scene, camera)
  }
  animate()
})()

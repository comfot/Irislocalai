/* 综合空间的离线 3D 场景：园区与五个场所，使用本地 three.min.js 渲染。 */
(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const overlay = document.getElementById('overlay')
  const fallback = document.getElementById('fallback')
  const boot = document.getElementById('boot')
  let firstFrame = true

  const PLACES = ['studio', 'home', 'campus', 'library', 'site', 'duty', 'desk']
  const FRAME_MS = 1000 / 30
  const FRAME_MS_ACTIVE = 1000 / 60

  let renderer
  let scene
  let camera
  let root
  let clock
  let raycaster
  let pointer
  let cameraGoal
  let cameraView
  let currentPlace = null
  let paused = false
  let dragging = false
  let pointerId = null
  let lastX = 0
  let lastY = 0
  let moved = 0
  let pinchDistance = 0
  const pointerPositions = new Map()
  let animTime = 0
  let lastFrameAt = 0
  let idleTurns = 0
  let lastStatsSignature = ''
  let labels = []
  let labelNodes = new Map()
  let lastWidth = 0
  let lastHeight = 0
  let animateFns = []
  let pulseMats = []
  let randomSeed = 7
  // 人物行走范围使用当前场所的局部坐标，在 addPerson 时固化到每个人物。
  let walkArea = { minX: -4, maxX: 4, minZ: -3, maxZ: 3 }
  const PERSON_RADIUS = 0.26
  let obstacles = []

  function addObstacle(minX, maxX, minZ, maxZ) {
    obstacles.push({ minX, maxX, minZ, maxZ })
  }

  function addObstacleAt(x, z, width, depth) {
    addObstacle(x - width / 2, x + width / 2, z - depth / 2, z + depth / 2)
  }

  function isObstacleAt(x, z, radius = PERSON_RADIUS) {
    if (x - radius < walkArea.minX || x + radius > walkArea.maxX) return true
    if (z - radius < walkArea.minZ || z + radius > walkArea.maxZ) return true
    for (const box of obstacles) {
      if (x + radius > box.minX && x - radius < box.maxX && z + radius > box.minZ && z - radius < box.maxZ) {
        return true
      }
    }
    return false
  }

  function pickWalkTarget(walk) {
    const radius = walk.radius ?? PERSON_RADIUS
    for (let attempt = 0; attempt < 48; attempt++) {
      const x = walk.minX + radius + rand() * Math.max(0.1, walk.maxX - walk.minX - radius * 2)
      const z = walk.minZ + radius + rand() * Math.max(0.1, walk.maxZ - walk.minZ - radius * 2)
      if (!isObstacleAt(x, z, radius + 0.04)) {
        walk.targetX = x
        walk.targetZ = z
        return true
      }
    }
    return false
  }

  function findFreeSpot(x, z, radius = PERSON_RADIUS) {
    const clampX = Math.max(walkArea.minX + radius, Math.min(walkArea.maxX - radius, x))
    const clampZ = Math.max(walkArea.minZ + radius, Math.min(walkArea.maxZ - radius, z))
    if (!isObstacleAt(clampX, clampZ, radius + 0.04)) return { x: clampX, z: clampZ }
    for (let ring = 0.3; ring <= 2.4; ring += 0.3) {
      for (let i = 0; i < 12; i++) {
        const angle = (i / 12) * Math.PI * 2
        const nx = clampX + Math.cos(angle) * ring
        const nz = clampZ + Math.sin(angle) * ring
        if (!isObstacleAt(nx, nz, radius + 0.04)) return { x: nx, z: nz }
      }
    }
    return { x: clampX, z: clampZ }
  }

  let state = {
    place: 'studio',
    accent: '#0D84FF',
    dark: false,
    stats: {},
    avatars: [],
  }

  const frames = {
    studio: { x: 0, y: 0.9, z: 0, radius: 21, polar: 0.94, azimuth: 0.6 },
    home: { x: 0, y: 1.0, z: 0, radius: 18, polar: 0.95, azimuth: 0.55 },
    campus: { x: 0, y: 0.6, z: 0, radius: 50, polar: 0.93, azimuth: 0.62 },
    library: { x: 0, y: 0.9, z: 0, radius: 23, polar: 0.96, azimuth: 0.58 },
    site: { x: 0, y: 0.6, z: 0, radius: 30, polar: 0.94, azimuth: 0.6 },
    duty: { x: 0, y: 0.9, z: 0, radius: 22, polar: 0.96, azimuth: 0.58 },
    desk: { x: 0, y: 0.8, z: 0, radius: 22, polar: 0.94, azimuth: 0.55 },
  }

  function rand() {
    randomSeed = (randomSeed * 16807) % 2147483647
    return (randomSeed - 1) / 2147483646
  }

  function accentColor() {
    return new THREE.Color(state.accent || '#0D84FF')
  }

  function palette() {
    const dark = state.dark
    return dark
      ? {
          bgTop: '#101a2c',
          bgBottom: '#070b12',
          floor: '#20293a',
          floorDark: '#141b28',
          slab: '#0f1622',
          wall: '#2a3549',
          wallLow: '#1d2736',
          wood: '#4a3729',
          woodLight: '#6b513a',
          deskTop: '#33415a',
          metal: '#8593a6',
          darkMetal: '#3d4a5c',
          fabric: '#36465f',
          fabricLight: '#4a5e7c',
          paper: '#d8dde6',
          glass: '#4f7f9c',
          leaf: '#3f7a5b',
          leafLight: '#5aa176',
          trunk: '#5a4433',
          stone: '#8d98a6',
          lawn: '#284b3c',
          lawnLight: '#32604a',
          dirt: '#5a4735',
          path: '#5d6674',
          skin: '#e8c3a5',
          hair: '#2b2233',
          highlight: '#8fd4ff',
        }
      : {
          bgTop: '#eef5f0',
          bgBottom: '#cfe3d9',
          floor: '#d8bd94',
          floorDark: '#b99a70',
          slab: '#c8ac82',
          wall: '#f6f1e8',
          wallLow: '#e8dfd0',
          wood: '#c49a68',
          woodLight: '#dcb888',
          deskTop: '#efe6d8',
          metal: '#aeb7c2',
          darkMetal: '#7b8491',
          fabric: '#c4d3dc',
          fabricLight: '#dce7ec',
          paper: '#fbfaf6',
          glass: '#a9d6e0',
          leaf: '#79b56b',
          leafLight: '#9ed08c',
          trunk: '#9a7351',
          stone: '#ded6c9',
          lawn: '#a9d39a',
          lawnLight: '#bde2ae',
          dirt: '#d9c09d',
          path: '#e6dfd3',
          skin: '#f4d4b8',
          hair: '#4a3b35',
          highlight: '#ffffff',
        }
  }

  function surface(color, options = {}) {
    const material = new THREE.MeshStandardMaterial({
      color,
      roughness: options.roughness ?? 0.75,
      metalness: options.metalness ?? 0.08,
      emissive: options.emissive ?? 0x000000,
      emissiveIntensity: options.emissiveIntensity ?? 0.6,
      transparent: options.opacity !== undefined,
      opacity: options.opacity ?? 1,
      side: options.doubleSide ? THREE.DoubleSide : THREE.FrontSide,
    })
    if (options.glow) {
      material.emissive = new THREE.Color(color)
      material.emissiveIntensity = options.emissiveIntensity ?? 0.35
    }
    return material
  }

  function put(group, geometry, color, x, y, z, options = {}) {
    const mesh = new THREE.Mesh(geometry, color instanceof THREE.Material ? color : surface(color, options))
    mesh.position.set(x, y, z)
    if (options.rotation) mesh.rotation.set(options.rotation[0], options.rotation[1], options.rotation[2])
    mesh.castShadow = options.cast !== false
    mesh.receiveShadow = options.receive !== false
    if (options.pick) mesh.userData.pick = options.pick
    if (options.pulse) pulseMats.push(mesh.material)
    group.add(mesh)
    return mesh
  }

  function box(group, width, height, depth, color, x, y, z, options = {}) {
    return put(group, new THREE.BoxGeometry(width, height, depth), color, x, y, z, options)
  }

  function roundBox(group, width, height, depth, radius, color, x, y, z, options = {}) {
    const halfW = width / 2
    const halfH = height / 2
    const r = Math.min(radius, halfW, halfH, depth / 3)
    const shape = new THREE.Shape()
    shape.moveTo(-halfW + r, -halfH)
    shape.lineTo(halfW - r, -halfH)
    shape.quadraticCurveTo(halfW, -halfH, halfW, -halfH + r)
    shape.lineTo(halfW, halfH - r)
    shape.quadraticCurveTo(halfW, halfH, halfW - r, halfH)
    shape.lineTo(-halfW + r, halfH)
    shape.quadraticCurveTo(-halfW, halfH, -halfW, halfH - r)
    shape.lineTo(-halfW, -halfH + r)
    shape.quadraticCurveTo(-halfW, -halfH, -halfW + r, -halfH)
    const geometry = new THREE.ExtrudeGeometry(shape, {
      depth: Math.max(0.02, depth - r * 2),
      bevelEnabled: true,
      bevelSize: r * 0.8,
      bevelThickness: r * 0.8,
      bevelSegments: 2,
      curveSegments: 3,
    })
    geometry.center()
    return put(group, geometry, color, x, y, z, options)
  }

  function cylinder(group, radiusTop, radiusBottom, height, segments, color, x, y, z, options = {}) {
    return put(
      group,
      new THREE.CylinderGeometry(radiusTop, radiusBottom, height, segments),
      color,
      x,
      y,
      z,
      options,
    )
  }

  function sphere(group, radius, color, x, y, z, options = {}) {
    return put(
      group,
      new THREE.SphereGeometry(radius, options.detail ?? 14, options.detail ?? 10),
      color,
      x,
      y,
      z,
      options,
    )
  }

  function cone(group, radius, height, color, x, y, z, options = {}) {
    return put(group, new THREE.ConeGeometry(radius, height, options.segments ?? 7), color, x, y, z, options)
  }

  function plane(group, width, depth, color, x, y, z, options = {}) {
    const mesh = put(group, new THREE.PlaneGeometry(width, depth), color, x, y, z, options)
    mesh.rotation.x = -Math.PI / 2
    mesh.castShadow = false
    return mesh
  }

  function line(group, points, color, options = {}) {
    const geometry = new THREE.BufferGeometry().setFromPoints(points)
    const material = new THREE.LineBasicMaterial({ color, transparent: options.opacity !== undefined, opacity: options.opacity ?? 1 })
    const item = new THREE.Line(geometry, material)
    group.add(item)
    return item
  }

  function clearScene() {
    labels = []
    labelNodes.forEach((node) => node.remove())
    labelNodes.clear()
    animateFns = []
    pulseMats = []
    if (!root) return
    root.traverse((item) => {
      if (item.geometry) item.geometry.dispose()
      if (item.material) {
        if (Array.isArray(item.material)) item.material.forEach((m) => m.dispose())
        else item.material.dispose()
      }
    })
    scene.remove(root)
    root = null
  }

  const ysmAssets = new Map()
  const YSM_TARGET_HEIGHT = 1.55
  const DEG = Math.PI / 180

  async function fetchYsmJson(url) {
    const response = await fetch(url)
    if (!response.ok) throw new Error('请求失败 ' + response.status)
    return response.json()
  }

  function resolveYsmAsset(base, ref) {
    if (!ref) return ''
    const value = String(ref).replace(/\\/g, '/')
    if (/^https?:\/\//i.test(value)) return value
    return base + value.replace(/^\.?\//, '')
  }

  function pickYsmTexture(texture) {
    const first = Array.isArray(texture) ? texture[0] : texture
    if (typeof first === 'string') return first
    if (first && typeof first === 'object') {
      return first.uv || first.path || first.texture || first.default || ''
    }
    return ''
  }

  function parseBedrockGeometry(raw) {
    const holder = raw && raw['minecraft:geometry']
    const geometry = Array.isArray(holder) ? holder[0] : (holder || raw || {})
    const description = geometry.description || {}
    const source = Array.isArray(geometry.bones) ? geometry.bones : []
    const hidden = new Set()
    const isHidden = (name) => /colou?rpalette/i.test(name) || /^background$/i.test(name)
    for (const bone of source) {
      if (isHidden(bone.name || '')) hidden.add(bone.name)
    }
    // 隐藏的辅助骨骼可能还有子骨骼，一并排除。
    let changed = true
    while (changed) {
      changed = false
      for (const bone of source) {
        if (hidden.has(bone.name)) continue
        if (hidden.has(bone.parent)) {
          hidden.add(bone.name)
          changed = true
        }
      }
    }
    return {
      textureWidth: Number(description.texture_width) || 64,
      textureHeight: Number(description.texture_height) || 64,
      bones: source.filter((bone) => !hidden.has(bone.name)),
    }
  }

  function parseYsmChannel(channel) {
    if (!channel || typeof channel !== 'object' || Array.isArray(channel)) return null
    const frames = []
    for (const [key, value] of Object.entries(channel)) {
      const time = Number(key)
      if (!Number.isFinite(time)) continue
      const data = Array.isArray(value)
        ? value
        : value && (Array.isArray(value.post) ? value.post : value.pre)
      if (!Array.isArray(data) || data.length < 3) continue
      if (!data.slice(0, 3).every((item) => typeof item === 'number' && Number.isFinite(item))) continue
      frames.push({ time, value: [data[0], data[1], data[2]] })
    }
    frames.sort((a, b) => a.time - b.time)
    return frames.length ? frames : null
  }

  function parseYsmAnimation(name, raw) {
    const bones = {}
    const source = raw && raw.bones
    if (source && typeof source === 'object') {
      for (const [boneName, channels] of Object.entries(source)) {
        if (!channels || typeof channels !== 'object') continue
        bones[boneName] = {
          rotation: parseYsmChannel(channels.rotation),
          position: parseYsmChannel(channels.position),
          scale: parseYsmChannel(channels.scale),
        }
      }
    }
    const times = []
    for (const channel of Object.values(bones)) {
      for (const key of ['rotation', 'position', 'scale']) {
        const frames = channel[key]
        if (frames && frames.length) times.push(frames[frames.length - 1].time)
      }
    }
    return {
      name,
      loop: raw && raw.loop !== false,
      length: Math.max(0.05, Number(raw && raw.animation_length) || Math.max(0, ...times) || 1),
      bones,
    }
  }

  async function loadYsmAnimations(animationRef, readJson) {
    const refs = []
    if (typeof animationRef === 'string') {
      refs.push(animationRef)
    } else if (Array.isArray(animationRef)) {
      for (const item of animationRef) {
        if (typeof item === 'string') refs.push(item)
        else if (item && typeof item === 'object') {
          for (const value of Object.values(item)) {
            if (typeof value === 'string') refs.push(value)
          }
        }
      }
    } else if (animationRef && typeof animationRef === 'object') {
      for (const key of ['main', 'extra', 'carryon', 'tac']) {
        if (typeof animationRef[key] === 'string') refs.push(animationRef[key])
      }
      for (const value of Object.values(animationRef)) {
        if (typeof value === 'string' && !refs.includes(value)) refs.push(value)
      }
    }
    const merged = {}
    for (const ref of refs) {
      try {
        const raw = await readJson(ref)
        const animations = (raw && raw.animations) || {}
        for (const [name, value] of Object.entries(animations)) {
          if (!merged[name]) merged[name] = parseYsmAnimation(name, value)
        }
      } catch (error) {
        console.warn('[studio] YSM animation skipped', ref, error)
      }
    }
    const entries = Object.values(merged)
    const findByName = (keywords) => entries.find((item) => {
      const name = item.name.toLowerCase()
      return keywords.some((keyword) => name.includes(keyword))
    })
    return {
      idle: findByName(['idle', 'wait', 'rest']) || entries[0] || null,
      walk: findByName(['walk', 'move', 'run']) || null,
      all: entries,
    }
  }

  const YSM_WASM_BASE = 'https://iris.local/studio-assets/ysm/'
  let ysmParserPromise = null
  let ysmParseCounter = 0

  function loadYsmParser() {
    if (ysmParserPromise) return ysmParserPromise
    ysmParserPromise = (async () => {
      if (!window.YSMParserModule) {
        await new Promise((resolve, reject) => {
          const script = document.createElement('script')
          script.src = YSM_WASM_BASE + 'YSMParser.js'
          script.async = true
          script.onload = () => resolve()
          script.onerror = () => reject(new Error('YSMParser 脚本加载失败'))
          document.head.appendChild(script)
        })
      }
      if (!window.YSMParserModule) throw new Error('YSMParser 模块未导出')
      return window.YSMParserModule({
        locateFile: (path) => YSM_WASM_BASE + path,
        print: () => {},
        printErr: (message) => console.warn('[YSM]', message),
      })
    })().catch((error) => {
      ysmParserPromise = null
      throw error
    })
    return ysmParserPromise
  }

  function readYsmMemfs(module, root) {
    const files = []
    const walk = (dir) => {
      for (const name of module.FS.readdir(dir)) {
        if (name === '.' || name === '..') continue
        const full = dir + '/' + name
        const stat = module.FS.stat(full)
        if (module.FS.isDir(stat.mode)) walk(full)
        else files.push({ path: full.slice(root.length + 1), data: module.FS.readFile(full) })
      }
    }
    walk(root)
    return files
  }

  function removeYsmMemfs(module, root) {
    try {
      for (const name of module.FS.readdir(root)) {
        if (name === '.' || name === '..') continue
        const full = root + '/' + name
        const stat = module.FS.stat(full)
        if (module.FS.isDir(stat.mode)) removeYsmMemfs(module, full)
        else module.FS.unlink(full)
      }
      module.FS.rmdir(root)
    } catch (_) { }
  }

  async function parseYsmArchive(bytes) {
    const module = await loadYsmParser()
    const id = ++ysmParseCounter
    const outputDir = '/ysm/out-' + id
    const errorSize = 2048
    module.FS.mkdirTree(outputDir)
    const dataPtr = module._malloc(bytes.byteLength)
    const errorPtr = module._malloc(errorSize)
    try {
      if (module.writeArrayToMemory) module.writeArrayToMemory(bytes, dataPtr)
      else module.HEAPU8.set(bytes, dataPtr)
      // 第 4 个参数传 0：让 YSMParser 反推为 Blockbench 工程（cube 几何），
      // 复用现有的立方体/UV 渲染；V3 raw mesh 模式暂不使用。
      const code = module.ccall(
        'ysm_parse_bytes',
        'number',
        ['number', 'number', 'string', 'number', 'number', 'number'],
        [dataPtr, bytes.byteLength, outputDir, 0, errorPtr, errorSize],
      )
      if (code !== 0) {
        throw new Error(module.UTF8ToString(errorPtr, errorSize) || 'YSM 解密失败')
      }
      return readYsmMemfs(module, outputDir)
    } finally {
      module._free(dataPtr)
      module._free(errorPtr)
      removeYsmMemfs(module, outputDir)
    }
  }

  function decodeYsmText(data) {
    return new TextDecoder('utf-8').decode(data)
  }

  function normalizeYsmPath(value) {
    return String(value || '').replace(/\\/g, '/').replace(/^\.?\//, '')
  }

  async function loadYsmTexture(url) {
    const texture = await new THREE.TextureLoader().loadAsync(url)
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.NearestMipmapLinearFilter
    texture.flipY = false
    texture.colorSpace = THREE.SRGBColorSpace
    texture.needsUpdate = true
    return texture
  }

  async function loadYsmProject(manifest, readJson, readTexture) {
    const player = (manifest && manifest.files && manifest.files.player) || {}
    const modelInfo = player.model || {}
    const mainRef = typeof modelInfo.main === 'string'
      ? modelInfo.main
      : (modelInfo.main && (modelInfo.main.default || modelInfo.main.main)) || ''
    if (!mainRef) throw new Error('ysm.json 缺少 model.main')
    const geometry = parseBedrockGeometry(await readJson(mainRef))
    const textureRef = pickYsmTexture(player.texture)
    let texture = null
    if (textureRef) {
      try {
        texture = await readTexture(textureRef)
      } catch (error) {
        console.warn('[studio] YSM texture failed', error)
      }
    }
    const animations = await loadYsmAnimations(player.animation, readJson)
    return { geometry, texture, animations }
  }

  async function loadYsmFromUrl(url) {
    const base = url.substring(0, url.lastIndexOf('/') + 1)
    const manifest = await fetchYsmJson(url)
    const asset = await loadYsmProject(
      manifest,
      (ref) => fetchYsmJson(resolveYsmAsset(base, ref)),
      (ref) => loadYsmTexture(resolveYsmAsset(base, ref)),
    )
    return { ...asset, mirrorX: false }
  }

  async function loadYsmFromFiles(files) {
    const map = new Map()
    for (const file of files) {
      map.set(normalizeYsmPath(file.path), file.data)
    }
    const manifests = [...map.keys()].filter((path) => /(^|\/)ysm\.json$/i.test(path))
    manifests.sort((a, b) => a.split('/').length - b.split('/').length)
    const manifestPath = manifests[0]
    if (!manifestPath) throw new Error('解密结果缺少 ysm.json')
    const base = manifestPath.slice(0, manifestPath.lastIndexOf('/') + 1)
    const manifest = JSON.parse(decodeYsmText(map.get(manifestPath)))
    const readPath = (ref) => normalizeYsmPath(base + ref)
    const asset = await loadYsmProject(
      manifest,
      async (ref) => {
        const data = map.get(readPath(ref))
        if (!data) throw new Error('缺少文件 ' + ref)
        return JSON.parse(decodeYsmText(data))
      },
      async (ref) => {
        const data = map.get(readPath(ref))
        if (!data) throw new Error('缺少贴图 ' + ref)
        const url = URL.createObjectURL(new Blob([data], { type: 'image/png' }))
        const texture = await loadYsmTexture(url)
        setTimeout(() => URL.revokeObjectURL(url), 5000)
        return texture
      },
    )
    // YSMParser 输出的是 Blockbench 坐标，需要按 YSM 的镜像 X 规则转换。
    return { ...asset, mirrorX: true }
  }

  async function loadYsm(url) {
    if (ysmAssets.has(url)) return ysmAssets.get(url)
    const task = (async () => {
      if (/\.ysm(?:$|\?)/i.test(url)) {
        const response = await fetch(url)
        if (!response.ok) throw new Error('模型请求失败 ' + response.status)
        const bytes = new Uint8Array(await response.arrayBuffer())
        return loadYsmFromFiles(await parseYsmArchive(bytes))
      }
      return loadYsmFromUrl(url)
    })()
    ysmAssets.set(url, task)
    try {
      return await task
    } catch (error) {
      ysmAssets.delete(url)
      throw error
    }
  }

  function applyYsmBoxUv(geometry, uv, size, textureWidth, textureHeight, mirrorU) {
    const attributes = geometry.attributes.uv
    if (!attributes) return
    const depth = Math.max(0.01, size[2])
    const width = Math.max(0.01, size[0])
    const height = Math.max(0.01, size[1])
    const baseU = Array.isArray(uv) ? Number(uv[0]) || 0 : 0
    const baseV = Array.isArray(uv) ? Number(uv[1]) || 0 : 0
    const rects = {
      east: [baseU, baseV + depth, depth, height],
      west: [baseU + depth + width, baseV + depth, depth, height],
      up: [baseU + depth, baseV, width, depth],
      down: [baseU + depth + width, baseV, width, depth],
      south: [baseU + depth + width + depth, baseV + depth, width, height],
      north: [baseU + depth, baseV + depth, width, height],
    }
    const faces = [
      ['east', 0],
      ['west', 1],
      ['up', 2],
      ['down', 3],
      ['south', 4],
      ['north', 5],
    ]
    for (const [key, face] of faces) {
      let rect = rects[key]
      if (uv && !Array.isArray(uv) && uv[key]) {
        const perFace = uv[key]
        const offset = Array.isArray(perFace.uv) ? perFace.uv : [0, 0]
        const faceSize = Array.isArray(perFace.uv_size) ? perFace.uv_size : [rect[2], rect[3]]
        rect = [Number(offset[0]) || 0, Number(offset[1]) || 0, faceSize[0], faceSize[1]]
      }
      const u0 = rect[0] / textureWidth
      const u1 = (rect[0] + rect[2]) / textureWidth
      const v0 = rect[1] / textureHeight
      const v1 = (rect[1] + rect[3]) / textureHeight
      const corners = mirrorU
        ? [[u1, v0], [u0, v0], [u1, v1], [u0, v1]]
        : [[u0, v0], [u1, v0], [u0, v1], [u1, v1]]
      for (let i = 0; i < 4; i++) {
        attributes.setXY(face * 4 + i, corners[i][0], corners[i][1])
      }
    }
    attributes.needsUpdate = true
  }

  function buildYsmCube(asset, cube) {
    const size = Array.isArray(cube.size) ? cube.size : [0, 0, 0]
    const inflate = cube.inflate
    const ix = Array.isArray(inflate) ? Number(inflate[0]) || 0 : Number(inflate) || 0
    const iy = Array.isArray(inflate) ? Number(inflate[1]) || 0 : Number(inflate) || 0
    const iz = Array.isArray(inflate) ? Number(inflate[2]) || 0 : Number(inflate) || 0
    const geometry = cube.__ysmGeometry || new THREE.BoxGeometry(
      Math.max(0.01, size[0] + ix * 2),
      Math.max(0.01, size[1] + iy * 2),
      Math.max(0.01, size[2] + iz * 2),
    )
    if (!cube.__ysmGeometry) {
      applyYsmBoxUv(
        geometry,
        cube.uv,
        [size[0], size[1], size[2]],
        asset.geometry.textureWidth,
        asset.geometry.textureHeight,
        asset.mirrorX,
      )
      cube.__ysmGeometry = geometry
    }
    if (!asset.material) {
      asset.material = new THREE.MeshStandardMaterial({
        map: asset.texture,
        color: asset.texture ? 0xffffff : 0x9aa6b5,
        transparent: false,
        alphaTest: 0.5,
        side: THREE.DoubleSide,
        roughness: 0.9,
        metalness: 0,
      })
    }
    const mesh = new THREE.Mesh(geometry, asset.material)
    mesh.castShadow = true
    mesh.receiveShadow = true
    if (Array.isArray(cube.rotation)) {
      const mirror = asset.mirrorX ? -1 : 1
      mesh.rotation.order = 'ZYX'
      mesh.rotation.set(
        mirror * (Number(cube.rotation[0]) || 0) * DEG,
        mirror * (Number(cube.rotation[1]) || 0) * DEG,
        (Number(cube.rotation[2]) || 0) * DEG,
      )
    }
    return mesh
  }

  function buildYsmInstance(asset) {
    const mirrorX = !!asset.mirrorX
    const root = new THREE.Group()
    const inner = new THREE.Group()
    root.add(inner)
    const nodes = new Map()

    let minX = Infinity
    let minY = Infinity
    let minZ = Infinity
    let maxX = -Infinity
    let maxY = -Infinity
    let maxZ = -Infinity
    for (const bone of asset.geometry.bones) {
      for (const cube of bone.cubes || []) {
        const origin = Array.isArray(cube.origin) ? cube.origin : [0, 0, 0]
        const size = Array.isArray(cube.size) ? cube.size : [0, 0, 0]
        const inflate = cube.inflate
        const ix = Array.isArray(inflate) ? Number(inflate[0]) || 0 : Number(inflate) || 0
        const iy = Array.isArray(inflate) ? Number(inflate[1]) || 0 : Number(inflate) || 0
        const iz = Array.isArray(inflate) ? Number(inflate[2]) || 0 : Number(inflate) || 0
        minX = Math.min(minX, origin[0] - ix)
        minY = Math.min(minY, origin[1] - iy)
        minZ = Math.min(minZ, origin[2] - iz)
        maxX = Math.max(maxX, origin[0] + size[0] + ix)
        maxY = Math.max(maxY, origin[1] + size[1] + iy)
        maxZ = Math.max(maxZ, origin[2] + size[2] + iz)
      }
    }
    if (!Number.isFinite(minY)) {
      minX = 0
      minY = 0
      minZ = 0
      maxX = 8
      maxY = 32
      maxZ = 8
    }
    const centerX = mirrorX ? -(minX + maxX) / 2 : (minX + maxX) / 2
    const centerZ = (minZ + maxZ) / 2
    const heightPixels = Math.max(1, maxY - minY)
    // YSM 模型可能按像素（1 unit = 1/16 米）或按米导出，不能硬编码 1/16；
    // 一律按包围盒高度归一化到固定身高。
    const scale = YSM_TARGET_HEIGHT / heightPixels
    root.scale.setScalar(scale)
    // 综合空间的小人约定面朝 +Z；YSM 模型镜像后正面朝 -Z，需要转 180°。
    root.rotation.y = Math.PI
    root.position.y = -minY * scale
    inner.position.set(-centerX, 0, -centerZ)

    for (const bone of asset.geometry.bones) {
      const node = new THREE.Group()
      node.name = bone.name || ''
      node.rotation.order = 'ZYX'
      const pivot = Array.isArray(bone.pivot) ? bone.pivot : [0, 0, 0]
      node.position.set(mirrorX ? -pivot[0] : pivot[0], pivot[1], pivot[2])
      const rotation = Array.isArray(bone.rotation) ? bone.rotation : [0, 0, 0]
      node.rotation.set(
        (mirrorX ? -1 : 1) * (Number(rotation[0]) || 0) * DEG,
        (mirrorX ? -1 : 1) * (Number(rotation[1]) || 0) * DEG,
        (Number(rotation[2]) || 0) * DEG,
      )
      nodes.set(node.name, { bone, node, parent: bone.parent, absPivot: node.position.clone() })
    }
    for (const item of nodes.values()) {
      const parent = nodes.get(item.parent)
      if (parent) {
        item.node.position.copy(item.absPivot).sub(parent.absPivot)
        parent.node.add(item.node)
      } else {
        inner.add(item.node)
      }
      const pivot = Array.isArray(item.bone.pivot) ? item.bone.pivot : [0, 0, 0]
      for (const cube of item.bone.cubes || []) {
        const mesh = buildYsmCube(asset, cube)
        const origin = Array.isArray(cube.origin) ? cube.origin : [0, 0, 0]
        const size = Array.isArray(cube.size) ? cube.size : [0, 0, 0]
        const dx = origin[0] + size[0] / 2 - pivot[0]
        mesh.position.set(
          mirrorX ? -dx : dx,
          origin[1] + size[1] / 2 - pivot[1],
          origin[2] + size[2] / 2 - pivot[2],
        )
        item.node.add(mesh)
      }
    }
    for (const item of nodes.values()) {
      item.basePosition = item.node.position.clone()
      item.baseRotation = item.node.rotation.clone()
      item.baseScale = item.node.scale.clone()
    }
    // 部分 YSM 模型同时提供 Soc_（袜子/鞋）与 Emp_（空腿）两套腿部，
    // 默认显隐靠 Molang 表达式控制；这里在有袜子模型时隐藏空腿，避免两套重叠。
    const boneNames = new Set(asset.geometry.bones.map((bone) => bone.name || ''))
    const hasSocks = Array.from(boneNames).some((name) => /^Soc_/i.test(name))
    if (hasSocks) {
      for (const item of nodes.values()) {
        if (/^Emp_/i.test(item.node.name)) item.node.visible = false
      }
    }

    function sample(frames, time) {
      if (!frames || !frames.length) return null
      if (time <= frames[0].time) return frames[0].value
      const last = frames[frames.length - 1]
      if (time >= last.time) return last.value
      for (let i = 1; i < frames.length; i++) {
        const current = frames[i]
        if (time <= current.time) {
          const previous = frames[i - 1]
          const span = Math.max(0.0001, current.time - previous.time)
          const k = (time - previous.time) / span
          return [
            previous.value[0] + (current.value[0] - previous.value[0]) * k,
            previous.value[1] + (current.value[1] - previous.value[1]) * k,
            previous.value[2] + (current.value[2] - previous.value[2]) * k,
          ]
        }
      }
      return last.value
    }

    let elapsed = 0
    let action = asset.animations.idle
    function play(next) {
      if (!next || next === action) return
      action = next
      elapsed = 0
    }
    function update(dt, moving) {
      elapsed += dt
      const wanted = moving
        ? (asset.animations.walk || asset.animations.idle)
        : asset.animations.idle
      play(wanted)
      if (!action) return
      const time = action.loop ? elapsed % action.length : Math.min(elapsed, action.length)
      for (const [boneName, channel] of Object.entries(action.bones)) {
        const item = nodes.get(boneName)
        if (!item) continue
        const rotation = sample(channel.rotation, time)
        if (rotation) {
          item.node.rotation.set(
            (mirrorX ? -1 : 1) * rotation[0] * DEG,
            (mirrorX ? -1 : 1) * rotation[1] * DEG,
            rotation[2] * DEG,
          )
        }
        const position = sample(channel.position, time)
        if (position) {
          item.node.position.set(
            item.basePosition.x + (mirrorX ? -position[0] : position[0]),
            item.basePosition.y + position[1],
            item.basePosition.z + position[2],
          )
        }
        const scaleValue = sample(channel.scale, time)
        if (scaleValue) {
          item.node.scale.set(scaleValue[0], scaleValue[1], scaleValue[2])
        }
      }
    }
    return { root, update, nodes, asset }
  }

  function attachYsm(person, url) {
    loadYsm(url).then((asset) => {
      if (!person.parent) return
      const instance = buildYsmInstance(asset)
      person.add(instance.root)
      for (const child of person.children) {
        if (child !== instance.root) child.visible = false
      }
      person.userData.ysm = instance
    }).catch((error) => {
      console.warn('[studio] YSM load failed', url, error)
    })
  }

  function addPerson(group, options) {
    const p = palette()
    const person = new THREE.Group()
    const scale = options.scale ?? 1
    const radius = PERSON_RADIUS * scale
    const spot = findFreeSpot(options.x, options.z, radius)
    person.position.set(spot.x, options.y ?? 0, spot.z)
    person.rotation.y = options.facing ?? 0
    person.scale.setScalar(scale)
    group.add(person)

    const accent = options.accent ?? accentColor()
    const cloth = surface(accent, { roughness: 0.62 })
    const skin = surface(options.skin ?? p.skin, { roughness: 0.9 })
    const hair = surface(options.hair ?? p.hair, { roughness: 0.9 })
    const trousers = surface(options.trousers ?? p.darkMetal, { roughness: 0.85 })

    const legL = put(person, new THREE.CapsuleGeometry(0.09, 0.3, 4, 8), trousers, -0.11, 0.31, 0)
    const legR = put(person, new THREE.CapsuleGeometry(0.09, 0.3, 4, 8), trousers, 0.11, 0.31, 0)
    const body = put(person, new THREE.CapsuleGeometry(0.24, 0.38, 4, 10), cloth, 0, 0.85, 0)
    const armL = put(person, new THREE.CapsuleGeometry(0.065, 0.25, 4, 8), cloth, -0.3, 0.85, 0.02)
    const armR = put(person, new THREE.CapsuleGeometry(0.065, 0.25, 4, 8), cloth, 0.3, 0.85, 0.02)
    armL.rotation.z = 0.24
    armR.rotation.z = -0.24
    const handL = sphere(person, 0.075, skin, -0.35, 0.67, 0.03)
    const handR = sphere(person, 0.075, skin, 0.35, 0.67, 0.03)
    const head = sphere(person, 0.23, skin, 0, 1.32, 0, { detail: 18 })
    const hairMesh = sphere(person, 0.245, hair, 0, 1.37, -0.03, { detail: 16 })
    hairMesh.scale.set(1, 0.78, 1)
    const nose = sphere(person, 0.035, skin, 0, 1.29, 0.21, { detail: 8 })
    const eyeL = sphere(person, 0.023, p.hair, -0.08, 1.34, 0.2, { detail: 8 })
    const eyeR = sphere(person, 0.023, p.hair, 0.08, 1.34, 0.2, { detail: 8 })
    const style = options.style || 'standard'
    if (style === 'longhair' || style === 'dress') {
      const backHair = put(person, new THREE.CapsuleGeometry(0.17, 0.36, 4, 8), hair, 0, 1.02, -0.13)
      backHair.scale.set(1, 1.2, 0.7)
      hairMesh.scale.set(1.02, 0.94, 1.02)
    }
    if (style === 'dress') {
      cone(person, 0.4, 0.66, cloth, 0, 0.58, 0, { segments: 16 })
      legL.visible = false
      legR.visible = false
    }
    if (style === 'outfit') {
      box(person, 0.07, 0.3, 0.05, '#f2b544', -0.19, 0.84, 0.23)
      box(person, 0.07, 0.3, 0.05, '#f2b544', 0.19, 0.84, 0.23)
    }
    if (options.helmet) {
      const helmet = sphere(person, 0.27, '#f2b544', 0, 1.45, 0, { detail: 14 })
      helmet.scale.set(1.05, 0.62, 1.05)
      const brim = cylinder(person, 0.3, 0.3, 0.035, 18, '#f2b544', 0, 1.4, 0.06)
      brim.rotation.x = 0.06
    }
    person.userData.baseY = person.position.y
    person.userData.phase = rand() * 6
    person.userData.body = body
    person.userData.armL = armL
    person.userData.armR = armR
    person.userData.head = head
    person.userData.legL = legL
    person.userData.legR = legR
    person.userData.handL = handL
    person.userData.handR = handR
    person.userData.nose = nose
    person.userData.eyes = [eyeL, eyeR]
    person.userData.walk = {
      minX: walkArea.minX,
      maxX: walkArea.maxX,
      minZ: walkArea.minZ,
      maxZ: walkArea.maxZ,
      radius: radius,
      speed: options.walkSpeed ?? 1.0,
      targetX: spot.x,
      targetZ: spot.z,
      wait: 1.2 + rand() * 3.2,
      moving: false,
    }
    pickWalkTarget(person.userData.walk)
    animateFns.push({ kind: 'person', data: person.userData })
    if (options.model) attachYsm(person, options.model)
    return person
  }

  function buildTree(group, x, z, scale = 1) {
    const p = palette()
    cylinder(group, 0.1 * scale, 0.14 * scale, 0.8 * scale, 6, p.trunk, x, 0.4 * scale, z)
    const crown = put(
      group,
      new THREE.IcosahedronGeometry(0.6 * scale, 0),
      p.leaf,
      x,
      1.15 * scale,
      z,
    )
    crown.rotation.y = rand() * Math.PI
    const top = put(
      group,
      new THREE.IcosahedronGeometry(0.4 * scale, 0),
      p.leafLight,
      x + 0.1 * scale,
      1.65 * scale,
      z - 0.05 * scale,
    )
    top.rotation.y = rand() * Math.PI
  }

  function buildPlant(group, x, z, scale = 1) {
    const p = palette()
    cylinder(group, 0.23 * scale, 0.18 * scale, 0.34 * scale, 10, p.woodLight, x, 0.17 * scale, z)
    const leaves = [
      [0, 0.67, 0],
      [-0.18, 0.56, 0.08],
      [0.17, 0.58, -0.05],
      [0.04, 0.52, 0.18],
    ]
    leaves.forEach(([dx, dy, dz], index) => {
      const leaf = put(
        group,
        new THREE.SphereGeometry(0.19 * scale, 8, 6),
        index % 2 ? p.leafLight : p.leaf,
        x + dx * scale,
        dy * scale + 0.1,
        z + dz * scale,
      )
      leaf.scale.set(1, 1.5, 1)
      leaf.rotation.z = dx * 1.6
    })
  }

  function buildSofa(group, x, z, width, facing = 0) {
    const p = palette()
    const sofa = new THREE.Group()
    sofa.position.set(x, 0, z)
    sofa.rotation.y = facing
    group.add(sofa)
    roundBox(sofa, width, 0.34, 0.9, 0.13, p.fabric, 0, 0.28, 0)
    roundBox(sofa, width, 0.62, 0.24, 0.1, p.fabricLight, 0, 0.58, -0.36)
    roundBox(sofa, 0.24, 0.4, 0.9, 0.1, p.fabricLight, -width / 2 + 0.1, 0.46, 0)
    roundBox(sofa, 0.24, 0.4, 0.9, 0.1, p.fabricLight, width / 2 - 0.1, 0.46, 0)
    for (const dx of [-width / 2 + 0.3, width / 2 - 0.3]) {
      roundBox(sofa, 0.28, 0.2, 0.5, 0.08, p.paper, dx, 0.52, 0.05)
    }
    return sofa
  }

  function buildRoom(group, options = {}) {
    const p = palette()
    const width = options.width ?? 10
    const depth = options.depth ?? 8
    const height = options.height ?? 2.8
    roundBox(group, width + 0.5, 0.28, depth + 0.5, 0.12, p.slab, 0, -0.15, 0, { receive: true })
    box(group, width, 0.1, depth, p.floor, 0, 0.02, 0, { receive: true })
    box(group, width, height, 0.14, p.wall, 0, height / 2, -depth / 2)
    box(group, 0.14, height, depth, p.wall, -width / 2, height / 2, 0)
    box(group, width, 0.18, 0.18, p.wallLow, 0, 0.1, -depth / 2 + 0.02)
    box(group, 0.18, 0.18, depth, p.wallLow, -width / 2 + 0.02, 0.1, 0)

    const winW = options.windowWidth ?? 2.6
    const winX = options.windowX ?? -1
    const winY = options.windowY ?? 1.75
    box(group, winW, 1.35, 0.04, p.glass, winX, winY, -depth / 2 + 0.09, {
      glow: true,
      roughness: 0.15,
      metalness: 0.15,
    })
    box(group, winW + 0.12, 0.06, 0.1, p.metal, winX, winY + 0.7, -depth / 2 + 0.1)
    box(group, 0.06, 1.35, 0.12, p.metal, winX - winW / 2, winY, -depth / 2 + 0.1)
    box(group, 0.06, 1.35, 0.12, p.metal, winX + winW / 2, winY, -depth / 2 + 0.1)
    if (options.board) {
      box(group, 2.4, 1.15, 0.05, p.paper, -width / 2 + 0.1, 1.65, 1.1)
      box(group, 2.2, 0.04, 0.02, accentColor(), -width / 2 + 0.13, 1.75, 1.1, { glow: true })
      for (let i = 0; i < 3; i++) {
        box(group, 0.55, 0.03, 0.01, p.darkMetal, -width / 2 + 0.13, 1.35 + i * 0.16, 1.1)
      }
    }
    if (options.shelf) {
      buildShelf(group, -width / 2 + 0.55, -depth / 2 + 0.65, 2.5, 2.2, 'z')
    }
  }

  function buildDesk(group, x, z, options = {}) {
    const p = palette()
    const width = options.width ?? 1.6
    roundBox(group, width, 0.08, 0.72, 0.04, p.deskTop, x, 0.76, z)
    for (const dx of [-width / 2 + 0.12, width / 2 - 0.12]) {
      box(group, 0.07, 0.72, 0.07, p.darkMetal, x + dx, 0.38, z - 0.26)
      box(group, 0.07, 0.72, 0.07, p.darkMetal, x + dx, 0.38, z + 0.26)
    }
    if (options.monitor !== false) {
      box(group, 0.08, 0.34, 0.08, p.darkMetal, x, 0.95, z - 0.08)
      const frame = box(group, 0.72, 0.42, 0.05, p.darkMetal, x, 1.18, z - 0.08)
      frame.rotation.x = -0.05
      const screen = put(
        group,
        new THREE.PlaneGeometry(0.64, 0.34),
        options.screenColor ?? accentColor(),
        x,
        1.18,
        z - 0.045,
        { glow: true, emissiveIntensity: 0.8, pulse: true, cast: false },
      )
      screen.rotation.x = -0.05
    }
    if (options.chair !== false) {
      roundBox(group, 0.46, 0.08, 0.44, 0.06, p.fabric, x, 0.46, z + 0.72)
      roundBox(group, 0.44, 0.48, 0.08, 0.06, p.fabricLight, x, 0.7, z + 0.94)
      box(group, 0.06, 0.42, 0.06, p.metal, x, 0.22, z + 0.72)
    }
    return { width }
  }

  function buildShelf(group, x, z, width = 2.4, height = 2.2, axis = 'x') {
    const p = palette()
    const depth = 0.42
    const g = new THREE.Group()
    g.position.set(x, 0, z)
    if (axis === 'z') g.rotation.y = Math.PI / 2
    group.add(g)
    box(g, width, 0.08, depth, p.wood, 0, 0.06, 0)
    box(g, width, 0.08, depth, p.wood, 0, height, 0)
    box(g, 0.08, height, depth, p.wood, -width / 2, height / 2, 0)
    box(g, 0.08, height, depth, p.wood, width / 2, height / 2, 0)
    box(g, width, height, 0.04, p.woodLight, 0, height / 2, -depth / 2 + 0.02)
    const rows = 4
    const colors = ['#7db3ff', '#ff9aa2', '#7fe0c3', '#f6c177', '#b39ddb', '#f28e8e', '#8fd4ff']
    for (let row = 0; row < rows; row++) {
      const shelfY = 0.35 + row * ((height - 0.5) / rows)
      box(g, width - 0.16, 0.05, depth - 0.06, p.wood, 0, shelfY, 0)
      let cursor = -width / 2 + 0.16
      while (cursor < width / 2 - 0.18) {
        const bookW = 0.07 + rand() * 0.05
        const bookH = 0.18 + rand() * 0.12
        box(
          g,
          bookW,
          bookH,
          depth - 0.2,
          colors[Math.floor(rand() * colors.length)],
          cursor + bookW / 2,
          shelfY + bookH / 2 + 0.03,
          0,
        )
        cursor += bookW + 0.012
      }
    }
    return g
  }

  /** 地毯：底色 + 内嵌边框，纯装饰不参与碰撞。 */
  function buildRug(group, x, z, width, depth, color, options = {}) {
    const p = palette()
    plane(group, width, depth, color, x, 0.085, z, { opacity: options.opacity ?? 0.94, cast: false })
    plane(group, width - 0.3, depth - 0.3, options.border ?? p.fabricLight, x, 0.09, z, {
      opacity: 0.5,
      cast: false,
    })
    plane(group, width - 0.52, depth - 0.52, color, x, 0.095, z, { opacity: 0.94, cast: false })
  }

  /** 壁灯：贴墙底座 + 暖光灯泡。 */
  function buildWallLamp(group, x, y, z, options = {}) {
    const p = palette()
    box(group, 0.12, 0.26, 0.07, p.darkMetal, x, y, z)
    sphere(group, 0.12, '#ffe2b8', x, y + 0.02, z + (options.out ?? 0.08), { glow: true, detail: 10 })
  }

  /** 墙面装饰：外框 + 内芯，rotation 为绕 Y 轴朝向。 */
  function buildWallFrame(group, x, y, z, width, height, options = {}) {
    const p = palette()
    const rotation = options.rotation ?? 0
    const nx = Math.sin(rotation) * 0.035
    const nz = Math.cos(rotation) * 0.035
    box(group, width, height, 0.05, options.frame ?? p.woodLight, x, y, z, { rotation: [0, rotation, 0] })
    box(
      group,
      width - 0.12,
      height - 0.12,
      0.02,
      options.color ?? p.paper,
      x + nx,
      y,
      z + nz,
      { rotation: [0, rotation, 0], glow: options.glow, emissiveIntensity: 0.4 },
    )
  }

  /** 户外路灯，自带小碰撞盒。 */
  function buildLampPost(group, x, z, options = {}) {
    const p = palette()
    const height = options.height ?? 2.3
    cylinder(group, 0.11, 0.15, 0.14, 10, p.darkMetal, x, 0.07, z)
    cylinder(group, 0.045, 0.06, height, 8, p.darkMetal, x, height / 2 + 0.1, z)
    box(group, 0.5, 0.06, 0.06, p.darkMetal, x + 0.23, height + 0.08, z)
    sphere(group, 0.11, '#ffe2b8', x + 0.44, height, z, { glow: true, detail: 10 })
    addObstacleAt(x, z, 0.36, 0.36)
  }

  /** 户外长椅：座板 + 靠背 + 金属脚，朝向可选。 */
  function buildBench(group, x, z, width, options = {}) {
    const p = palette()
    const bench = new THREE.Group()
    bench.position.set(x, 0, z)
    bench.rotation.y = options.facing ?? 0
    group.add(bench)
    for (let i = 0; i < 3; i++) {
      box(bench, width, 0.06, 0.14, options.color ?? p.woodLight, 0, 0.42, -0.16 + i * 0.16)
    }
    for (const dx of [-width / 2 + 0.12, width / 2 - 0.12]) {
      box(bench, 0.08, 0.42, 0.44, p.darkMetal, dx, 0.21, 0)
      box(bench, 0.08, 0.52, 0.08, p.darkMetal, dx, 0.68, -0.32)
    }
    for (let i = 0; i < 2; i++) {
      box(bench, width, 0.14, 0.05, options.color ?? p.woodLight, 0, 0.64 + i * 0.2, -0.34)
    }
    addObstacleAt(x, z, width + 0.2, 0.78)
  }

  /** 花坛：石框 + 土 + 花，带碰撞。 */
  function buildFlowerBed(group, x, z, width, depth, options = {}) {
    const p = palette()
    box(group, width, 0.22, depth, p.stone, x, 0.11, z)
    box(group, width - 0.18, 0.1, depth - 0.18, p.dirt, x, 0.2, z, { cast: false })
    const colors = ['#ff9aa2', '#f6c177', '#b39ddb', '#ffd166']
    for (let i = 0; i < Math.max(4, Math.round(width * 2.4)); i++) {
      const fx = x - width / 2 + 0.22 + (i % 5) * ((width - 0.44) / 5)
      const fz = z - depth / 2 + 0.22 + Math.floor(i / 5) * ((depth - 0.44) / 2)
      cylinder(group, 0.02, 0.02, 0.24, 5, p.leaf, fx, 0.34, fz)
      sphere(group, 0.075, colors[i % colors.length], fx, 0.48, fz, { detail: 8 })
    }
    addObstacleAt(x, z, width, depth)
  }

  /** 绿篱：叶色矮墙 + 顶部球体，带碰撞。 */
  function buildHedge(group, x, z, width, depth, options = {}) {
    const p = palette()
    const height = options.height ?? 0.62
    box(group, width, height, depth, p.leaf, x, height / 2, z, { roughness: 0.92 })
    for (let i = 0; i < Math.max(2, Math.round(width / 0.45)); i++) {
      sphere(group, 0.16, p.leafLight, x - width / 2 + 0.22 + i * 0.45, height + 0.01, z, { detail: 8 })
    }
    addObstacleAt(x, z, width, depth)
  }

  /** 广场喷泉：石池、水面、中心台与发光水柱。 */
  function buildFountain(group, x, z, options = {}) {
    const p = palette()
    const radius = options.radius ?? 1.5
    cylinder(group, radius, radius, 0.26, 30, p.stone, x, 0.13, z)
    cylinder(group, radius - 0.2, radius - 0.2, 0.08, 30, p.glass, x, 0.28, z, {
      glow: true,
      opacity: 0.72,
      emissiveIntensity: 0.25,
    })
    cylinder(group, 0.34, 0.46, 0.72, 16, p.stone, x, 0.55, z)
    cylinder(group, 0.76, 0.66, 0.12, 22, p.stone, x, 0.95, z)
    sphere(group, 0.14, accentColor(), x, 1.08, z, { glow: true, detail: 10 })
    for (let i = 0; i < 8; i++) {
      const angle = (i / 8) * Math.PI * 2
      cylinder(
        group,
        0.028,
        0.028,
        0.46,
        5,
        p.glass,
        x + Math.cos(angle) * (radius - 0.45),
        0.53,
        z + Math.sin(angle) * (radius - 0.45),
        { opacity: 0.35, cast: false },
      )
    }
    addObstacleAt(x, z, radius * 2 + 0.16, radius * 2 + 0.16)
  }

  /** 木箱/料箱。 */
  function buildCrate(group, x, z, options = {}) {
    const p = palette()
    const width = options.width ?? 0.62
    const height = options.height ?? 0.5
    const depth = options.depth ?? 0.62
    const color = options.color ?? p.woodLight
    box(group, width, height, depth, color, x, height / 2 + 0.05, z, { roughness: 0.86 })
    box(group, width + 0.02, 0.06, 0.04, p.wood, x, height / 2 + 0.05, z + depth / 2 + 0.01)
    box(group, width + 0.02, 0.06, 0.04, p.wood, x, height * 0.3, z + depth / 2 + 0.01)
    addObstacleAt(x, z, width + 0.04, depth + 0.04)
  }

  /** 圆桶（油桶/涂料桶）。 */
  function buildBarrel(group, x, z, options = {}) {
    const p = palette()
    const color = options.color ?? '#7db3ff'
    cylinder(group, 0.26, 0.26, 0.7, 14, color, x, 0.35, z, { roughness: 0.7 })
    cylinder(group, 0.29, 0.29, 0.05, 14, p.darkMetal, x, 0.17, z)
    cylinder(group, 0.29, 0.29, 0.05, 14, p.darkMetal, x, 0.55, z)
    addObstacleAt(x, z, 0.58, 0.58)
  }

  /** 成捆钢管，沿 X 轴摆放。 */
  function buildPipes(group, x, z, length, options = {}) {
    const p = palette()
    box(group, length + 0.3, 0.08, 0.62, p.wood, x, 0.04, z)
    for (let i = 0; i < 3; i++) {
      cylinder(group, 0.085, 0.085, length, 10, options.color ?? p.metal, x, 0.15 + i * 0.18, z, {
        rotation: [0, 0, Math.PI / 2],
      })
    }
    addObstacleAt(x, z, length, 0.7)
  }

  /** 工地集装箱办公室。 */
  function buildContainer(group, x, z, options = {}) {
    const p = palette()
    const width = options.width ?? 2.2
    const height = options.height ?? 2.05
    const depth = options.depth ?? 1.8
    roundBox(group, width, height, depth, 0.05, options.color ?? '#7fb0c9', x, height / 2 + 0.02, z)
    box(group, width - 0.3, 0.05, depth - 0.3, p.metal, x, height + 0.04, z, { cast: false })
    box(group, 0.5, height - 0.42, 0.04, p.darkMetal, x, (height - 0.42) / 2 + 0.02, z + depth / 2)
    box(group, 0.42, 0.3, 0.03, p.glass, x, height - 0.58, z + depth / 2 + 0.02, { glow: true, cast: false })
    for (const dx of [-0.55, -0.15]) {
      box(group, 0.3, 0.3, 0.03, p.glass, x + dx, height - 0.6, z + depth / 2 + 0.02, { cast: false })
    }
    addObstacleAt(x, z, width + 0.12, depth + 0.12)
  }

  /** 立式告示牌。 */
  function buildSignpost(group, x, z, color, options = {}) {
    const p = palette()
    cylinder(group, 0.035, 0.035, 1.45, 6, p.metal, x, 0.72, z)
    box(group, 0.82, 0.52, 0.05, p.paper, x, 1.4, z)
    box(group, 0.7, 0.1, 0.02, color, x, 1.56, z + 0.04)
    box(group, 0.52, 0.05, 0.02, p.darkMetal, x, 1.4, z + 0.04)
    box(group, 0.62, 0.05, 0.02, p.darkMetal, x, 1.3, z + 0.04)
    addObstacleAt(x, z, 0.32, 0.32)
  }

  /** 饮水机。 */
  function buildWaterCooler(group, x, z) {
    const p = palette()
    box(group, 0.42, 0.92, 0.42, p.paper, x, 0.46, z)
    cylinder(group, 0.17, 0.15, 0.44, 12, p.glass, x, 1.12, z, { opacity: 0.75 })
    box(group, 0.26, 0.06, 0.06, p.metal, x, 0.56, z + 0.23)
    addObstacleAt(x, z, 0.5, 0.5)
  }

  /** 排队立柱，可配一条横杆。 */
  function buildStanchion(group, x, z, options = {}) {
    const p = palette()
    cylinder(group, 0.2, 0.23, 0.05, 12, p.metal, x, 0.03, z)
    cylinder(group, 0.032, 0.032, 0.88, 8, p.metal, x, 0.48, z)
    sphere(group, 0.055, options.color ?? accentColor(), x, 0.96, z, { detail: 8 })
  }

  /** 墙面时钟，默认朝 +Z。 */
  function buildWallClock(group, x, y, z) {
    const p = palette()
    cylinder(group, 0.32, 0.32, 0.05, 24, p.paper, x, y, z, { rotation: [Math.PI / 2, 0, 0] })
    cylinder(group, 0.28, 0.28, 0.02, 24, p.darkMetal, x, y, z + 0.035, { rotation: [Math.PI / 2, 0, 0], cast: false })
    box(group, 0.02, 0.2, 0.015, p.paper, x, y + 0.1, z + 0.05)
    box(group, 0.15, 0.02, 0.015, p.paper, x + 0.07, y, z + 0.05, { rotation: [0, 0, 0.3] })
  }

  function buildHome() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    buildRoom(g, { width: 13, depth: 10, height: 3.1, windowWidth: 3.2, windowX: 0.6, windowY: 1.9 })

    // 木地板、踢脚线与护墙板
    for (let i = 0; i < 10; i++) {
      plane(g, 12.7, 0.92, i % 2 ? p.floor : p.floorDark, 0, 0.075, -4.5 + i, { cast: false })
    }
    box(g, 12.9, 0.16, 0.07, p.wallLow, 0, 0.15, -4.94)
    box(g, 0.07, 0.16, 9.8, p.wallLow, -6.44, 0.15, 0)
    box(g, 0.07, 0.16, 9.8, p.wallLow, 6.44, 0.15, 0)
    box(g, 12.8, 0.9, 0.04, p.wallLow, 0, 0.6, -4.96)

    // 客厅：地毯、沙发、茶几与电视墙
    plane(g, 5.4, 4.0, p.fabricLight, -1.8, 0.08, -1.4, { opacity: 0.95, cast: false })
    plane(g, 4.8, 0.1, accentColor(), -1.8, 0.085, -3.3, { opacity: 0.5, cast: false })
    buildSofa(g, -1.8, -3.4, 3.0, 0)
    roundBox(g, 0.42, 0.42, 0.22, 0.08, accentColor(), -3.0, 0.62, -3.35, { rotation: [0.1, 0.2, 0.05] })
    roundBox(g, 0.42, 0.42, 0.22, 0.08, p.fabricLight, -0.6, 0.62, -3.35, { rotation: [0.1, -0.2, -0.05] })
    roundBox(g, 1.0, 0.06, 0.6, 0.05, p.fabric, -1.4, 0.47, -3.2)
    addObstacle(-3.35, -0.25, -3.9, -2.9)

    roundBox(g, 1.7, 0.12, 0.95, 0.06, p.woodLight, -1.8, 0.42, -1.8)
    box(g, 0.08, 0.36, 0.08, p.metal, -2.45, 0.2, -1.55)
    box(g, 0.08, 0.36, 0.08, p.metal, -1.15, 0.2, -1.55)
    box(g, 0.34, 0.05, 0.25, p.paper, -2.2, 0.51, -1.85)
    box(g, 0.3, 0.04, 0.22, '#d97a7a', -2.18, 0.56, -1.84)
    cylinder(g, 0.06, 0.05, 0.12, 10, p.paper, -1.45, 0.54, -1.7)
    cylinder(g, 0.09, 0.07, 0.28, 10, p.glass, -1.8, 0.62, -1.9)
    sphere(g, 0.12, accentColor(), -1.8, 0.8, -1.9, { glow: true, detail: 10 })
    addObstacle(-2.7, -0.9, -2.3, -1.3)

    roundBox(g, 2.8, 0.5, 0.5, 0.06, p.wood, -1.8, 0.28, -4.5)
    box(g, 2.0, 1.1, 0.06, p.darkMetal, -1.8, 1.18, -4.42)
    plane(g, 1.8, 0.96, accentColor(), -1.8, 1.18, -4.38, {
      glow: true,
      emissiveIntensity: 0.6,
      pulse: true,
      cast: false,
    })
    box(g, 0.26, 0.5, 0.24, p.darkMetal, -3.4, 0.35, -4.45)
    box(g, 0.26, 0.5, 0.24, p.darkMetal, -0.2, 0.35, -4.45)
    box(g, 0.34, 0.08, 0.24, p.metal, -1.8, 0.58, -4.45)
    addObstacle(-3.25, -0.35, -4.8, -4.2)

    // 窗帘与壁灯
    roundBox(g, 0.36, 2.2, 0.12, 0.05, p.fabric, -1.0, 1.85, -4.85)
    roundBox(g, 0.36, 2.2, 0.12, 0.05, p.fabric, 2.2, 1.85, -4.85)
    cylinder(g, 0.02, 0.02, 3.5, 6, p.metal, 0.6, 2.95, -4.85, { rotation: [0, 0, Math.PI / 2] })
    box(g, 0.12, 0.3, 0.08, p.metal, -6.4, 1.9, -2.0)
    sphere(g, 0.12, '#ffe2b8', -6.3, 2.02, -2.0, { glow: true, detail: 10 })

    // 餐桌、餐具与吊灯
    cylinder(g, 1.12, 1.12, 0.1, 28, p.deskTop, 3.8, 0.76, -2.6)
    cylinder(g, 1.16, 1.16, 0.02, 28, p.fabricLight, 3.8, 0.82, -2.6)
    cylinder(g, 0.12, 0.22, 0.72, 12, p.darkMetal, 3.8, 0.38, -2.6)
    for (let i = 0; i < 4; i++) {
      const angle = (i / 4) * Math.PI * 2 + Math.PI / 4
      const cx = 3.8 + Math.cos(angle) * 1.62
      const cz = -2.6 + Math.sin(angle) * 1.62
      roundBox(g, 0.44, 0.08, 0.42, 0.05, p.fabric, cx, 0.46, cz)
      roundBox(
        g, 0.42, 0.46, 0.07, 0.05, p.fabricLight,
        cx + Math.cos(angle) * 0.2, 0.68, cz + Math.sin(angle) * 0.2,
        { rotation: [0, -angle + Math.PI / 2, 0] },
      )
      box(g, 0.06, 0.44, 0.06, p.metal, cx, 0.22, cz)
    }
    for (const [x, z] of [[3.4, -2.6], [4.2, -2.6]]) {
      cylinder(g, 0.16, 0.16, 0.03, 18, p.paper, x, 0.84, z)
      cylinder(g, 0.06, 0.05, 0.18, 10, p.glass, x, 0.94, z)
    }
    cylinder(g, 0.1, 0.08, 0.3, 10, p.glass, 3.8, 0.9, -2.9)
    sphere(g, 0.16, '#e27d9c', 3.8, 1.12, -2.9, { detail: 12 })
    cylinder(g, 0.02, 0.02, 0.95, 6, p.metal, 3.8, 2.62, -2.6)
    const pendant = sphere(g, 0.3, '#ffe2b8', 3.8, 2.12, -2.6, { glow: true, detail: 14 })
    pendant.scale.set(1, 0.62, 1)
    addObstacle(1.9, 5.7, -4.5, -0.75)

    // 厨房岛台、吧台凳与吊柜
    roundBox(g, 2.4, 0.9, 0.9, 0.08, p.deskTop, 4.6, 0.45, 3.5)
    box(g, 2.2, 0.06, 0.7, p.metal, 4.6, 0.92, 3.5)
    box(g, 0.62, 0.06, 0.42, p.darkMetal, 4.1, 0.96, 3.5)
    cylinder(g, 0.03, 0.03, 0.34, 8, p.metal, 4.1, 1.16, 3.7)
    roundBox(g, 0.5, 0.62, 0.42, 0.05, p.metal, 5.3, 1.25, 3.5)
    box(g, 0.16, 0.08, 0.2, p.darkMetal, 5.3, 1.04, 3.5)
    cylinder(g, 0.07, 0.05, 0.12, 8, p.paper, 5.3, 0.98, 3.2)
    box(g, 0.5, 0.7, 1.8, p.wood, 6.25, 2.1, 3.2)
    for (const dz of [-0.45, 0.45]) {
      box(g, 0.03, 0.66, 0.8, p.woodLight, 5.98, 2.1, 3.2 + dz)
      box(g, 0.04, 0.14, 0.04, p.metal, 5.95, 2.1, 3.2 + dz + (dz > 0 ? -0.28 : 0.28))
    }
    for (const [x, z] of [[3.4, 2.6], [4.0, 2.6]]) {
      cylinder(g, 0.2, 0.2, 0.06, 14, p.fabric, x, 0.62, z)
      cylinder(g, 0.04, 0.04, 0.6, 8, p.metal, x, 0.31, z)
      cylinder(g, 0.26, 0.26, 0.03, 14, p.metal, x, 0.03, z)
    }
    addObstacle(3.35, 5.85, 3.0, 4.0)
    addObstacle(3.1, 4.3, 2.3, 2.9)

    // 卧室：床、床头柜、床尾凳与衣柜
    plane(g, 2.6, 2.2, p.fabricLight, -4.9, 0.085, 2.1, { cast: false })
    roundBox(g, 2.4, 0.36, 3.0, 0.08, p.wood, -4.9, 0.2, 3.1)
    roundBox(g, 2.2, 0.28, 2.7, 0.08, p.paper, -4.9, 0.5, 3.1)
    roundBox(g, 0.85, 0.16, 0.45, 0.06, p.paper, -5.3, 0.68, 2.0)
    roundBox(g, 0.85, 0.16, 0.45, 0.06, p.paper, -4.5, 0.68, 2.0)
    roundBox(g, 2.2, 0.14, 1.5, 0.06, p.fabric, -4.9, 0.68, 3.7)
    roundBox(g, 1.6, 0.32, 0.5, 0.06, p.fabricLight, -4.9, 0.26, 1.25)
    roundBox(g, 0.55, 0.5, 0.5, 0.05, p.woodLight, -3.45, 0.25, 4.35)
    sphere(g, 0.15, '#ffe2b8', -3.45, 0.62, 4.35, { glow: true, detail: 10 })
    box(g, 0.5, 2.2, 0.64, p.wood, -6.15, 1.1, 0.4)
    box(g, 0.02, 1.9, 0.02, p.darkMetal, -5.83, 1.1, 0.4)
    box(g, 0.04, 0.12, 0.04, p.metal, -5.8, 1.2, 0.15)
    box(g, 0.04, 0.12, 0.04, p.metal, -5.8, 1.2, 0.65)
    addObstacle(-6.1, -3.7, 1.6, 4.6)
    addObstacle(-3.75, -3.15, 4.08, 4.62)
    addObstacle(-6.5, -5.85, 0.05, 0.75)

    // 冰箱
    roundBox(g, 0.9, 1.9, 0.8, 0.08, p.metal, 6.0, 0.95, 1.1)
    box(g, 0.04, 1.6, 0.02, p.darkMetal, 5.58, 0.95, 1.1)
    box(g, 0.03, 0.5, 0.04, p.paper, 5.55, 1.2, 0.8)
    addObstacle(5.52, 6.48, 0.68, 1.52)

    // 书架、落地灯、植物、鱼缸与猫窝
    buildShelf(g, -6.1, -1.2, 2.4, 2.2, 'z')
    addObstacle(-6.5, -5.7, -2.45, 0.05)
    cylinder(g, 0.04, 0.04, 1.6, 8, p.metal, -5.9, 0.8, -3.2)
    sphere(g, 0.22, '#ffe2b8', -5.9, 1.68, -3.2, { glow: true, detail: 12 })
    addObstacle(-6.1, -5.7, -3.4, -3.0)
    buildPlant(g, -6.0, 4.5, 0.85)
    buildPlant(g, 6.0, -4.4, 1.0)
    buildPlant(g, 5.85, 0.15, 0.7)
    buildPlant(g, -6.0, 3.2, 0.6)
    box(g, 0.9, 0.7, 0.5, p.glass, 6.0, 0.45, -1.6, { opacity: 0.35, cast: false })
    box(g, 0.8, 0.06, 0.4, p.glass, 6.0, 0.72, -1.6, { opacity: 0.6, glow: true, cast: false })
    box(g, 0.1, 0.06, 0.04, '#f2a65a', 5.9, 0.5, -1.55)
    box(g, 0.1, 0.06, 0.04, '#7db3ff', 6.12, 0.42, -1.65)
    addObstacle(5.55, 6.45, -1.9, -1.3)
    cylinder(g, 0.44, 0.4, 0.16, 18, p.fabric, -4.5, 0.12, -1.0)
    cylinder(g, 0.34, 0.32, 0.08, 16, p.fabricLight, -4.5, 0.18, -1.0)
    sphere(g, 0.16, p.skin, -4.5, 0.3, -1.0, { detail: 12 })
    sphere(g, 0.12, p.skin, -4.5, 0.42, -1.0, { detail: 12 })
    cone(g, 0.05, 0.1, p.hair, -4.56, 0.52, -1.0, { segments: 4 })
    cone(g, 0.05, 0.1, p.hair, -4.44, 0.52, -1.0, { segments: 4 })
    cylinder(g, 0.03, 0.02, 0.3, 6, p.hair, -4.34, 0.32, -0.9, { rotation: [0.4, 0, -0.8] })
    addObstacle(-4.95, -4.05, -1.45, -0.55)

    // 照片墙与壁钟
    box(g, 0.5, 0.4, 0.04, p.paper, 4.6, 2.0, -4.9)
    box(g, 0.36, 0.3, 0.04, p.paper, 5.3, 1.85, -4.9)
    box(g, 0.3, 0.24, 0.04, p.paper, 4.9, 1.45, -4.9)
    cylinder(g, 0.36, 0.36, 0.05, 24, p.paper, -6.4, 2.1, 2.6, { rotation: [0, 0, Math.PI / 2] })
    box(g, 0.02, 0.24, 0.02, p.darkMetal, -6.37, 2.1, 2.6)
    box(g, 0.02, 0.16, 0.02, p.darkMetal, -6.37, 2.1, 2.66, { rotation: [0.7, 0, 0] })

    // 窗外草地、栅栏、路灯、邮箱与花坛
    plane(g, 6.0, 3.4, p.lawn, 0, 0.02, 6.8, { cast: false })
    plane(g, 1.4, 3.4, p.path, 0, 0.025, 6.8, { cast: false })
    box(g, 2.2, 0.12, 0.5, p.stone, 0, 0.06, 5.3)
    for (let x = -6; x <= 6; x += 0.8) {
      box(g, 0.08, 0.8, 0.08, p.wood, x, 0.4, 7.1)
    }
    box(g, 12.2, 0.08, 0.06, p.wood, 0, 0.62, 7.1)
    box(g, 12.2, 0.08, 0.06, p.wood, 0, 0.3, 7.1)
    cylinder(g, 0.04, 0.04, 1.6, 8, p.metal, -1.5, 0.8, 6.2)
    sphere(g, 0.14, '#ffe2b8', -1.5, 1.7, 6.2, { glow: true, detail: 10 })
    cylinder(g, 0.05, 0.05, 0.8, 8, p.wood, 2.0, 0.4, 6.0)
    box(g, 0.3, 0.24, 0.22, p.metal, 2.0, 0.92, 6.0)
    box(g, 0.3, 0.04, 0.22, '#d97a7a', 2.0, 1.05, 6.0)
    box(g, 1.6, 0.22, 0.5, p.woodLight, 4.0, 0.11, 6.1)
    for (let i = 0; i < 5; i++) {
      sphere(g, 0.09, i % 2 ? '#f6c177' : '#e27d9c', 3.5 + i * 0.24, 0.3, 6.1, { detail: 8 })
    }
    buildTree(g, -3.4, 7.4, 1.0)
    buildTree(g, 3.6, 7.8, 0.85)

    // 家的居民：角色卡有几张就摆几个小人，可走动
    walkArea = { minX: -2.2, maxX: 3.2, minZ: -0.4, maxZ: 3.0 }
    const residents = Array.isArray(state.avatars) ? state.avatars : []
    labels = []
    residents.forEach((person, index) => {
      const x = -1.4 + (index % 5) * 1.0
      const z = 0.1 + Math.min(3, Math.floor(index / 5)) * 0.9
      const node = addPerson(g, {
        x: x,
        z: z,
        facing: Math.atan2(-x, -z),
        accent: new THREE.Color(person.color || state.accent || '#0D84FF'),
        style: person.style || 'standard',
        model: person.model,
        scale: 0.96 + (index % 3) * 0.03,
      })
      const label = {
        id: 'resident-' + (person.id || index),
        title: person.name || `角色 ${index + 1}`,
        at: { x: node.position.x, y: 2.0, z: node.position.z },
      }
      node.userData.label = label
      labels.push(label)
    })
    return frames.home
  }

  function buildStudio() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -4.45, maxX: 4.45, minZ: 0.55, maxZ: 3.55 }
    addObstacle(-3.6, 3.6, -2.72, -1.28)
    addObstacle(-1.12, 1.92, -0.52, 2.52)
    addObstacle(-4.75, -2.55, 1.5, 2.5)
    addObstacle(2.0, 4.2, -1.3, 0.72)
    addObstacle(2.05, 3.35, 1.0, 1.8)
    addObstacle(1.62, 2.08, 2.87, 3.33)
    addObstacle(4.05, 4.62, 2.62, 3.3)
    addObstacle(4.3, 4.8, 1.28, 1.92)
    buildRoom(g, { width: 10, depth: 8, height: 2.8, board: true, shelf: true })

    // 木地板、地毯与吊灯
    for (let i = 0; i < 10; i++) {
      plane(g, 9.7, 0.78, i % 2 ? p.floor : p.floorDark, 0, 0.075, -3.6 + i * 0.8, { cast: false })
    }
    buildRug(g, 0.4, 1.0, 4.8, 3.4, p.fabricLight, { border: accentColor() })
    buildRug(g, -3.5, 2.9, 2.2, 1.2, p.fabric, { opacity: 0.9 })
    buildWallLamp(g, 2.4, 2.18, -3.9)
    buildWallLamp(g, -3.7, 2.18, -3.9)

    // 后墙装饰、时钟与侧墙相框
    buildWallFrame(g, 3.3, 1.95, -3.94, 0.72, 0.54, { color: '#d7e6ff' })
    buildWallFrame(g, 4.2, 2.15, -3.94, 0.5, 0.62, { color: '#ffe6c7' })
    buildWallFrame(g, -4.92, 1.9, 2.4, 0.85, 0.65, { rotation: Math.PI / 2 })
    buildWallFrame(g, -4.92, 1.9, 3.15, 0.55, 0.55, { rotation: Math.PI / 2, color: '#ffe0e6' })
    buildWallClock(g, 4.55, 2.15, -3.94)

    // 饮水机、回收箱与沙发边灯
    buildWaterCooler(g, 4.55, 1.6)
    cylinder(g, 0.26, 0.24, 0.5, 12, p.metal, -4.55, 0.25, 3.5, { opacity: 1 })
    cylinder(g, 0.29, 0.27, 0.05, 12, p.darkMetal, -4.55, 0.52, 3.5)
    cylinder(g, 0.035, 0.045, 1.5, 8, p.darkMetal, -4.55, 0.75, 0.65)
    sphere(g, 0.17, '#ffe2b8', -4.55, 1.56, 0.65, { glow: true, detail: 12 })
    addObstacleAt(-4.55, 0.65, 0.42, 0.42)

    // 后墙窗与工作台
    const deskXs = [-2.7, -0.9, 0.9, 2.7]
    deskXs.forEach((x, index) => {
      buildDesk(g, x, -2.3, {
        width: 1.55,
        monitor: true,
        screenColor: index === 0 ? accentColor() : undefined,
      })
      if (index === 0) {
        box(g, 0.34, 0.06, 0.25, p.darkMetal, x - 0.42, 0.84, -2.15)
        box(g, 0.16, 0.1, 0.16, p.paper, x + 0.42, 0.86, -2.2)
      }
    })

    // 键盘、鼠标、杯子与便签
    deskXs.forEach((x, index) => {
      box(g, 0.34, 0.03, 0.12, p.darkMetal, x, 0.82, -2.02)
      box(g, 0.07, 0.03, 0.1, p.paper, x + 0.24, 0.82, -2.0)
      if (index !== 2) {
        cylinder(g, 0.045, 0.04, 0.1, 10, p.paper, x - 0.34, 0.84, -2.05)
      }
      box(g, 0.1, 0.02, 0.1, index === 1 ? '#ffe08a' : '#cfe3ff', x + 0.5, 0.8, -2.3, {
        rotation: [0, 0.3, 0],
        cast: false,
      })
    })

    // 中央会议桌
    cylinder(g, 1.0, 1.0, 0.1, 30, p.deskTop, 0.4, 0.76, 1.0)
    cylinder(g, 0.16, 0.26, 0.74, 14, p.darkMetal, 0.4, 0.4, 1.0)
    for (let i = 0; i < 6; i++) {
      const angle = (i / 6) * Math.PI * 2 + Math.PI / 6
      const cx = 0.4 + Math.cos(angle) * 1.42
      const cz = 1.0 + Math.sin(angle) * 1.42
      roundBox(g, 0.42, 0.08, 0.4, 0.05, p.fabric, cx, 0.47, cz)
      roundBox(g, 0.4, 0.42, 0.07, 0.05, p.fabricLight, cx + Math.cos(angle) * 0.2, 0.7, cz + Math.sin(angle) * 0.2)
      box(g, 0.06, 0.44, 0.06, p.metal, cx, 0.23, cz)
    }
    box(g, 0.32, 0.05, 0.26, p.paper, 0.15, 0.82, 0.95)
    box(g, 0.28, 0.04, 0.22, p.paper, 0.66, 0.82, 1.12)

    // 沙发与边几
    buildSofa(g, -3.65, 2.0, 2.1, 0)
    roundBox(g, 0.85, 0.08, 0.55, 0.06, p.woodLight, -3.45, 0.36, 0.75)
    box(g, 0.06, 0.34, 0.06, p.metal, -3.8, 0.17, 0.75)
    box(g, 0.06, 0.34, 0.06, p.metal, -3.1, 0.17, 0.75)
    const cup = cylinder(g, 0.06, 0.05, 0.12, 12, p.paper, -3.55, 0.46, 0.75)
    cup.rotation.x = 0.2
    box(g, 0.34, 0.03, 0.24, p.paper, -3.45, 0.42, 0.75, { cast: false })

    // 玻璃沙盒与控制台
    const glassMat = surface(state.dark ? '#7fb5cf' : '#bfe3ea', {
      transparent: true,
      opacity: 0.28,
      roughness: 0.1,
      metalness: 0.1,
      doubleSide: true,
    })
    box(g, 2.1, 2.15, 0.05, glassMat, 3.1, 1.1, -1.2, { cast: false })
    box(g, 2.1, 2.15, 0.05, glassMat, 3.1, 1.1, 0.65, { cast: false })
    box(g, 0.05, 2.15, 1.9, glassMat, 2.06, 1.1, -0.28, { cast: false })
    box(g, 0.05, 2.15, 1.9, glassMat, 4.14, 1.1, -0.28, { cast: false })
    box(g, 2.2, 0.09, 2.05, p.metal, 3.1, 2.17, -0.28)
    box(g, 0.09, 0.09, 1.9, p.metal, 2.06, 2.17, -0.28)
    for (let i = 0; i < 3; i++) {
      box(g, 0.5, 0.06, 0.6, p.darkMetal, 2.8, 0.9 + i * 0.52, -0.55)
      const led = sphere(g, 0.035, i === 0 ? accentColor() : '#7fe0c3', 2.8, 0.94 + i * 0.52, -0.25, {
        glow: true,
        detail: 8,
      })
      led.userData.led = true
    }
    buildDesk(g, 2.7, 1.4, { width: 1.2, monitor: true, screenColor: accentColor(), chair: false })

    // 植物与杂物
    buildPlant(g, -4.25, -3.1, 1.05)
    buildPlant(g, 4.3, 3.0, 1.15)
    buildPlant(g, 1.85, 3.1, 0.8)
    box(g, 0.45, 0.32, 0.3, p.metal, -4.35, 0.2, 2.9)
    sphere(g, 0.09, p.leafLight, -4.35, 0.46, 2.9, { detail: 10 })

    // 地面光带
    plane(g, 0.12, 6.6, accentColor(), -4.25, 0.075, 0.4, { glow: true, opacity: 0.45, cast: false })

    // 人物
    const characterNode = addPerson(g, {
      x: 0.4,
      z: 2.9,
      facing: Math.PI,
      accent: accentColor(),
      scale: 1.02,
    })
    addPerson(g, {
      x: -2.2,
      z: 1.0,
      facing: Math.PI * 0.72,
      accent: new THREE.Color(state.dark ? '#7bd6c0' : '#4aa88f'),
      trousers: p.darkMetal,
      scale: 0.96,
    })

    labels = [
      {
        id: 'character',
        title: state.stats.character || 'Agent',
        detail: state.stats.running ? '正在生成' : '空闲',
        at: { x: 0.4, y: 2.2, z: 2.35 },
        tone: state.stats.running ? 'busy' : 'calm',
      },
      {
        id: 'desk',
        title: '工作台',
        detail: state.stats.workspace || '未选择工作区',
        at: { x: -0.9, y: 1.75, z: -2.3 },
      },
      {
        id: 'table',
        title: '会话',
        detail: `${state.stats.conversations ?? 0} 段 · ${state.stats.characters ?? 0} 位角色`,
        at: { x: 0.4, y: 1.6, z: 1.0 },
      },
      {
        id: 'sandbox',
        title: '工具沙盒',
        detail: 'Shell · 已授权',
        at: { x: 3.1, y: 2.5, z: -0.28 },
        tone: 'busy',
      },
    ]
    if (characterNode && labels[0]) characterNode.userData.label = labels[0]
    return frames.studio
  }

  function campusBuilding(g, options) {
    const p = palette()
    const b = new THREE.Group()
    b.position.set(options.x, 0, options.z)
    if (options.rotation) b.rotation.y = options.rotation
    g.add(b)
    const w = options.width
    const d = options.depth
    const h = options.height
    roundBox(b, w, h, d, 0.14, options.color ?? p.wall, 0, h / 2, 0, {
      pick: options.pick,
    })
    roundBox(b, w + 0.3, 0.22, d + 0.3, 0.08, p.wallLow, 0, 0.11, 0, { pick: options.pick })
    if (options.roof !== false) {
      roundBox(b, w + 0.25, 0.18, d + 0.25, 0.08, options.roofColor ?? p.stone, 0, h + 0.09, 0)
      if (options.roofUnit !== false) {
        roundBox(b, w * 0.26, 0.3, d * 0.24, 0.04, p.metal, w * 0.2, h + 0.32, -d * 0.12)
        cylinder(b, 0.05, 0.05, 0.36, 8, p.darkMetal, -w * 0.22, h + 0.36, d * 0.14)
        cylinder(b, 0.12, 0.12, 0.05, 10, p.stone, -w * 0.22, h + 0.55, d * 0.14)
      }
    }
    const levels = options.floors ?? 2
    // 楼层腰线，让盒子有层次
    for (let floor = 1; floor < levels; floor++) {
      const y = 0.8 + floor * ((h - 0.8) / levels) - 0.34
      roundBox(b, w + 0.18, 0.1, d + 0.18, 0.04, p.wallLow, 0, y, 0, { pick: options.pick })
    }
    const floors = options.floors ?? 2
    for (let floor = 0; floor < floors; floor++) {
      const y = 0.8 + floor * ((h - 0.8) / Math.max(1, floors))
      for (let i = 0; i < Math.max(2, Math.floor(w / 0.9)); i++) {
        const x = -w / 2 + 0.65 + i * ((w - 1.3) / Math.max(1, Math.floor(w / 0.9) - 1))
        box(b, 0.38, 0.28, 0.04, p.glass, x, y, d / 2 + 0.02, {
          glow: options.lit,
          emissiveIntensity: options.lit ? 0.5 : 0.2,
          pick: options.pick,
          cast: false,
        })
      }
    }
    if (options.columns) {
      for (let i = 0; i < 4; i++) {
        const x = -w / 2 + 0.45 + i * ((w - 0.9) / 3)
        cylinder(b, 0.1, 0.1, h * 0.8, 10, p.paper, x, h * 0.4, d / 2 + 0.18, { pick: options.pick })
      }
      roundBox(b, w + 0.3, 0.16, 0.45, 0.06, p.paper, 0, h * 0.8 + 0.08, d / 2 + 0.18, {
        pick: options.pick,
      })
    }
    if (options.awning) {
      roundBox(b, w * 0.55, 0.12, 1.1, 0.05, options.awning, 0, 1.9, d / 2 + 0.5)
      for (const dx of [-w * 0.22, w * 0.22]) {
        cylinder(b, 0.035, 0.035, 1.8, 8, p.metal, dx, 0.9, d / 2 + 0.95)
      }
    }
    if (options.door !== false) {
      box(b, 0.5, 0.9, 0.04, p.darkMetal, 0, 0.45, d / 2 + 0.03, { pick: options.pick })
      box(b, 0.9, 0.12, 0.6, p.stone, 0, 0.06, d / 2 + 0.36, { pick: options.pick })
      box(b, 0.7, 0.12, 0.4, p.stone, 0, 0.18, d / 2 + 0.24, { pick: options.pick })
      roundBox(b, 1.1, 0.12, 0.5, 0.04, options.awning ?? p.wallLow, 0, 1.5, d / 2 + 0.24, {
        pick: options.pick,
      })
      sphere(b, 0.07, '#ffe2b8', 0.55, 1.24, d / 2 + 0.1, { glow: true, detail: 8 })
      box(b, w * 0.36, 0.22, 0.04, options.awning ?? accentColor(), 0, h - 0.42, d / 2 + 0.04, {
        glow: true,
        emissiveIntensity: 0.3,
        pick: options.pick,
        cast: false,
      })
    }
    return b
  }

  function buildCampus() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -4.0, maxX: 4.0, minZ: -2.35, maxZ: 4.85 }
    addObstacle(-2.7, 2.7, -6.4, -2.8)
    addObstacle(-10.9, -6.3, 3.3, 7.1)
    addObstacle(-10.7, -6.5, -4.9, -1.9)
    addObstacle(6.3, 11.7, -7.5, -2.9)
    addObstacle(6.6, 9.8, 3.9, 6.5)
    addObstacle(-2.1, 2.1, 5.3, 8.3)

    roundBox(g, 27, 0.32, 19, 0.4, p.lawn, 0, -0.16, 0, { receive: true })
    plane(g, 26.2, 18.2, p.lawn, 0, 0.01, 0, { opacity: 0, cast: false })
    // 草地上的浅色铺装与环路
    plane(g, 26.4, 1.7, p.path, 0, 0.025, 0.5, { cast: false })
    plane(g, 1.7, 18.2, p.path, 0, 0.025, 1.0, { cast: false })
    cylinder(g, 2.7, 2.7, 0.05, 30, p.path, 0, 0.03, 0.6)
    buildFountain(g, 0, 0.6, { radius: 1.35 })
    line(g, [
      new THREE.Vector3(-11, 0.06, 0.5),
      new THREE.Vector3(-2.6, 0.06, 0.5),
    ], p.paper, { opacity: 0.35 })
    line(g, [
      new THREE.Vector3(2.6, 0.06, 0.5),
      new THREE.Vector3(11, 0.06, 0.5),
    ], p.paper, { opacity: 0.35 })

    const spots = {
      studio: { x: 0, z: -4.6 },
      home: { x: -8.6, z: 5.2 },
      library: { x: -8.6, z: -3.4 },
      site: { x: 9.0, z: -5.2 },
      duty: { x: 8.2, z: 5.2 },
      desk: { x: 0, z: 6.8 },
    }

    // 工作室：三层现代楼，带玻璃幕墙
    const studio = campusBuilding(g, {
      x: spots.studio.x,
      z: spots.studio.z,
      width: 5.4,
      depth: 3.6,
      height: 3.3,
      floors: 3,
      color: state.dark ? '#3a465c' : '#f7f2e8',
      roofColor: p.stone,
      lit: true,
      pick: 'studio',
      door: false,
    })
    box(studio, 5.0, 0.1, 0.06, accentColor(), 0, 1.0, 1.84, { glow: true, pick: 'studio' })
    box(studio, 5.0, 0.1, 0.06, accentColor(), 0, 2.0, 1.84, { glow: true, pick: 'studio' })
    roundBox(studio, 1.1, 0.24, 0.6, 0.05, p.metal, 0, 3.5, 0.2)
    const antenna = cylinder(studio, 0.03, 0.03, 1.0, 8, p.metal, 0, 4.1, 0.2)
    const beacon = sphere(studio, 0.07, accentColor(), 0, 4.62, 0.2, { glow: true, detail: 8 })
    beacon.userData.beacon = true
    animateFns.push({ kind: 'beacon', mesh: beacon, light: antenna })

    // 家：坡屋顶小屋，可点击进入
    const home = campusBuilding(g, {
      x: spots.home.x,
      z: spots.home.z,
      width: 4.6,
      depth: 3.8,
      height: 2.1,
      floors: 1,
      color: state.dark ? '#4a4038' : '#f3e6d2',
      lit: true,
      pick: 'home',
    })
    const homeRoof = cone(home, 3.2, 1.4, state.dark ? '#5c4436' : '#b96f4a', 0, 2.8, 0, {
      segments: 4,
      rotation: [0, Math.PI / 4, 0],
      pick: 'home',
    })
    homeRoof.scale.set(1.35, 1, 1.1)
    box(home, 0.28, 0.7, 0.28, p.stone, 1.2, 3.1, -0.4, { pick: 'home' })

    // 图书馆：柱廊
    const library = campusBuilding(g, {
      x: spots.library.x,
      z: spots.library.z,
      width: 4.2,
      depth: 3.0,
      height: 2.6,
      floors: 2,
      color: state.dark ? '#4a4a52' : '#f4eee2',
      columns: true,
      pick: 'library',
    })
    box(library, 1.6, 0.36, 0.06, p.woodLight, 0, 2.75, 1.52)

    // 工地：围栏、塔吊与半建成的楼
    const site = new THREE.Group()
    site.position.set(spots.site.x, 0, spots.site.z)
    g.add(site)
    roundBox(site, 5.4, 0.05, 4.6, 0.1, p.dirt, 0, 0.04, 0, { pick: 'site' })
    for (let i = 0; i < 15; i++) {
      const x = -2.4 + (i % 5) * 1.2
      const z = -1.9 + Math.floor(i / 5) * 1.2
      box(site, 0.08, 0.8, 0.08, p.metal, x, 0.4, z, { pick: 'site' })
    }
    const half = roundBox(site, 3.0, 1.7, 2.1, 0.08, state.dark ? '#5c6878' : '#c9c3b8', 0.4, 0.85, -0.2, {
      pick: 'site',
    })
    for (let floor = 0; floor < 2; floor++) {
      for (let i = 0; i < 4; i++) {
        box(half, 0.34, 0.24, 0.03, p.glass, -1.1 + i * 0.72, 0.55 + floor * 0.75, 1.07, {
          glow: true,
          pick: 'site',
          cast: false,
        })
      }
    }
    // 塔吊
    const crane = new THREE.Group()
    crane.position.set(3.8, 0, 3.2)
    site.add(crane)
    for (let i = 0; i < 4; i++) {
      const x = i < 2 ? -0.28 : 0.28
      const z = i % 2 ? -0.28 : 0.28
      box(crane, 0.06, 4.8, 0.06, '#e0a63a', x, 2.4, z, { pick: 'site' })
    }
    for (let y = 0.4; y < 4.8; y += 0.7) {
      box(crane, 0.6, 0.05, 0.05, '#e0a63a', 0, y, 0.28, { pick: 'site' })
      box(crane, 0.05, 0.7, 0.05, '#e0a63a', 0.28, y + 0.35, 0.28, { rotation: [0, 0, 0.55], pick: 'site' })
    }
    const jib = box(crane, 6.0, 0.16, 0.16, '#e0a63a', -2.2, 4.85, 0, { pick: 'site' })
    jib.rotation.z = -0.03
    box(crane, 1.1, 0.7, 0.7, '#e0a63a', 0.8, 4.6, 0, { pick: 'site' })
    cylinder(crane, 0.035, 0.035, 2.9, 6, p.darkMetal, -4.2, 3.4, 0)
    box(crane, 0.22, 0.28, 0.22, p.metal, -4.2, 1.9, 0, { pick: 'site' })
    // 材料堆
    for (let i = 0; i < 4; i++) {
      box(site, 0.8, 0.16, 0.4, p.woodLight, -1.8 + i * 0.9, 0.16 + (i % 2) * 0.16, 1.6, { pick: 'site' })
    }
    for (let i = 0; i < 3; i++) {
      cone(site, 0.16, 0.36, '#f07b3f', -2.2 + i * 0.5, 0.18, 1.0, { pick: 'site', segments: 8 })
    }
    addPerson(g, {
      x: -1.6,
      z: 2.4,
      facing: -0.4,
      accent: new THREE.Color('#4aa88f'),
      scale: 0.94,
    })
    addPerson(g, {
      x: 3.1,
      z: -1.2,
      facing: Math.PI * 0.72,
      accent: '#e0a63a',
      helmet: true,
      scale: 0.93,
    })

    // 值班室：带钟楼的小屋
    const duty = campusBuilding(g, {
      x: spots.duty.x,
      z: spots.duty.z,
      width: 3.2,
      depth: 2.6,
      height: 2.3,
      floors: 2,
      color: state.dark ? '#3e4a5e' : '#f2ece0',
      roofColor: '#b9c4c9',
      lit: state.stats.apiEnabled,
      pick: 'duty',
    })
    box(duty, 1.4, 1.4, 1.4, state.dark ? '#4a5568' : '#e7dfd2', 0, 3.0, 0, { pick: 'duty' })
    cylinder(duty, 0.48, 0.48, 0.1, 24, p.paper, 0, 3.1, 0.72, {
      rotation: [Math.PI / 2, 0, 0],
      pick: 'duty',
    })
    cylinder(duty, 0.03, 0.03, 0.35, 8, p.darkMetal, 0, 3.1, 0.8, { pick: 'duty' })
    cone(duty, 0.55, 0.55, '#b9c4c9', 0, 4.3, 0, { pick: 'duty', segments: 4 })

    // 前台：带雨棚的小楼
    const desk = campusBuilding(g, {
      x: spots.desk.x,
      z: spots.desk.z,
      width: 4.0,
      depth: 2.8,
      height: 2.0,
      floors: 1,
      color: state.dark ? '#3d495c' : '#f6f0e5',
      roofColor: p.stone,
      awning: state.dark ? '#2f4a5c' : '#2c7a7b',
      lit: true,
      pick: 'desk',
    })
    box(desk, 2.0, 0.1, 0.06, accentColor(), 0, 1.4, 1.43, { glow: true, pick: 'desk' })
    cylinder(desk, 0.18, 0.18, 1.5, 10, p.metal, -1.7, 0.75, 1.1, { pick: 'desk' })
    sphere(desk, 0.2, accentColor(), -1.7, 1.55, 1.1, { glow: true, pick: 'desk' })

    // 树、石凳、路灯
    const trees = [
      [-11.4, -6.4], [-11.5, 1.2], [-10.8, 8.0], [-6.2, 7.4],
      [-3.0, -7.4], [3.1, -7.2], [6.5, -6.0], [11.3, -6.8],
      [11.8, 1.2], [11.6, 8.2], [5.1, 8.4], [3.2, 3.2], [-5.2, 4.6],
    ]
    trees.forEach(([x, z], index) => buildTree(g, x, z, 0.82 + (index % 3) * 0.08))
    for (const [x, z] of [[-4.8, 2.6], [4.8, 2.9], [-5.0, 5.8], [5.4, 6.4]]) {
      buildPlant(g, x, z, 0.45)
    }
    // 广场座椅、花坛与路灯
    for (let i = 0; i < 4; i++) {
      const angle = Math.PI / 4 + i * Math.PI / 2
      const bx = Math.cos(angle) * 3.45
      const bz = 0.6 + Math.sin(angle) * 3.45
      buildBench(g, bx, bz, 1.35, { facing: Math.atan2(-bx, 0.6 - bz) })
    }
    buildFlowerBed(g, -4.5, 2.4, 1.2, 0.9)
    buildFlowerBed(g, 4.5, 2.4, 1.2, 0.9)
    buildFlowerBed(g, -1.7, 5.1, 1.5, 0.8)
    buildFlowerBed(g, 1.7, 5.1, 1.5, 0.8)
    buildHedge(g, -4.7, -0.6, 1.0, 2.3, { height: 0.55 })
    buildHedge(g, 4.7, -0.6, 1.0, 2.3, { height: 0.55 })
    buildSignpost(g, -1.5, 3.7, accentColor(), {})
    for (const [x, z] of [[-3.4, 1.5], [3.4, 1.5], [-3.6, 4.2], [3.8, 4.2]]) {
      buildLampPost(g, x, z, { height: 2.6 })
    }
    addObstacleAt(3.2, 3.2, 0.7, 0.7)
    // 停在前台旁的配送小车
    const cart = new THREE.Group()
    cart.position.set(-2.8, 0, 5.2)
    g.add(cart)
    roundBox(cart, 1.0, 0.16, 0.55, 0.05, p.deskTop, 0, 0.25, 0)
    for (const dx of [-0.32, 0.32]) {
      cylinder(cart, 0.13, 0.13, 0.08, 12, p.darkMetal, dx, 0.14, 0.2, { rotation: [Math.PI / 2, 0, 0] })
      cylinder(cart, 0.13, 0.13, 0.08, 12, p.darkMetal, dx, 0.14, -0.2, { rotation: [Math.PI / 2, 0, 0] })
    }
    box(cart, 0.3, 0.25, 0.3, '#c8843d', 0, 0.45, 0)

    labels = [
      {
        id: 'studio',
        title: '工作室',
        detail: state.stats.running ? '正在工作' : '空闲',
        at: { x: 0, y: 5.1, z: spots.studio.z },
        tone: state.stats.running ? 'busy' : 'calm',
        pick: 'studio',
      },
      {
        id: 'home',
        title: '家',
        detail: `${(state.avatars && state.avatars.length) || 0} 位居民`,
        at: { x: spots.home.x, y: 3.9, z: spots.home.z },
        pick: 'home',
      },
      {
        id: 'library',
        title: '图书馆',
        detail: `${state.stats.knowledge ?? 0} 份知识 · ${state.stats.memories ?? 0} 条记忆`,
        at: { x: spots.library.x, y: 3.4, z: spots.library.z },
        pick: 'library',
      },
      {
        id: 'site',
        title: '工地',
        detail: `${state.stats.files ?? 0} 个文件`,
        at: { x: spots.site.x, y: 4.8, z: spots.site.z },
        tone: state.stats.files > 0 ? 'busy' : 'calm',
        pick: 'site',
      },
      {
        id: 'duty',
        title: '值班室',
        detail: state.stats.apiEnabled || state.stats.mcpEnabled ? '服务运行中' : '没有排定的运行',
        at: { x: spots.duty.x, y: 5.0, z: spots.duty.z },
        tone: state.stats.apiEnabled ? 'busy' : 'calm',
        pick: 'duty',
      },
      {
        id: 'desk',
        title: '前台',
        detail: '还没有配对的电脑',
        at: { x: spots.desk.x, y: 3.1, z: spots.desk.z },
        pick: 'desk',
      },
    ]
    return frames.campus
  }

  function buildLibrary() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -2.0, maxX: 4.6, minZ: -2.4, maxZ: 3.35 }
    addObstacle(-5.45, 1.65, -2.9, -2.4)
    addObstacle(-2.2, -0.4, 0.6, 2.15)
    addObstacle(1.2, 3.0, 1.4, 2.9)
    addObstacle(-4.85, -4.35, 1.2, 3.6)
    addObstacle(4.25, 4.75, -2.05, 1.05)
    addObstacle(1.45, 4.15, -2.55, -1.65)
    addObstacle(1.0, 2.0, -1.6, -1.0)
    addObstacle(-2.45, -1.75, -0.95, -0.3)
    addObstacle(3.9, 4.5, 2.0, 2.6)
    buildRoom(g, { width: 11, depth: 8.6, height: 3.3, windowWidth: 3.4, windowX: 0.8, windowY: 1.9 })

    // 木地板、阅读区地毯与吊灯
    for (let i = 0; i < 11; i++) {
      plane(g, 10.7, 0.75, i % 2 ? p.floor : p.floorDark, 0, 0.075, -3.8 + i * 0.76, { cast: false })
    }
    buildRug(g, 0.45, 1.5, 3.1, 3.3, p.fabricLight, { border: accentColor() })
    buildRug(g, 3.6, 0.3, 1.7, 4.2, p.fabric, { opacity: 0.9 })
    buildWallLamp(g, -3.2, 2.9, -4.12)
    buildWallLamp(g, 3.5, 2.9, -4.12)

    // 窗帘与墙面装饰
    roundBox(g, 0.42, 2.3, 0.12, 0.05, p.fabric, -1.02, 1.9, -4.14)
    roundBox(g, 0.42, 2.3, 0.12, 0.05, p.fabric, 2.62, 1.9, -4.14)
    cylinder(g, 0.02, 0.02, 4.0, 6, p.metal, 0.8, 3.05, -4.14, { rotation: [0, 0, Math.PI / 2] })
    buildWallFrame(g, -5.42, 2.3, -2.6, 0.9, 0.7, { rotation: Math.PI / 2, color: '#e7f0ff' })
    buildWallFrame(g, -5.42, 2.3, -1.4, 0.6, 0.8, { rotation: Math.PI / 2, color: '#ffe9d6' })
    buildWallClock(g, 3.9, 2.75, -4.22)
    // 指引牌与绿植
    buildSignpost(g, 4.05, -2.75, accentColor(), {})
    addObstacleAt(3.9, 3.0, 0.95, 0.95)
    buildPlant(g, -4.55, -0.2, 0.8)

    // 书架区：三排
    for (const x of [-4.0, -1.9, 0.2]) {
      buildShelf(g, x, -2.65, 2.9, 2.5, 'x')
    }
    buildShelf(g, -4.6, 2.4, 2.4, 2.3, 'z')
    buildShelf(g, 4.5, -0.5, 3.0, 2.4, 'z')

    // 阅读桌
    for (const [x, z] of [[-1.3, 1.1], [2.1, 1.9]]) {
      roundBox(g, 1.7, 0.1, 0.9, 0.05, p.woodLight, x, 0.78, z)
      for (const dx of [-0.7, 0.7]) {
        box(g, 0.08, 0.76, 0.08, p.darkMetal, x + dx, 0.39, z - 0.32)
        box(g, 0.08, 0.76, 0.08, p.darkMetal, x + dx, 0.39, z + 0.32)
      }
      for (const dx of [-0.5, 0.5]) {
        roundBox(g, 0.4, 0.07, 0.38, 0.05, p.fabric, x + dx, 0.46, z + 0.8)
      }
      for (const dx of [-0.28, 0.3]) {
        box(g, 0.28, 0.035, 0.2, p.paper, x + dx, 0.85, z + 0.1)
      }
      // 绿色阅读灯
      cylinder(g, 0.035, 0.04, 0.45, 8, p.darkMetal, x + 0.55, 1.0, z - 0.25)
      const shade = cone(g, 0.16, 0.16, '#3f7a5b', x + 0.55, 1.23, z - 0.25, { segments: 12, glow: true })
      shade.rotation.z = 0.4
      sphere(g, 0.06, '#ffe2b8', x + 0.62, 1.15, z - 0.25, { glow: true, detail: 8 })
      // 椅背与桌上书堆
      box(g, 0.4, 0.34, 0.05, p.fabricLight, x - 0.5, 0.62, z + 0.98)
      box(g, 0.4, 0.34, 0.05, p.fabricLight, x + 0.5, 0.62, z + 0.98)
      box(g, 0.22, 0.05, 0.3, '#7db3ff', x - 0.06, 0.85, z + 0.2)
      box(g, 0.2, 0.04, 0.26, '#f6c177', x - 0.04, 0.89, z + 0.22, { rotation: [0, 0.12, 0] })
    }

    // 窗边阅读角：扶手椅、地球仪与小几
    const chair = new THREE.Group()
    chair.position.set(3.9, 0, 3.0)
    chair.rotation.y = -0.7
    g.add(chair)
    roundBox(chair, 0.8, 0.32, 0.75, 0.1, p.fabric, 0, 0.32, 0)
    roundBox(chair, 0.8, 0.55, 0.2, 0.08, p.fabricLight, 0, 0.62, -0.3)
    roundBox(chair, 0.18, 0.36, 0.7, 0.08, p.fabricLight, -0.4, 0.5, 0)
    roundBox(chair, 0.18, 0.36, 0.7, 0.08, p.fabricLight, 0.4, 0.5, 0)
    cylinder(g, 0.03, 0.03, 0.8, 8, p.wood, 4.55, 0.4, 2.45)
    sphere(g, 0.22, '#7db3ff', 4.55, 0.92, 2.45, { detail: 14 })
    addObstacleAt(4.55, 2.45, 0.5, 0.5)

    // 借阅台、扫描仪与书车
    roundBox(g, 2.5, 0.9, 0.8, 0.08, p.wood, 2.8, 0.45, -2.1)
    roundBox(g, 2.7, 0.08, 0.95, 0.04, p.woodLight, 2.8, 0.94, -2.1)
    box(g, 0.5, 0.3, 0.38, p.darkMetal, 3.25, 1.13, -2.05)
    box(g, 0.34, 0.04, 0.24, p.glass, 3.25, 1.31, -2.05, { glow: true })
    for (let i = 0; i < 4; i++) {
      box(g, 0.38, 0.26, 0.05, ['#7db3ff', '#f6c177', '#7fe0c3', '#ff9aa2'][i], 2.25, 1.1, -2.35 + i * 0.12, {
        rotation: [0, 0, 0.08],
      })
    }
    const cart = new THREE.Group()
    cart.position.set(1.5, 0, -1.3)
    g.add(cart)
    box(cart, 0.9, 0.08, 0.55, p.metal, 0, 0.4, 0)
    box(cart, 0.9, 0.08, 0.55, p.metal, 0, 0.72, 0)
    for (const dx of [-0.38, 0.38]) {
      box(cart, 0.06, 0.72, 0.06, p.metal, dx, 0.36, -0.22)
      box(cart, 0.06, 0.72, 0.06, p.metal, dx, 0.36, 0.22)
      cylinder(cart, 0.09, 0.09, 0.05, 8, p.darkMetal, dx, 0.08, 0.25, { rotation: [Math.PI / 2, 0, 0] })
    }
    for (let i = 0; i < 4; i++) {
      box(cart, 0.16, 0.3, 0.4, ['#7db3ff', '#ff9aa2', '#7fe0c3', '#f6c177'][i], -0.3 + i * 0.2, 0.9, 0)
    }

    // 梯子与盆栽
    const ladder = new THREE.Group()
    ladder.position.set(-2.1, 0, -0.6)
    ladder.rotation.z = -0.12
    g.add(ladder)
    for (const dx of [-0.26, 0.26]) {
      box(ladder, 0.06, 2.2, 0.06, p.wood, dx, 1.1, 0)
      for (let i = 0; i < 5; i++) box(ladder, 0.58, 0.05, 0.05, p.wood, 0, 0.4 + i * 0.35, 0)
    }
    buildPlant(g, -4.8, 3.4, 1.0)
    buildPlant(g, 4.75, 3.4, 1.2)
    buildPlant(g, 4.2, 2.3, 0.7)

    addPerson(g, {
      x: 2.8,
      z: -1.35,
      facing: 0,
      accent: new THREE.Color(state.dark ? '#6fa8dc' : '#4f7f9c'),
      scale: 0.98,
    })
    addPerson(g, {
      x: -1.4,
      z: 0.15,
      facing: Math.PI,
      accent: new THREE.Color(state.dark ? '#7bd6c0' : '#4aa88f'),
      scale: 0.92,
    })

    labels = [
      {
        id: 'knowledge',
        title: '知识库',
        detail: `${state.stats.knowledge ?? 0} 份文档`,
        at: { x: -4.0, y: 3.0, z: -2.65 },
        tone: 'busy',
      },
      {
        id: 'memory',
        title: '记忆库',
        detail: `${state.stats.memories ?? 0} 条记忆`,
        at: { x: -1.9, y: 3.0, z: -2.65 },
      },
      {
        id: 'diary',
        title: '日记',
        detail: `${state.stats.diary ?? 0} 篇`,
        at: { x: 0.2, y: 3.0, z: -2.65 },
      },
      {
        id: 'desk',
        title: '借阅台',
        detail: '关键词检索 · 前两条注入',
        at: { x: 2.8, y: 1.65, z: -2.1 },
      },
    ]
    return frames.library
  }

  function buildSite() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -5.75, maxX: 5.75, minZ: -1.1, maxZ: 4.15 }
    addObstacle(-1.6, 2.8, -4.9, -1.22)
    addObstacle(4.75, 5.45, -2.45, -1.75)

    roundBox(g, 15, 0.3, 10.5, 0.35, p.dirt, 0, -0.15, 0, { receive: true })
    plane(g, 14.2, 9.8, p.dirt, 0, 0.02, 0.1, { cast: false })
    // 车辙、湿泥与碎石
    plane(g, 12.4, 0.5, state.dark ? '#4b3b2c' : '#c9ae8a', 0, 0.03, -0.7, { opacity: 0.7, cast: false })
    plane(g, 12.4, 0.5, state.dark ? '#4b3b2c' : '#c9ae8a', 0, 0.03, 3.1, { opacity: 0.55, cast: false })
    plane(g, 1.6, 1.2, p.glass, -3.2, 0.035, -0.4, { opacity: 0.3, glow: true, cast: false })
    plane(g, 1.1, 0.9, p.glass, 2.6, 0.035, 3.4, { opacity: 0.26, glow: true, cast: false })
    for (let i = 0; i < 14; i++) {
      sphere(g, 0.04 + rand() * 0.03, p.stone, -6.4 + rand() * 12.8, 0.05, -4.2 + rand() * 8.8, {
        detail: 6,
        cast: false,
      })
    }

    // 围挡
    const fence = [[-7.0, 0, 0, 9.8], [7.0, 0, 0, 9.8], [0, -5.0, 14.2, 0], [0, 5.0, 14.2, 0]]
    fence.forEach(([x, z, w, d]) => {
      if (w > 0) {
        box(g, w, 0.05, 0.06, p.metal, x, 1.15, z)
        box(g, w, 0.05, 0.06, p.metal, x, 0.6, z)
        for (let i = -w / 2; i <= w / 2; i += 1.6) box(g, 0.08, 1.2, 0.08, p.darkMetal, x + i, 0.6, z)
        // 顶部警示条
        for (let i = -w / 2; i < w / 2; i += 0.6) {
          box(g, 0.3, 0.06, 0.02, i % 1.2 < 0.01 ? '#f2b544' : '#3d4a5c', x + i + 0.15, 1.22, z + 0.04, {
            cast: false,
          })
        }
      } else {
        box(g, 0.06, 0.05, d, p.metal, x, 1.15, z)
        box(g, 0.06, 0.05, d, p.metal, x, 0.6, z)
        for (let i = -d / 2; i <= d / 2; i += 1.6) box(g, 0.08, 1.2, 0.08, p.darkMetal, x, 0.6, z + i)
      }
    })
    buildSignpost(g, 5.9, 4.1, '#f07b3f', {})
    buildSignpost(g, -5.9, 4.1, '#e0a63a', {})

    // 街区网格，区块代表工作区文件夹
    const zones = [
      { x: -3.6, z: -1.9, w: 3.3, d: 2.4, name: 'src', color: '#7db3ff' },
      { x: 0.2, z: -2.1, w: 3.1, d: 2.1, name: 'docs', color: '#f6c177' },
      { x: 3.9, z: -1.7, w: 3.2, d: 2.5, name: 'tools', color: '#7fe0c3' },
      { x: -2.3, z: 1.4, w: 3.4, d: 2.2, name: 'assets', color: '#ff9aa2' },
      { x: 1.9, z: 1.7, w: 3.2, d: 2.0, name: 'build', color: '#b39ddb' },
    ]
    zones.forEach((zone) => {
      roundBox(g, zone.w, 0.04, zone.d, 0.06, p.path, zone.x, 0.04, zone.z)
      box(g, zone.w - 0.34, 0.07, zone.d - 0.34, p.wood, zone.x, 0.08, zone.z)
      const count = 3 + Math.floor(rand() * 5)
      for (let i = 0; i < count; i++) {
        const bx = zone.x - zone.w / 2 + 0.55 + (i % 3) * (zone.w / 3.4)
        const bz = zone.z - zone.d / 2 + 0.6 + Math.floor(i / 3) * 0.82
        const height = 0.3 + rand() * 0.95
        box(g, 0.62, height, 0.62, zone.color, bx, height / 2 + 0.12, bz, {
          pick: 'site',
          roughness: 0.7,
        })
        box(g, 0.64, 0.05, 0.64, p.woodLight, bx, height + 0.14, bz, { pick: 'site', cast: false })
        box(g, 0.22, 0.05, 0.44, p.paper, bx, height + 0.18, bz, { cast: false })
      }
      box(g, 0.74, 0.16, 0.04, zone.color, zone.x, 0.32, zone.z + zone.d / 2 - 0.08, { cast: false })
      box(g, 0.5, 0.03, 0.02, p.paper, zone.x, 0.32, zone.z + zone.d / 2 - 0.06, { cast: false })
      addObstacleAt(zone.x, zone.z, zone.w + 0.2, zone.d + 0.2)
    })

    // 在建主楼与脚手架
    const shell = roundBox(g, 4.1, 3.0, 3.0, 0.08, state.dark ? '#5f6a78' : '#cfc8bc', 0.6, 1.5, -3.2, {
      pick: 'site',
    })
    for (let floor = 0; floor < 3; floor++) {
      for (let i = 0; i < 5; i++) {
        box(shell, 0.5, 0.42, 0.04, p.glass, -1.5 + i * 0.75, 0.7 + floor * 0.85, 1.52, {
          glow: floor === 2,
          pick: 'site',
          cast: false,
        })
      }
    }
    for (let i = 0; i < 6; i++) {
      const x = -1.5 + i * 0.85
      box(g, 0.05, 3.4, 0.05, '#d9a13c', x, 1.7, -1.35)
      for (let j = 0; j < 4; j++) {
        box(g, 0.85, 0.04, 0.04, '#d9a13c', x + 0.42, 0.7 + j * 0.8, -1.35)
      }
      if (i % 2 === 0) {
        box(g, 1.0, 0.04, 0.04, '#d9a13c', x + 0.42, 1.9, -1.35, { rotation: [0, 0, 0.55] })
      }
    }
    // 脚手架防护网与跳板
    box(g, 4.5, 2.6, 0.02, state.dark ? '#3f6b5a' : '#9fc7a8', 0.6, 1.55, -1.28, {
      opacity: 0.35,
      cast: false,
      doubleSide: true,
    })
    for (const y of [0.72, 1.52, 2.32]) {
      box(g, 4.4, 0.06, 0.34, p.woodLight, 0.6, y, -1.16, { cast: false })
    }
    for (const y of [0.6, 1.5, 2.4]) {
      box(g, 4.6, 0.04, 0.05, '#d9a13c', 0.6, y, -4.75)
    }

    // 塔吊
    const crane = new THREE.Group()
    crane.position.set(5.1, 0, -2.1)
    g.add(crane)
    for (let i = 0; i < 4; i++) {
      const x = i < 2 ? -0.3 : 0.3
      const z = i % 2 ? -0.3 : 0.3
      box(crane, 0.08, 6.0, 0.08, '#e0a63a', x, 3.0, z)
    }
    for (let y = 0.5; y < 6; y += 0.75) {
      box(crane, 0.65, 0.05, 0.05, '#e0a63a', 0, y, 0.3)
      box(crane, 0.05, 0.75, 0.05, '#e0a63a', 0.3, y + 0.37, 0.3, { rotation: [0, 0, 0.55] })
    }
    const jib = box(crane, 8.2, 0.18, 0.18, '#e0a63a', -2.8, 6.05, 0)
    jib.rotation.z = -0.03
    box(crane, 1.2, 0.8, 0.8, '#e0a63a', 1.9, 5.8, 0)
    box(crane, 0.5, 1.0, 0.6, p.darkMetal, 1.9, 5.7, 0.5)
    cylinder(crane, 0.04, 0.04, 3.0, 6, p.darkMetal, -5.3, 4.5, 0)
    box(crane, 0.28, 0.3, 0.28, p.metal, -5.3, 2.95, 0)
    cylinder(crane, 0.02, 0.02, 1.8, 5, p.darkMetal, -5.3, 4.1, 0)
    // 吊钩与吊具
    box(crane, 0.5, 0.12, 0.5, p.darkMetal, -5.3, 1.55, 0)
    for (const dz of [-0.5, 0.5]) {
      cylinder(crane, 0.02, 0.02, 1.4, 5, p.darkMetal, -5.3, 2.2, dz, { rotation: [0.4, 0, 0] })
    }
    box(crane, 1.0, 0.34, 0.8, p.woodLight, -5.3, 1.3, 0, { pick: 'site' })

    // 材料、路锥与工人
    for (let i = 0; i < 5; i++) {
      box(g, 1.1, 0.14, 0.5, p.woodLight, -5.2, 0.2 + (i % 2) * 0.15, 3.4 + i * 0.1)
    }
    addObstacle(-5.7, -4.55, 3.25, 3.95)
    buildPipes(g, -0.3, 3.75, 2.2)
    buildBarrel(g, 1.9, 3.7, { color: '#7db3ff' })
    buildBarrel(g, 2.45, 3.75, { color: '#f6c177' })
    buildCrate(g, 4.45, 3.6, { width: 0.7, height: 0.55, color: '#c8843d' })
    buildCrate(g, 3.8, 3.5, { width: 0.5, height: 0.4, color: p.woodLight })
    buildContainer(g, -5.7, 0.6, { width: 2.0, depth: 1.5, color: state.dark ? '#4d6b7d' : '#8fb9c9' })
    for (let i = 0; i < 4; i++) {
      cone(g, 0.16, 0.38, '#f07b3f', -5.4 + i * 0.6, 0.2, 2.0, { segments: 8 })
    }
    addObstacle(-5.65, -3.5, 1.8, 2.2)
    // 警示围栏与照明
    for (const x of [-2.2, 2.9]) {
      for (let i = 0; i < 3; i++) {
        cylinder(g, 0.04, 0.04, 0.9, 6, p.darkMetal, x + i * 0.9, 0.45, 2.9)
        box(g, 0.9, 0.08, 0.04, i % 2 ? '#f2b544' : '#3d4a5c', x + i * 0.9 + 0.45, 0.78, 2.9, {
          cast: false,
        })
      }
    }
    addObstacle(-2.4, -0.2, 2.82, 2.98)
    addObstacle(2.7, 4.9, 2.82, 2.98)
    buildLampPost(g, -6.2, 1.2, { height: 2.8 })
    buildLampPost(g, 6.2, 1.2, { height: 2.8 })
    addPerson(g, {
      x: -2.6,
      z: 3.4,
      facing: 0.5,
      accent: '#e0a63a',
      helmet: true,
      scale: 0.94,
    })
    addPerson(g, {
      x: 3.2,
      z: 3.5,
      facing: -0.6,
      accent: new THREE.Color('#7db3ff'),
      helmet: true,
      scale: 0.95,
    })

    labels = [
      {
        id: 'workspace',
        title: state.stats.workspace || '工作区',
        detail: `${state.stats.files ?? 0} 个文件`,
        at: { x: -3.6, y: 2.2, z: -1.9 },
        tone: state.stats.files > 0 ? 'busy' : 'calm',
      },
      {
        id: 'zones',
        title: '街区',
        detail: `${state.stats.workspaceCount ?? 1} 个工作区`,
        at: { x: 3.9, y: 1.6, z: -1.7 },
      },
      {
        id: 'shell',
        title: '在建',
        detail: '代码、文档与配置',
        at: { x: 0.6, y: 3.6, z: -3.2 },
      },
    ]
    return frames.site
  }

  function buildDuty() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -4.0, maxX: 4.3, minZ: -1.45, maxZ: 3.45 }
    addObstacle(-2.75, 1.75, -2.95, -2.1)
    addObstacle(-0.9, -0.1, -1.95, -1.2)
    addObstacle(2.55, 4.85, 2.0, 2.45)
    addObstacle(3.5, 4.7, -3.15, -2.25)
    addObstacle(0.9, 2.9, 2.65, 3.55)
    addObstacle(3.55, 4.25, -0.35, 0.55)
    addObstacle(0.85, 1.35, -1.8, -1.3)
    buildRoom(g, { width: 10.5, depth: 8, height: 2.9, windowWidth: 2.2, windowX: 3.1, windowY: 1.8 })

    // 地面、地毯与灯带
    for (let i = 0; i < 10; i++) {
      plane(g, 10.2, 0.76, i % 2 ? p.floor : p.floorDark, 0, 0.075, -3.6 + i * 0.8, { cast: false })
    }
    buildRug(g, -0.5, -0.9, 4.6, 1.4, p.fabricLight, { opacity: 0.9, border: p.fabric })
    buildRug(g, 1.9, 2.0, 2.4, 1.4, p.fabricLight, { opacity: 0.9 })
    buildWallLamp(g, -3.8, 2.2, -3.9)
    buildWallLamp(g, 2.1, 2.35, -3.9)

    // 监控墙
    for (let i = 0; i < 3; i++) {
      for (let j = 0; j < 2; j++) {
        const x = -2.3 + i * 1.25
        const y = 1.35 + j * 0.78
        box(g, 1.05, 0.66, 0.08, p.darkMetal, x, y, -3.86)
        const screen = put(
          g,
          new THREE.PlaneGeometry(0.92, 0.54),
          state.dark ? accentColor() : '#5b7f9e',
          x,
          y,
          -3.81,
          { glow: true, emissiveIntensity: 0.6, pulse: true, cast: false },
        )
        screen.rotation.y = 0
        for (let k = 0; k < 3; k++) {
          box(g, 0.62, 0.02, 0.01, p.glass, x - 0.05, y - 0.16 + k * 0.13, -3.79, { glow: true, cast: false })
        }
      }
    }
    // 控制台
    roundBox(g, 4.2, 0.82, 0.75, 0.06, p.wood, -0.5, 0.41, -2.55)
    roundBox(g, 4.4, 0.08, 0.85, 0.04, p.deskTop, -0.5, 0.86, -2.55)
    box(g, 0.9, 0.05, 0.3, p.darkMetal, -1.4, 0.92, -2.4)
    box(g, 0.9, 0.05, 0.3, p.darkMetal, 0.2, 0.92, -2.4)
    box(g, 0.28, 0.24, 0.3, p.darkMetal, 1.25, 1.0, -2.5)
    sphere(g, 0.04, accentColor(), 1.25, 1.14, -2.35, { glow: true, detail: 8 })
    // 桌面键盘、鼠标、杯子、对讲机与值班记录
    for (const x of [-1.4, 0.2]) {
      box(g, 0.34, 0.03, 0.12, p.darkMetal, x, 0.9, -2.62, { cast: false })
      box(g, 0.07, 0.03, 0.1, p.paper, x + 0.26, 0.9, -2.6, { cast: false })
      cylinder(g, 0.045, 0.04, 0.1, 10, p.paper, x - 0.32, 0.95, -2.7)
    }
    box(g, 0.3, 0.04, 0.22, p.paper, 1.0, 0.92, -2.62, { cast: false })
    box(g, 0.1, 0.14, 0.06, p.darkMetal, 1.5, 1.02, -2.45)
    cylinder(g, 0.02, 0.02, 0.3, 6, p.metal, 1.5, 1.22, -2.45)
    // 椅背、坐垫与扶手
    roundBox(g, 0.55, 0.08, 0.5, 0.06, p.fabric, -0.5, 0.46, -1.55)
    roundBox(g, 0.5, 0.6, 0.08, 0.06, p.fabricLight, -0.5, 0.75, -1.32)
    box(g, 0.06, 0.45, 0.06, p.metal, -0.5, 0.22, -1.55)
    for (const dx of [-0.26, 0.26]) {
      box(g, 0.05, 0.06, 0.4, p.darkMetal, -0.5 + dx, 0.62, -1.5)
    }
    // 备用椅与收纳柜
    roundBox(g, 0.5, 0.08, 0.46, 0.06, p.fabric, 1.1, 0.44, -1.55)
    roundBox(g, 0.46, 0.5, 0.07, 0.05, p.fabricLight, 1.1, 0.68, -1.36)
    box(g, 0.05, 0.42, 0.05, p.metal, 1.1, 0.21, -1.55)
    box(g, 0.9, 0.7, 0.45, p.woodLight, -2.2, 0.35, -3.6)
    for (let i = 0; i < 2; i++) {
      box(g, 0.8, 0.2, 0.03, p.wood, -2.2, 0.2 + i * 0.3, -3.37)
      box(g, 0.1, 0.03, 0.02, p.metal, -2.2, 0.2 + i * 0.3, -3.35)
    }
    cylinder(g, 0.045, 0.045, 0.5, 8, p.darkMetal, -2.9, 1.05, -3.7)
    const consoleLamp = sphere(g, 0.14, '#ffe2b8', -2.9, 1.35, -3.7, { glow: true, detail: 10 })
    consoleLamp.scale.set(1, 0.7, 1)

    // 服务器机柜
    roundBox(g, 1.0, 2.1, 0.8, 0.05, p.darkMetal, 4.1, 1.05, -2.7)
    for (let i = 0; i < 5; i++) {
      box(g, 0.82, 0.04, 0.04, p.metal, 4.1, 0.3 + i * 0.36, -2.28)
      const led = sphere(g, 0.025, i % 2 ? '#7fe0c3' : accentColor(), 3.85, 0.3 + i * 0.36, -2.26, {
        glow: true,
        detail: 6,
      })
      led.userData.led = true
    }

    // 地图屏
    roundBox(g, 2.4, 1.5, 0.08, 0.05, p.darkMetal, 3.7, 1.45, 2.2)
    put(g, new THREE.PlaneGeometry(2.2, 1.3), state.dark ? '#1d3a4d' : '#dcece6', 3.7, 1.45, 2.15, {
      glow: true,
      emissiveIntensity: 0.3,
      cast: false,
    })
    for (const dx of [-1.05, 1.05]) {
      box(g, 0.07, 1.45, 0.07, p.metal, 3.7 + dx, 0.72, 2.2)
    }
    box(g, 2.5, 0.07, 0.42, p.metal, 3.7, 0.04, 2.2)
    for (let i = 0; i < 4; i++) {
      line(g, [
        new THREE.Vector3(2.75 + i * 0.6, 1.0, 2.12),
        new THREE.Vector3(3.0 + i * 0.6, 1.8, 2.12),
      ], p.metal, { opacity: 0.4 })
    }
    // 咖啡机、折叠床、植物
    roundBox(g, 0.7, 1.0, 0.55, 0.05, p.metal, -4.35, 0.52, 2.9)
    box(g, 0.3, 0.3, 0.1, p.darkMetal, -4.35, 0.6, 2.6)
    const cup = cylinder(g, 0.07, 0.05, 0.12, 8, p.paper, -4.35, 0.2, 2.55)
    for (let i = 0; i < 3; i++) {
      cylinder(g, 0.055, 0.045, 0.09, 8, '#f6c177', -4.5, 1.06, 2.75 + i * 0.14)
    }
    roundBox(g, 1.9, 0.16, 0.72, 0.05, p.fabric, 1.9, 0.28, 3.1)
    roundBox(g, 0.6, 0.16, 0.5, 0.05, p.paper, 1.0, 0.4, 2.95, { rotation: [0, 0.3, 0] })
    roundBox(g, 0.7, 0.1, 0.5, 0.05, p.fabricLight, 2.5, 0.4, 2.9, { rotation: [0, -0.2, 0] })
    box(g, 0.05, 0.5, 0.05, p.darkMetal, 1.05, 0.28, 3.35)
    buildWaterCooler(g, 3.9, 0.1)
    cylinder(g, 0.03, 0.04, 1.5, 8, p.darkMetal, 3.35, 0.85, 3.15)
    sphere(g, 0.16, '#ffe2b8', 3.35, 1.65, 3.15, { glow: true, detail: 12 })
    addObstacleAt(3.35, 3.15, 0.4, 0.4)
    buildPlant(g, 4.5, 3.4, 1.0)
    buildPlant(g, -4.8, 3.4, 0.9)

    // 左侧墙面：值班制度牌与相框
    buildWallFrame(g, -5.16, 1.9, 0.4, 0.9, 0.62, { rotation: Math.PI / 2, color: '#f2f6ff' })
    buildWallFrame(g, -5.16, 1.9, 1.5, 0.62, 0.8, { rotation: Math.PI / 2, color: '#ffe9d0' })
    for (let i = 0; i < 4; i++) {
      box(g, 0.03, 0.1, 0.5, i % 2 ? accentColor() : p.darkMetal, -5.1, 1.45 + i * 0.14, 0.4, {
        cast: false,
      })
    }
    buildWallClock(g, 3.9, 2.4, -3.9)

    addPerson(g, {
      x: 0.9,
      z: -1.05,
      facing: Math.PI,
      accent: accentColor(),
      scale: 0.98,
    })

    labels = [
      {
        id: 'api',
        title: 'API 服务',
        detail: `${state.stats.apiEnabled ? '运行中' : '已停止'} · :${state.stats.apiPort ?? 8686}`,
        at: { x: -2.3, y: 3.1, z: -3.86 },
        tone: state.stats.apiEnabled ? 'busy' : 'calm',
      },
      {
        id: 'mcp',
        title: 'MCP 服务',
        detail: `${state.stats.mcpEnabled ? '运行中' : '已停止'} · :${state.stats.mcpPort ?? 8765}`,
        at: { x: 4.1, y: 2.5, z: -2.7 },
        tone: state.stats.mcpEnabled ? 'busy' : 'calm',
      },
      {
        id: 'engine',
        title: '推理引擎',
        detail: state.stats.engine || '待机',
        at: { x: -0.5, y: 1.5, z: -2.55 },
      },
      {
        id: 'duty',
        title: '定时任务',
        detail: '暂无计划任务',
        at: { x: 3.9, y: 2.9, z: -3.9 },
      },
    ]
    return frames.duty
  }

  function buildReception() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    walkArea = { minX: -4.2, maxX: 4.2, minZ: -3.35, maxZ: 2.6 }
    addObstacle(-2.7, 2.7, -2.9, -2.0)
    addObstacle(-4.05, -0.95, 1.9, 2.75)
    addObstacle(-0.35, 0.35, -3.35, -2.85)
    addObstacle(0.05, 0.75, 2.2, 2.8)
    addObstacle(3.8, 4.4, 2.3, 2.95)
    addObstacle(-4.75, -4.1, 0.15, 0.75)
    addObstacle(1.35, 1.65, 1.95, 2.25)
    addObstacle(2.55, 2.85, 1.95, 2.25)
    buildRoom(g, { width: 10.5, depth: 8, height: 3.0, windowWidth: 1.8, windowX: -3.4, windowY: 1.8 })

    // 地面、等候区地毯与灯带
    for (let i = 0; i < 10; i++) {
      plane(g, 10.2, 0.76, i % 2 ? p.floor : p.floorDark, 0, 0.075, -3.6 + i * 0.8, { cast: false })
    }
    buildRug(g, -1.0, 1.7, 3.6, 1.8, p.fabricLight, { border: accentColor() })
    buildWallLamp(g, -3.7, 2.2, -3.9)
    buildWallLamp(g, 1.0, 2.2, -3.9)

    // 长前台
    roundBox(g, 5.0, 1.0, 0.65, 0.06, p.wood, 0, 0.5, -2.4)
    roundBox(g, 5.2, 0.09, 0.8, 0.04, p.deskTop, 0, 1.03, -2.4)
    box(g, 4.8, 0.62, 0.04, p.woodLight, 0, 0.42, -2.05, { pick: 'desk' })
    box(g, 0.62, 0.38, 0.04, p.darkMetal, 0.7, 1.28, -2.47)
    box(g, 0.56, 0.32, 0.01, p.glass, 0.7, 1.28, -2.44, { glow: true })
    box(g, 0.5, 0.28, 0.02, state.dark ? accentColor() : '#cfe3ff', 0.7, 1.28, -2.4, {
      glow: true,
      emissiveIntensity: 0.45,
      cast: false,
    })
    box(g, 0.09, 0.18, 0.12, p.darkMetal, 0.7, 1.16, -2.5)
    box(g, 0.3, 0.03, 0.1, p.darkMetal, 0.7, 1.09, -2.28, { cast: false })
    // 服务铃、电话与花
    cylinder(g, 0.09, 0.09, 0.03, 14, p.metal, -0.9, 1.09, -2.3)
    sphere(g, 0.055, accentColor(), -0.9, 1.15, -2.3, { glow: true, detail: 8 })
    box(g, 0.26, 0.08, 0.18, p.darkMetal, 1.65, 1.11, -2.35, { rotation: [0, -0.2, 0] })
    cylinder(g, 0.08, 0.06, 0.18, 10, p.glass, 1.45, 1.16, -2.45)
    sphere(g, 0.11, '#ff9aa2', 1.45, 1.31, -2.45, { detail: 10 })
    box(g, 0.28, 0.4, 0.02, p.paper, -1.45, 1.25, -2.1, { cast: false })
    for (let i = 0; i < 3; i++) {
      box(g, 0.2, 0.03, 0.01, p.darkMetal, -1.45, 1.35 - i * 0.08, -2.08, { cast: false })
    }
    box(g, 0.6, 0.04, 0.42, p.metal, -1.3, 1.09, -2.3)
    cylinder(g, 0.045, 0.045, 0.5, 8, p.darkMetal, 2.05, 1.3, -2.45)
    const deskLamp = sphere(g, 0.1, '#ffe2b8', 2.05, 1.6, -2.45, { glow: true, detail: 10 })
    deskLamp.userData.lamp = true
    // 包裹架
    for (let i = 0; i < 3; i++) {
      box(g, 0.6, 0.4, 0.46, ['#c8843d', '#7db3ff', '#7fe0c3'][i], -2.0 + i * 0.68, 1.2, -2.4)
    }

    // 接待员座椅
    roundBox(g, 0.55, 0.08, 0.5, 0.05, p.fabric, 0, 0.46, -3.1)
    roundBox(g, 0.52, 0.56, 0.08, 0.05, p.fabricLight, 0, 0.72, -3.32)
    box(g, 0.06, 0.45, 0.06, p.metal, 0, 0.22, -3.1)

    // 徽章板
    roundBox(g, 3.3, 1.45, 0.06, 0.05, p.metal, -1.0, 1.6, -3.9)
    roundBox(g, 3.1, 1.28, 0.03, 0.04, state.dark ? '#26303a' : '#fcfcfa', -1.0, 1.6, -3.86)
    for (let i = 0; i < 6; i++) {
      const x = -2.1 + (i % 3) * 1.1
      const y = 1.88 - Math.floor(i / 3) * 0.42
      box(g, 0.42, 0.26, 0.02, p.glass, x, y, -3.83, { glow: i === 0 })
      box(g, 0.3, 0.03, 0.01, p.darkMetal, x, y + 0.05, -3.81)
      box(g, 0.2, 0.03, 0.01, p.darkMetal, x, y - 0.02, -3.81)
    }
    // 等候长椅
    roundBox(g, 3.0, 0.09, 0.5, 0.04, p.fabric, -2.5, 0.45, 2.3)
    roundBox(g, 3.0, 0.46, 0.08, 0.04, p.fabricLight, -2.5, 0.7, 2.55)
    for (const dx of [-1.25, 0, 1.25]) {
      box(g, 0.08, 0.42, 0.38, p.metal, -2.5 + dx, 0.22, 2.3)
    }
    roundBox(g, 0.6, 0.14, 0.4, 0.05, p.fabric, -3.4, 0.57, 2.3)
    roundBox(g, 0.6, 0.14, 0.4, 0.05, p.fabric, -1.6, 0.57, 2.3)
    // 边几、杂志与饮茶角
    roundBox(g, 0.62, 0.52, 0.56, 0.05, p.woodLight, 0.4, 0.26, 2.5)
    box(g, 0.3, 0.04, 0.22, '#7db3ff', 0.36, 0.54, 2.5, { rotation: [0, 0.2, 0] })
    box(g, 0.26, 0.03, 0.2, '#f6c177', 0.46, 0.58, 2.46, { rotation: [0, -0.15, 0] })
    buildWaterCooler(g, 4.1, 2.6)
    buildPlant(g, 3.35, 2.95, 0.55)
    // 排队立柱与绳带
    buildStanchion(g, 1.5, 2.1)
    buildStanchion(g, 2.7, 2.1)
    cylinder(g, 0.015, 0.015, 1.2, 5, p.darkMetal, 2.1, 0.86, 2.1, { rotation: [0, 0, Math.PI / 2], cast: false })
    // 衣帽架与雨伞桶
    cylinder(g, 0.05, 0.07, 1.7, 8, p.wood, -4.4, 0.85, 0.45)
    for (let i = 0; i < 3; i++) {
      const angle = (i / 3) * Math.PI * 2
      box(g, 0.28, 0.05, 0.05, p.metal, -4.4 + Math.cos(angle) * 0.15, 1.62, 0.45 + Math.sin(angle) * 0.15, {
        rotation: [0, -angle, 0],
      })
    }
    cylinder(g, 0.16, 0.14, 0.5, 10, p.metal, -1.9, 0.25, 3.2, { opacity: 1 })
    buildPlant(g, -2.6, 3.4, 0.8)
    // 墙面相框、时钟与指引牌
    buildWallFrame(g, 1.9, 1.95, -3.92, 0.7, 0.5, { color: '#dfeaff' })
    buildWallFrame(g, 2.7, 2.15, -3.92, 0.5, 0.6, { color: '#ffe6d2' })
    buildWallClock(g, 3.6, 2.3, -3.92)
    buildSignpost(g, 4.4, 3.3, accentColor(), {})
    // 入口门框与前台招牌
    box(g, 0.1, 2.4, 0.1, p.metal, 4.3, 1.2, 3.6)
    box(g, 0.1, 2.4, 0.1, p.metal, 5.5, 1.2, 3.6)
    box(g, 1.3, 0.1, 0.1, p.metal, 4.9, 2.4, 3.6)
    box(g, 2.2, 0.5, 0.06, state.dark ? '#2b2f38' : '#e9e4dc', 0.2, 2.35, -3.9)
    box(g, 2.0, 0.08, 0.02, accentColor(), 0.2, 2.35, -3.85, { glow: true })
    // 植物与地垫
    buildPlant(g, -4.6, 3.2, 1.1)
    buildPlant(g, 4.6, 3.2, 0.9)
    plane(g, 2.4, 1.1, p.path, 4.9, 0.02, 3.1, { cast: false })

    addPerson(g, {
      x: -0.9,
      z: -3.0,
      facing: 0,
      accent: accentColor(),
      scale: 1.0,
    })
    addPerson(g, {
      x: 3.5,
      z: 1.9,
      facing: Math.PI + 0.4,
      accent: new THREE.Color('#7db3ff'),
      scale: 0.96,
    })

    labels = [
      {
        id: 'devices',
        title: '已配对电脑',
        detail: '还没有配对的电脑',
        at: { x: -1.0, y: 2.7, z: -3.9 },
      },
      {
        id: 'api',
        title: '本机 API',
        detail: `${state.stats.apiEnabled ? '运行中' : '已停止'} · :${state.stats.apiPort ?? 8686}`,
        at: { x: 0.2, y: 2.9, z: -3.9 },
        tone: state.stats.apiEnabled ? 'busy' : 'calm',
      },
      {
        id: 'mcp',
        title: 'MCP',
        detail: `${state.stats.mcpEnabled ? '运行中' : '已停止'} · :${state.stats.mcpPort ?? 8765}`,
        at: { x: 2.4, y: 2.0, z: -2.5 },
      },
      {
        id: 'characters',
        title: '角色卡',
        detail: `${state.stats.characters ?? 0} 位角色`,
        at: { x: 0, y: 1.9, z: -3.05 },
      },
    ]
    return frames.desk
  }

  const builders = {
    studio: buildStudio,
    home: buildHome,
    campus: buildCampus,
    library: buildLibrary,
    site: buildSite,
    duty: buildDuty,
    desk: buildReception,
  }

  function buildPlace() {
    clearScene()
    obstacles = []
    walkArea = { minX: -4, maxX: 4, minZ: -3, maxZ: 3 }
    root = new THREE.Group()
    scene.add(root)
    const frame = (builders[currentPlace] || buildStudio)()
    cameraGoal = {
      target: new THREE.Vector3(frame.x, frame.y, frame.z),
      radius: frame.radius,
      polar: frame.polar,
      azimuth: frame.azimuth,
    }
    cameraView = {
      target: cameraGoal.target.clone(),
      radius: cameraGoal.radius * 1.28,
      polar: Math.min(1.35, cameraGoal.polar - 0.08),
      azimuth: cameraGoal.azimuth + 0.18,
    }
    renderLabels()
    applyCamera(1)
    stage.classList.remove('is-entering')
    requestAnimationFrame(() => stage.classList.add('is-entering'))
  }

  function renderLabels() {
    const wanted = new Set(labels.map((item) => item.id))
    labelNodes.forEach((node, id) => {
      if (!wanted.has(id)) {
        node.remove()
        labelNodes.delete(id)
      }
    })
    labels.forEach((item) => {
      let node = labelNodes.get(item.id)
      if (!node) {
        node = document.createElement('button')
        node.type = 'button'
        node.className = 'sign'
        node.innerHTML = '<span class="dot"></span><span class="copy"><strong></strong><small></small></span>'
        node.addEventListener('click', () => {
          const current = labels.find((label) => label.id === node.dataset.id)
          if (current && current.pick) pick(current.pick)
        })
        overlay.appendChild(node)
        labelNodes.set(item.id, node)
      }
      node.dataset.id = item.id
      node.dataset.pick = item.pick ? 'true' : 'false'
      node.dataset.tone = item.tone ?? 'calm'
      node.querySelector('strong').textContent = item.title
      const small = node.querySelector('small')
      small.textContent = item.detail ?? ''
      small.hidden = !item.detail
      node.setAttribute('aria-label', item.detail ? `${item.title}: ${item.detail}` : item.title)
    })
    layoutLabels()
  }

  function viewportWidth() {
    return Math.max(1, stage.clientWidth || window.innerWidth || document.documentElement.clientWidth || 1)
  }

  function viewportHeight() {
    return Math.max(1, stage.clientHeight || window.innerHeight || document.documentElement.clientHeight || 1)
  }

  function project(point) {
    const vector = new THREE.Vector3(point.x, point.y, point.z).project(camera)
    return {
      x: (vector.x * 0.5 + 0.5) * viewportWidth(),
      y: (-vector.y * 0.5 + 0.5) * viewportHeight(),
      z: vector.z,
      visible: vector.z < 1,
    }
  }

  function layoutLabels() {
    const width = viewportWidth()
    const height = viewportHeight()
    if (!width || !height) return
    const placed = []
    labels.forEach((item) => {
      const node = labelNodes.get(item.id)
      if (!node) return
      const point = project(item.at)
      let visible = point.visible
      const w = node.offsetWidth || 120
      const h = node.offsetHeight || 42
      let left = Math.max(8, Math.min(point.x - w / 2, width - w - 8))
      let top = Math.max(8, Math.min(point.y - h, height - h - 8))
      for (let attempt = 0; attempt < 8 && visible; attempt++) {
        const overlap = placed.some(([x0, y0, x1, y1]) =>
          left < x1 + 4 && left + w > x0 - 4 && top < y1 + 4 && top + h > y0 - 4,
        )
        if (!overlap) break
        top -= h + 6
        if (top < 8) {
          top = Math.min(point.y - h, height - h - 8)
          left = Math.max(8, Math.min(point.x + (attempt % 2 ? 14 : -14) - w / 2, width - w - 8))
        }
      }
      visible = visible && top >= -h && top < height
      if (visible) placed.push([left, top, left + w, top + h])
      node.style.transform = `translate(${left.toFixed(1)}px, ${top.toFixed(1)}px)`
      node.style.visibility = visible ? 'visible' : 'hidden'
      node.style.zIndex = String(Math.round((1 - point.z) * 1000))
    })
  }

  function applyCamera(amount) {
    if (!camera || !cameraView || !cameraGoal) return
    const k = Math.max(0, Math.min(1, amount))
    cameraView.target.lerp(cameraGoal.target, k)
    cameraView.radius += (cameraGoal.radius - cameraView.radius) * k
    cameraView.polar += (cameraGoal.polar - cameraView.polar) * k
    cameraView.azimuth += (cameraGoal.azimuth - cameraView.azimuth) * k
    const sinPolar = Math.sin(cameraView.polar)
    camera.position.set(
      cameraView.target.x + sinPolar * Math.sin(cameraView.azimuth) * cameraView.radius,
      cameraView.target.y + Math.cos(cameraView.polar) * cameraView.radius,
      cameraView.target.z + sinPolar * Math.cos(cameraView.azimuth) * cameraView.radius,
    )
    camera.lookAt(cameraView.target)
  }

  function pick(place) {
    if (!PLACES.includes(place) || place === currentPlace) return
    if (window.StudioBridge && typeof window.StudioBridge.onPlaceSelected === 'function') {
      window.StudioBridge.onPlaceSelected(place)
    }
  }

  function pointerPair() {
    return Array.from(pointerPositions.values())
  }

  function pairDistance() {
    const pair = pointerPair()
    if (pair.length < 2) return 0
    return Math.hypot(pair[0].x - pair[1].x, pair[0].y - pair[1].y)
  }

  function onPointerDown(event) {
    if (event.pointerType === 'mouse' && event.button !== 0) return
    pointerPositions.set(event.pointerId, { x: event.clientX, y: event.clientY })
    try { stage.setPointerCapture(event.pointerId) } catch (_) { /* 合成事件或旧 WebView 可能不支持 */ }
    if (pointerPositions.size === 1) {
      pointerId = event.pointerId
      dragging = true
      moved = 0
      lastX = event.clientX
      lastY = event.clientY
    } else if (pointerPositions.size === 2) {
      moved = 999
      pinchDistance = pairDistance()
    }
  }

  function onPointerMove(event) {
    const current = pointerPositions.get(event.pointerId)
    if (!current) return
    current.x = event.clientX
    current.y = event.clientY
    if (pointerPositions.size >= 2) {
      const distance = pairDistance()
      if (distance > 0 && pinchDistance > 0) {
        cameraGoal.radius = Math.max(7, Math.min(62, cameraGoal.radius * (pinchDistance / distance)))
        applyCamera(0.55)
      }
      pinchDistance = distance
      idleTurns = 0
      return
    }
    if (event.pointerId !== pointerId || !dragging) return
    const dx = current.x - lastX
    const dy = current.y - lastY
    moved += Math.abs(dx) + Math.abs(dy)
    lastX = current.x
    lastY = current.y
    cameraGoal.azimuth -= dx * 0.007
    cameraGoal.polar = Math.max(0.38, Math.min(1.28, cameraGoal.polar + dy * 0.004))
    idleTurns = 0
    applyCamera(0.55)
  }

  function onPointerUp(event) {
    const wasPrimary = event.pointerId === pointerId
    pointerPositions.delete(event.pointerId)
    try { stage.releasePointerCapture(event.pointerId) } catch (_) { }
    if (wasPrimary) {
      if (moved < 8) attemptPick(event.clientX, event.clientY)
      pointerId = pointerPositions.keys().next().value ?? null
      const remaining = pointerId === null ? null : pointerPositions.get(pointerId)
      if (remaining) {
        lastX = remaining.x
        lastY = remaining.y
      }
    }
    dragging = pointerPositions.size > 0
    if (pointerPositions.size < 2) pinchDistance = 0
  }

  function attemptPick(clientX, clientY) {
    if (!root) return
    const rect = stage.getBoundingClientRect()
    pointer.set(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1,
    )
    raycaster.setFromCamera(pointer, camera)
    const hits = raycaster.intersectObjects(root.children, true)
    for (const hit of hits) {
      let item = hit.object
      while (item && !item.userData.pick) item = item.parent
      if (item && item.userData.pick) {
        pick(item.userData.pick)
        return
      }
    }
  }

  function onWheel(event) {
    cameraGoal.radius = Math.max(7, Math.min(62, cameraGoal.radius + event.deltaY * 0.01))
    applyCamera(0.6)
  }

  function animate(now = performance.now()) {
    requestAnimationFrame(animate)
    if (now - lastFrameAt < (dragging ? FRAME_MS_ACTIVE : FRAME_MS)) return
    lastFrameAt = now
    const dt = Math.min(0.05, clock.getDelta())
    if (!paused) {
      animTime += dt
      const t = animTime
      animateFns.forEach((item) => {
        if (item.kind === 'person') {
          const data = item.data
          const phase = data.phase
          const baseY = data.baseY || 0
          const group = data.body.parent
          const walk = data.walk
          let moving = false
          if (walk) {
            if (walk.wait > 0) {
              walk.wait -= dt
            } else {
              const dx = walk.targetX - group.position.x
              const dz = walk.targetZ - group.position.z
              const distance = Math.hypot(dx, dz)
              if (distance < 0.12) {
                pickWalkTarget(walk)
                walk.wait = 1.5 + rand() * 3.5
              } else {
                const step = Math.min(distance, walk.speed * dt)
                const nextX = group.position.x + (dx / distance) * step
                const nextZ = group.position.z + (dz / distance) * step
                const radius = walk.radius ?? PERSON_RADIUS
                if (!isObstacleAt(nextX, nextZ, radius)) {
                  group.position.x = nextX
                  group.position.z = nextZ
                  moving = true
                } else if (!isObstacleAt(nextX, group.position.z, radius)) {
                  group.position.x = nextX
                  moving = true
                } else if (!isObstacleAt(group.position.x, nextZ, radius)) {
                  group.position.z = nextZ
                  moving = true
                } else {
                  pickWalkTarget(walk)
                  walk.wait = 0.4
                }
                if (moving) {
                  let delta = Math.atan2(dx, dz) - group.rotation.y
                  while (delta > Math.PI) delta -= Math.PI * 2
                  while (delta < -Math.PI) delta += Math.PI * 2
                  group.rotation.y += delta * Math.min(1, dt * 7)
                }
              }
            }
          }
          const stride = moving ? Math.sin(t * 9 + phase) : 0
          const bob = moving
            ? Math.abs(Math.sin(t * 9 + phase)) * 0.035
            : Math.sin(t * 1.8 + phase) * 0.018
          group.position.y = baseY + bob
          data.body.rotation.z = Math.sin(t * 1.1 + phase) * 0.015
          data.armL.rotation.x = moving ? stride * 0.55 : 0
          data.armR.rotation.x = moving ? -stride * 0.55 : 0
          data.armL.rotation.z = 0.24 + Math.sin(t * 1.9 + phase) * 0.06
          data.armR.rotation.z = -0.24 + Math.cos(t * 2.1 + phase) * 0.06
          data.legL.rotation.x = moving ? stride * 0.5 : 0
          data.legR.rotation.x = moving ? -stride * 0.5 : 0
          data.head.rotation.y = moving
            ? Math.sin(t * 0.6 + phase) * 0.06
            : Math.sin(t * 0.6 + phase) * 0.16
          if (data.label) {
            data.label.at.x = group.position.x
            data.label.at.z = group.position.z
          }
          if (data.ysm) data.ysm.update(dt, moving)
        } else if (item.kind === 'beacon') {
          item.mesh.visible = Math.sin(t * 2.4) > -0.25
        }
      })
      pulseMats.forEach((material, index) => {
        material.emissiveIntensity = 0.55 + Math.sin(t * 1.3 + index * 0.7) * 0.18
      })
      // 人物之间保持最小间距，避免多个居民叠在一起，体型大的角色间距也更大。
      const people = animateFns
        .filter((item) => item.kind === 'person')
        .map((item) => item.data)
      for (let i = 0; i < people.length; i++) {
        for (let j = i + 1; j < people.length; j++) {
          const a = people[i].body.parent
          const b = people[j].body.parent
          const dx = b.position.x - a.position.x
          const dz = b.position.z - a.position.z
          const distance = Math.hypot(dx, dz)
          const minDistance = (people[i].walk?.radius ?? PERSON_RADIUS) +
            (people[j].walk?.radius ?? PERSON_RADIUS) + 0.02
          if (distance > 0.0001 && distance < minDistance) {
            const push = (minDistance - distance) / 2
            const nx = dx / distance
            const nz = dz / distance
            const ax = a.position.x - nx * push
            const az = a.position.z - nz * push
            const bx = b.position.x + nx * push
            const bz = b.position.z + nz * push
            if (!isObstacleAt(ax, az, people[i].walk?.radius ?? PERSON_RADIUS)) {
              a.position.x = ax
              a.position.z = az
            }
            if (!isObstacleAt(bx, bz, people[j].walk?.radius ?? PERSON_RADIUS)) {
              b.position.x = bx
              b.position.z = bz
            }
          }
        }
      }
      for (const item of animateFns) {
        if (item.kind !== 'person' || !item.data.label) continue
        item.data.label.at.x = item.data.body.parent.position.x
        item.data.label.at.z = item.data.body.parent.position.z
      }
      idleTurns += dt
      if (idleTurns > 14 && !dragging) {
        cameraGoal.azimuth += dt * 0.03
      }
    }
    applyCamera(1 - Math.exp(-dt * 4.5))
    layoutLabels()
    renderer.render(scene, camera)
    if (firstFrame) {
      firstFrame = false
      if (boot) boot.hidden = true
      if (window.StudioBridge && typeof window.StudioBridge.onReady === 'function') {
        window.StudioBridge.onReady()
      }
    }
  }

  function applyTheme() {
    const p = palette()
    document.body.dataset.dark = state.dark ? 'true' : 'false'
    document.body.style.setProperty('--accent', state.accent || '#0D84FF')
    document.body.style.background = state.dark
      ? `radial-gradient(circle at 50% 18%, ${p.bgTop} 0%, ${p.bgBottom} 100%)`
      : `radial-gradient(circle at 50% 18%, ${p.bgTop} 0%, ${p.bgBottom} 100%)`
  }

  function showError(message) {
    if (boot) boot.hidden = true
    if (typeof window.__studioShowError === 'function') {
      window.__studioShowError(message)
    } else if (fallback) {
      fallback.hidden = false
      fallback.textContent = message
    }
    if (window.StudioBridge && typeof window.StudioBridge.onError === 'function') {
      window.StudioBridge.onError(message)
    }
  }

  function init() {
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' })
    } catch (error) {
      showError('这台设备无法显示 3D 场景（WebGL 不可用）')
      return
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    renderer.setSize(stage.clientWidth, stage.clientHeight)
    renderer.setClearColor(0x000000, 0)
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    renderer.toneMappingExposure = state.dark ? 1.05 : 1.15
    renderer.shadowMap.enabled = true
    renderer.shadowMap.type = THREE.PCFSoftShadowMap
    stage.appendChild(renderer.domElement)

    scene = new THREE.Scene()
    scene.fog = new THREE.Fog(state.dark ? 0x07101c : 0xdcebe3, 42, 110)
    camera = new THREE.PerspectiveCamera(42, stage.clientWidth / Math.max(1, stage.clientHeight), 0.1, 160)
    raycaster = new THREE.Raycaster()
    pointer = new THREE.Vector2()
    clock = new THREE.Clock()

    const hemisphere = new THREE.HemisphereLight(
      state.dark ? 0x8cb4e8 : 0xffffff,
      state.dark ? 0x0a0d14 : 0x9db8a6,
      state.dark ? 0.85 : 1.0,
    )
    scene.add(hemisphere)
    const sun = new THREE.DirectionalLight(0xfff2dd, state.dark ? 1.1 : 1.4)
    sun.position.set(-9, 16, 11)
    sun.castShadow = true
    sun.shadow.mapSize.set(1024, 1024)
    sun.shadow.camera.left = -22
    sun.shadow.camera.right = 22
    sun.shadow.camera.top = 22
    sun.shadow.camera.bottom = -22
    sun.shadow.camera.near = 1
    sun.shadow.camera.far = 60
    sun.shadow.bias = -0.0007
    scene.add(sun)
    const fill = new THREE.DirectionalLight(state.dark ? 0x6b8ac4 : 0xdcefff, 0.35)
    fill.position.set(12, 7, -8)
    scene.add(fill)

    currentPlace = state.place
    lastStatsSignature = JSON.stringify(state.stats)
    try {
      buildPlace()
    } catch (error) {
      showError('3D 场景构建失败：' + (error && error.message ? error.message : String(error)))
      throw error
    }

    stage.addEventListener('pointerdown', onPointerDown)
    stage.addEventListener('pointermove', onPointerMove)
    stage.addEventListener('pointerup', onPointerUp)
    stage.addEventListener('pointercancel', onPointerUp)
    stage.addEventListener('wheel', onWheel, { passive: true })
    observeSize()
    animate()
  }

  function resize() {
    if (!renderer || !camera) return
    const width = viewportWidth()
    const height = viewportHeight()
    if (width < 2 || height < 2) return
    lastWidth = width
    lastHeight = height
    stage.style.width = width + 'px'
    stage.style.height = height + 'px'
    overlay.style.width = width + 'px'
    overlay.style.height = height + 'px'
    renderer.setSize(width, height)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    layoutLabels()
  }

  function observeSize() {
    const sync = () => {
      if (viewportWidth() === lastWidth && viewportHeight() === lastHeight) return
      resize()
    }
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(sync).observe(stage)
    }
    window.addEventListener('resize', sync)
    window.addEventListener('orientationchange', sync)
    sync()
    requestAnimationFrame(sync)
    setTimeout(sync, 300)
  }

  const params = new URLSearchParams(location.hash.startsWith('#') ? location.hash.substring(1) : '')
  state.place = PLACES.includes(params.get('place')) ? params.get('place') : 'studio'
  state.accent = params.get('accent') ? decodeURIComponent(params.get('accent')) : '#0D84FF'
  state.dark = params.get('dark') === '1'

  applyTheme()
  try {
    init()
  } catch (error) {
    showError('3D 初始化失败：' + (error && error.message ? error.message : String(error)))
  }

  window.Studio = {
    setState(next) {
      if (!next) return
      const previousAccent = state.accent
      const previousDark = state.dark
      state.stats = next.stats || {}
      state.avatars = Array.isArray(next.avatars) ? next.avatars : []
      if (next.accent) state.accent = next.accent
      if (typeof next.dark === 'boolean') state.dark = next.dark
      if (PLACES.includes(next.place)) state.place = next.place
      applyTheme()
      const signature = JSON.stringify(state.stats) + '|' + JSON.stringify(state.avatars)
      if (previousAccent !== state.accent || previousDark !== state.dark) {
        scene.fog.color.set(state.dark ? 0x07101c : 0xdcebe3)
        lastStatsSignature = signature
        currentPlace = state.place
        buildPlace()
      } else if (PLACES.includes(state.place) && state.place !== currentPlace) {
        lastStatsSignature = signature
        currentPlace = state.place
        buildPlace()
      } else if (signature !== lastStatsSignature) {
        lastStatsSignature = signature
        const savedGoal = cameraGoal
          ? { target: cameraGoal.target.clone(), radius: cameraGoal.radius, polar: cameraGoal.polar, azimuth: cameraGoal.azimuth }
          : null
        const savedView = cameraView
          ? { target: cameraView.target.clone(), radius: cameraView.radius, polar: cameraView.polar, azimuth: cameraView.azimuth }
          : null
        buildPlace()
        if (savedGoal && savedView) {
          cameraGoal = savedGoal
          cameraView = savedView
          applyCamera(1)
        }
      }
    },
    setPlace(place) {
      if (!PLACES.includes(place)) return
      state.place = place
      currentPlace = place
      buildPlace()
    },
    resetView() {
      if (!currentPlace) return
      const frame = frames[currentPlace]
      cameraGoal.target.set(frame.x, frame.y, frame.z)
      cameraGoal.radius = frame.radius
      cameraGoal.polar = frame.polar
      cameraGoal.azimuth = frame.azimuth
      idleTurns = 0
    },
    setPaused(value) {
      paused = Boolean(value)
    },
    isReady() {
      return Boolean(renderer && scene)
    },
  }

  if (window.StudioBridge && typeof window.StudioBridge.onReady === 'function') {
    window.StudioBridge.onReady()
  }
})()

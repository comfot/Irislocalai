/* 综合空间的离线 3D 场景：园区与五个场所，使用本地 three.min.js 渲染。 */
(() => {
  'use strict'

  const stage = document.getElementById('stage')
  const overlay = document.getElementById('overlay')
  const fallback = document.getElementById('fallback')
  const boot = document.getElementById('boot')
  let firstFrame = true

  const PLACES = ['studio', 'campus', 'library', 'site', 'duty', 'desk']
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

  let state = {
    place: 'studio',
    accent: '#0D84FF',
    dark: false,
    stats: {},
  }

  const frames = {
    studio: { x: 0, y: 0.9, z: 0, radius: 21, polar: 0.94, azimuth: 0.6 },
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

  function addPerson(group, options) {
    const p = palette()
    const person = new THREE.Group()
    person.position.set(options.x, options.y ?? 0, options.z)
    person.rotation.y = options.facing ?? 0
    person.scale.setScalar(options.scale ?? 1)
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
    animateFns.push({ kind: 'person', data: person.userData })
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

  function buildStudio() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    buildRoom(g, { width: 10, depth: 8, height: 2.8, board: true, shelf: true })

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
    addPerson(g, {
      x: 0.4,
      z: 2.35,
      facing: Math.PI,
      accent: accentColor(),
      scale: 1.02,
    })
    addPerson(g, {
      x: -2.7,
      z: -1.25,
      facing: Math.PI,
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
    }
    return b
  }

  function buildCampus() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)

    roundBox(g, 27, 0.32, 19, 0.4, p.lawn, 0, -0.16, 0, { receive: true })
    plane(g, 26.2, 18.2, p.lawn, 0, 0.01, 0, { opacity: 0, cast: false })
    // 草地上的浅色铺装与环路
    plane(g, 26.4, 1.7, p.path, 0, 0.025, 0.5, { cast: false })
    plane(g, 1.7, 18.2, p.path, 0, 0.025, 1.0, { cast: false })
    cylinder(g, 2.7, 2.7, 0.05, 30, p.path, 0, 0.03, 0.6)
    cylinder(g, 1.35, 1.35, 0.06, 24, p.glass, 0, 0.04, 0.6, {
      glow: true,
      opacity: 0.5,
    })
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
    addPerson(site, {
      x: -0.7,
      z: 1.0,
      facing: 0.5,
      accent: '#e0a63a',
      helmet: true,
      scale: 0.94,
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
    for (const [x, z] of [[-3.4, 1.5], [3.4, 1.5], [-3.6, 4.2], [3.8, 4.2]]) {
      cylinder(g, 0.045, 0.045, 2.3, 6, p.darkMetal, x, 1.15, z)
      const lamp = sphere(g, 0.1, '#ffe2b8', x, 2.35, z, { glow: true, detail: 8 })
      lamp.userData.lamp = true
    }
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
    buildRoom(g, { width: 11, depth: 8.6, height: 3.3, windowWidth: 3.4, windowX: 0.8, windowY: 1.9 })

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
    }

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

    roundBox(g, 15, 0.3, 10.5, 0.35, p.dirt, 0, -0.15, 0, { receive: true })
    plane(g, 14.2, 9.8, p.dirt, 0, 0.02, 0.1, { cast: false })

    // 围挡
    const fence = [[-7.0, 0, 0, 9.8], [7.0, 0, 0, 9.8], [0, -5.0, 14.2, 0], [0, 5.0, 14.2, 0]]
    fence.forEach(([x, z, w, d]) => {
      if (w > 0) {
        box(g, w, 0.05, 0.06, p.metal, x, 1.15, z)
        box(g, w, 0.05, 0.06, p.metal, x, 0.6, z)
        for (let i = -w / 2; i <= w / 2; i += 1.6) box(g, 0.08, 1.2, 0.08, p.darkMetal, x + i, 0.6, z)
      } else {
        box(g, 0.06, 0.05, d, p.metal, x, 1.15, z)
        box(g, 0.06, 0.05, d, p.metal, x, 0.6, z)
        for (let i = -d / 2; i <= d / 2; i += 1.6) box(g, 0.08, 1.2, 0.08, p.darkMetal, x, 0.6, z + i)
      }
    })

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
      const count = 3 + Math.floor(rand() * 5)
      for (let i = 0; i < count; i++) {
        const bx = zone.x - zone.w / 2 + 0.55 + (i % 3) * (zone.w / 3.4)
        const bz = zone.z - zone.d / 2 + 0.6 + Math.floor(i / 3) * 0.82
        const height = 0.3 + rand() * 0.95
        box(g, 0.62, height, 0.62, zone.color, bx, height / 2 + 0.08, bz, {
          pick: 'site',
          roughness: 0.7,
        })
      }
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

    // 材料、路锥、工人
    for (let i = 0; i < 5; i++) {
      box(g, 1.1, 0.14, 0.5, p.woodLight, -5.2, 0.2 + (i % 2) * 0.15, 3.4 + i * 0.1)
    }
    for (let i = 0; i < 4; i++) {
      cone(g, 0.16, 0.38, '#f07b3f', -5.4 + i * 0.6, 0.2, 2.0, { segments: 8 })
    }
    addPerson(g, {
      x: -1.9,
      z: 2.8,
      facing: 0.3,
      accent: '#e0a63a',
      helmet: true,
      scale: 1.0,
    })
    addPerson(g, {
      x: 4.2,
      z: 2.9,
      facing: -0.5,
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
    buildRoom(g, { width: 10.5, depth: 8, height: 2.9, windowWidth: 2.2, windowX: 3.1, windowY: 1.8 })

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
    // 椅子
    roundBox(g, 0.55, 0.08, 0.5, 0.06, p.fabric, -0.5, 0.46, -1.55)
    roundBox(g, 0.5, 0.6, 0.08, 0.06, p.fabricLight, -0.5, 0.75, -1.32)
    box(g, 0.06, 0.45, 0.06, p.metal, -0.5, 0.22, -1.55)

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
    // 时钟
    cylinder(g, 0.42, 0.42, 0.06, 24, p.paper, -4.65, 2.1, 1.8, { rotation: [Math.PI / 2, 0, 0] })
    cylinder(g, 0.36, 0.36, 0.02, 24, p.darkMetal, -4.62, 2.1, 1.81, { rotation: [Math.PI / 2, 0, 0] })
    box(g, 0.03, 0.26, 0.02, p.paper, -4.55, 2.2, 1.84)
    box(g, 0.02, 0.2, 0.02, p.paper, -4.55, 2.1, 1.84, { rotation: [0, 0, 1.1] })

    // 咖啡机、折叠床、植物
    roundBox(g, 0.7, 1.0, 0.55, 0.05, p.metal, -4.35, 0.52, 2.9)
    box(g, 0.3, 0.3, 0.1, p.darkMetal, -4.35, 0.6, 2.6)
    const cup = cylinder(g, 0.07, 0.05, 0.12, 8, p.paper, -4.35, 0.2, 2.55)
    roundBox(g, 1.9, 0.16, 0.72, 0.05, p.fabric, 1.9, 0.28, 3.1)
    roundBox(g, 0.6, 0.16, 0.5, 0.05, p.paper, 1.0, 0.4, 2.95, { rotation: [0, 0.3, 0] })
    buildPlant(g, 4.5, 3.4, 1.0)
    buildPlant(g, -4.8, 3.4, 0.9)

    addPerson(g, {
      x: -0.5,
      z: -1.15,
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
        at: { x: -4.65, y: 2.9, z: 1.8 },
      },
    ]
    return frames.duty
  }

  function buildDesk() {
    const p = palette()
    const g = new THREE.Group()
    root.add(g)
    buildRoom(g, { width: 10.5, depth: 8, height: 3.0, windowWidth: 1.8, windowX: -3.4, windowY: 1.8 })

    // 长前台
    roundBox(g, 5.0, 1.0, 0.65, 0.06, p.wood, 0, 0.5, -2.4)
    roundBox(g, 5.2, 0.09, 0.8, 0.04, p.deskTop, 0, 1.03, -2.4)
    box(g, 4.8, 0.62, 0.04, p.woodLight, 0, 0.42, -2.05, { pick: 'desk' })
    box(g, 0.62, 0.38, 0.04, p.darkMetal, 0.7, 1.28, -2.47)
    box(g, 0.56, 0.32, 0.01, p.glass, 0.7, 1.28, -2.44, { glow: true })
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
      x: 0,
      z: -3.1,
      facing: 0,
      accent: accentColor(),
      scale: 1.0,
    })
    addPerson(g, {
      x: 4.3,
      z: 2.8,
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
    campus: buildCampus,
    library: buildLibrary,
    site: buildSite,
    duty: buildDuty,
    desk: buildDesk,
  }

  function buildPlace() {
    clearScene()
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
          const bob = Math.sin(t * 1.8 + phase) * 0.018
          const group = data.body.parent
          group.position.y = baseY + bob
          data.body.rotation.z = Math.sin(t * 1.1 + phase) * 0.015
          data.armL.rotation.z = 0.24 + Math.sin(t * 1.9 + phase) * 0.06
          data.armR.rotation.z = -0.24 + Math.cos(t * 2.1 + phase) * 0.06
          data.head.rotation.y = Math.sin(t * 0.6 + phase) * 0.16
        } else if (item.kind === 'beacon') {
          item.mesh.visible = Math.sin(t * 2.4) > -0.25
        }
      })
      pulseMats.forEach((material, index) => {
        material.emissiveIntensity = 0.55 + Math.sin(t * 1.3 + index * 0.7) * 0.18
      })
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
      if (next.accent) state.accent = next.accent
      if (typeof next.dark === 'boolean') state.dark = next.dark
      if (PLACES.includes(next.place)) state.place = next.place
      applyTheme()
      const signature = JSON.stringify(state.stats)
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

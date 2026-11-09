#!/usr/bin/env node
'use strict'

/**
 * IRIS 内置动作服务：零依赖 HTTP，返回 VRM 人形骨骼的四元数轨道。
 * 协议固定为 {name,fps,duration,loop,tracks:[{bone,times,quaternions}]}，
 * 后续可用 Kimodo / HAST-SEC 的包裹服务替换实现，App 侧无需改动。
 */

const http = require('http')

const FPS = 30
const BONES = [
  'Hips', 'Spine', 'Chest', 'UpperChest', 'Neck', 'Head',
  'LeftShoulder', 'LeftUpperArm', 'LeftLowerArm', 'LeftHand',
  'RightShoulder', 'RightUpperArm', 'RightLowerArm', 'RightHand',
  'LeftUpperLeg', 'LeftLowerLeg', 'LeftFoot',
  'RightUpperLeg', 'RightLowerLeg', 'RightFoot',
]

function quat(x, y, z) {
  const cx = Math.cos(x / 2), sx = Math.sin(x / 2)
  const cy = Math.cos(y / 2), sy = Math.sin(y / 2)
  const cz = Math.cos(z / 2), sz = Math.sin(z / 2)
  return [
    sx * cy * cz + cx * sy * sz,
    cx * sy * cz - sx * cy * sz,
    cx * cy * sz + sx * sy * cz,
    cx * cy * cz - sx * sy * sz,
  ]
}

function sample(name, t, duration) {
  const w = (2 * Math.PI * t) / duration
  const neutral = quat(0, 0, 0)
  const poses = {
    Hips: quat(Math.sin(w) * 0.02, Math.sin(w * 0.5) * 0.05, Math.sin(w) * 0.03),
    Spine: quat(Math.sin(w) * 0.02, 0, 0),
    Chest: quat(Math.sin(w + 0.6) * 0.02, 0, 0),
    Neck: quat(Math.sin(w + 1.2) * 0.02, Math.sin(w * 0.5) * 0.04, 0),
    Head: quat(Math.sin(w + 1.6) * 0.02, Math.sin(w * 0.5) * 0.05, Math.sin(w) * 0.02),
  }

  if (name === 'wave') {
    poses.RightShoulder = quat(0, 0, -0.15)
    poses.RightUpperArm = quat(0, 0, -1.35)
    poses.RightLowerArm = quat(0, 0, -0.55 + Math.sin(w * 3) * 0.35)
    poses.RightHand = quat(0, 0, Math.sin(w * 3) * 0.3)
    poses.Head = quat(0, 0.08, Math.sin(w * 2) * 0.05)
  } else if (name === 'nod') {
    poses.Head = quat(0.28 + Math.sin(w * 2) * 0.18, 0, 0)
    poses.Neck = quat(0.12 + Math.sin(w * 2) * 0.08, 0, 0)
  } else if (name === 'bow') {
    poses.Spine = quat(0.45 + Math.sin(w) * 0.05, 0, 0)
    poses.Chest = quat(0.25, 0, 0)
    poses.Neck = quat(0.2, 0, 0)
    poses.Hips = quat(0.12, 0, 0)
  } else if (name === 'dance') {
    poses.Hips = quat(0, Math.sin(w) * 0.35, Math.sin(w * 2) * 0.12)
    poses.Spine = quat(0, 0, Math.sin(w) * 0.12)
    poses.Chest = quat(0, 0, Math.sin(w + 1) * 0.15)
    poses.LeftUpperArm = quat(0, 0, 0.7 + Math.sin(w * 2) * 0.35)
    poses.RightUpperArm = quat(0, 0, -0.7 - Math.sin(w * 2 + 1) * 0.35)
    poses.LeftLowerArm = quat(0, 0, 0.4 + Math.sin(w * 2 + 0.5) * 0.3)
    poses.RightLowerArm = quat(0, 0, -0.4 - Math.sin(w * 2 + 1.5) * 0.3)
    poses.LeftUpperLeg = quat(Math.sin(w * 2) * 0.15, 0, 0)
    poses.RightUpperLeg = quat(-Math.sin(w * 2) * 0.15, 0, 0)
  }

  for (const bone of BONES) {
    if (!poses[bone]) poses[bone] = neutral
  }
  return poses
}

function buildMotion(name) {
  const loop = name === 'idle' || name === 'dance'
  const duration = name === 'idle' ? 4 : 2.5
  const frames = Math.max(2, Math.round(duration * FPS))
  const times = []
  for (let i = 0; i <= frames; i++) times.push((i / FPS).toFixed(3))
  const tracks = BONES.map((bone) => {
    const values = []
    for (let i = 0; i <= frames; i++) {
      const t = (i / FPS) * (name === 'idle' ? 1 : 1)
      const pose = sample(name, t, duration)
      values.push(...pose[bone])
    }
    return { bone, times, quaternions: values }
  })
  return { name, fps: FPS, duration, loop, tracks }
}

const ALLOWED = new Set(['idle', 'wave', 'nod', 'bow', 'dance'])
const cache = new Map()

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', 'http://127.0.0.1')
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('Access-Control-Allow-Origin', '*')

  if (url.pathname === '/health') {
    res.end(JSON.stringify({ ok: true, engine: 'iris-motion', fps: FPS, motions: [...ALLOWED] }))
    return
  }
  if (url.pathname === '/motion') {
    const name = (url.searchParams.get('name') || 'idle').toLowerCase()
    if (!ALLOWED.has(name)) {
      res.statusCode = 404
      res.end(JSON.stringify({ error: 'unknown motion', name }))
      return
    }
    if (!cache.has(name)) cache.set(name, JSON.stringify(buildMotion(name)))
    res.end(cache.get(name))
    return
  }
  res.statusCode = 404
  res.end(JSON.stringify({ error: 'not found' }))
})

const port = Number(process.env.PORT || 8780)
server.listen(port, '127.0.0.1', () => {
  console.log(`iris-motion listening on 127.0.0.1:${port}`)
})

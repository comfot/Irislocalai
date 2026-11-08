import struct, math
from pathlib import Path

def sjis(name, size):
    raw = name.encode('shift_jis')
    if len(raw) > size:
        raise ValueError(name)
    return raw + b'\x00' * (size - len(raw))

def quat_z(angle):
    return (0.0, 0.0, math.sin(angle / 2), math.cos(angle / 2))

def quat_y(angle):
    return (0.0, math.sin(angle / 2), 0.0, math.cos(angle / 2))

def quat_x(angle):
    return (math.sin(angle / 2), 0.0, 0.0, math.cos(angle / 2))

def mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    )

frames = []
# Lower the arms from T-pose; subtle breathing / head sway on top.
arms = {
    '左腕': quat_z(-0.92),
    '右腕': quat_z(0.92),
    '左ひじ': quat_z(-0.12),
    '右ひじ': quat_z(0.12),
}
for frame, breath, head in ((0, 0.00, 0.00), (60, 0.035, 0.055), (120, 0.00, 0.00)):
    for bone, rotation in arms.items():
        frames.append((bone, frame, (0.0, 0.0, 0.0), rotation))
    frames.append(('上半身', frame, (0.0, 0.0, 0.0), quat_x(breath)))
    frames.append(('首', frame, (0.0, 0.0, 0.0), quat_y(head * 0.45)))
    frames.append(('頭', frame, (0.0, 0.0, 0.0), quat_y(head)))

interp = bytes([20, 20, 20, 20] * 16)
out = bytearray(sjis('Vocaloid Motion Data 0002', 30))
out += sjis('IRIS_IDLE', 20)
out += struct.pack('<I', len(frames))
for bone, frame, pos, rot in frames:
    out += sjis(bone, 15)
    out += struct.pack('<I3f4f', frame, *pos, *rot)
    out += interp
out += struct.pack('<I', 0)  # morph
out += struct.pack('<I', 0)  # camera
out += struct.pack('<I', 0)  # light
out += struct.pack('<I', 0)  # self shadow
out += struct.pack('<I', 0)  # model visibility
Path('app/src/main/assets/model/mmd-idle.vmd').write_bytes(out)
print(len(out), len(frames))

package com.menthaar.sdk.internal;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * 手写的等价 AIDL 接口（原 ISlamService.aidl）。当前构建环境没有 arm64 aidl 工具，
 * 因此保留线协议事务号与 AIDL 生成代码一致，SDK 与引擎进程可直接互通。
 */
public interface ISlamService extends IInterface {

    void initSLAM(String resDir) throws RemoteException;

    boolean attachFrameBuffer(ParcelFileDescriptor pfd, int size) throws RemoteException;

    void processFrame(int seq, int bufIndex, int width, int height) throws RemoteException;

    void detectPlane() throws RemoteException;

    void setPointCloudDisplay(boolean enable) throws RemoteException;

    boolean isPointCloudDisplayEnabled() throws RemoteException;

    void saveMap(String mapPath) throws RemoteException;

    void loadMapWithId(String mapPath, int mapId, boolean append) throws RemoteException;

    int[] getMapStats() throws RemoteException;

    void updateArObjectScale(float scaleFactor) throws RemoteException;

    void set3DofAssistEnabled(boolean enable) throws RemoteException;

    void setSensorReverse(boolean reversed) throws RemoteException;

    void setCameraImageRotation(int degrees) throws RemoteException;

    int getSensorDof() throws RemoteException;

    abstract class Stub extends Binder implements ISlamService {
        private static final String DESCRIPTOR = "com.menthaar.sdk.internal.ISlamService";

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ISlamService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ISlamService) return (ISlamService) local;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1:
                    data.enforceInterface(DESCRIPTOR);
                    initSLAM(data.readString());
                    return true;
                case 2: {
                    data.enforceInterface(DESCRIPTOR);
                    ParcelFileDescriptor pfd = data.readInt() != 0
                            ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    int size = data.readInt();
                    boolean result = attachFrameBuffer(pfd, size);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result ? 1 : 0);
                    }
                    return true;
                }
                case 3:
                    data.enforceInterface(DESCRIPTOR);
                    processFrame(data.readInt(), data.readInt(), data.readInt(), data.readInt());
                    return true;
                case 4:
                    data.enforceInterface(DESCRIPTOR);
                    detectPlane();
                    return true;
                case 5: {
                    data.enforceInterface(DESCRIPTOR);
                    setPointCloudDisplay(data.readInt() != 0);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 6: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean result = isPointCloudDisplayEnabled();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result ? 1 : 0);
                    }
                    return true;
                }
                case 7: {
                    data.enforceInterface(DESCRIPTOR);
                    saveMap(data.readString());
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 8: {
                    data.enforceInterface(DESCRIPTOR);
                    loadMapWithId(data.readString(), data.readInt(), data.readInt() != 0);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 9: {
                    data.enforceInterface(DESCRIPTOR);
                    int[] result = getMapStats();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeIntArray(result);
                    }
                    return true;
                }
                case 10: {
                    data.enforceInterface(DESCRIPTOR);
                    updateArObjectScale(data.readFloat());
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 11: {
                    data.enforceInterface(DESCRIPTOR);
                    set3DofAssistEnabled(data.readInt() != 0);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 12: {
                    data.enforceInterface(DESCRIPTOR);
                    setSensorReverse(data.readInt() != 0);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 13:
                    data.enforceInterface(DESCRIPTOR);
                    setCameraImageRotation(data.readInt());
                    return true;
                case 14: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = getSensorDof();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result);
                    }
                    return true;
                }
                case IBinder.INTERFACE_TRANSACTION:
                    if (reply != null) reply.writeString(DESCRIPTOR);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements ISlamService {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            private Parcel obtain() {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(DESCRIPTOR);
                return data;
            }

            @Override
            public void initSLAM(String resDir) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeString(resDir);
                try {
                    remote.transact(1, data, reply, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean attachFrameBuffer(ParcelFileDescriptor pfd, int size) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(pfd != null ? 1 : 0);
                if (pfd != null) pfd.writeToParcel(data, 0);
                data.writeInt(size);
                try {
                    remote.transact(2, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void processFrame(int seq, int bufIndex, int width, int height) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(seq);
                data.writeInt(bufIndex);
                data.writeInt(width);
                data.writeInt(height);
                try {
                    remote.transact(3, data, reply, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void detectPlane() throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(4, data, reply, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void setPointCloudDisplay(boolean enable) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(enable ? 1 : 0);
                try {
                    remote.transact(5, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean isPointCloudDisplayEnabled() throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(6, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void saveMap(String mapPath) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeString(mapPath);
                try {
                    remote.transact(7, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void loadMapWithId(String mapPath, int mapId, boolean append) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeString(mapPath);
                data.writeInt(mapId);
                data.writeInt(append ? 1 : 0);
                try {
                    remote.transact(8, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public int[] getMapStats() throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(9, data, reply, 0);
                    reply.readException();
                    return reply.createIntArray();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void updateArObjectScale(float scaleFactor) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeFloat(scaleFactor);
                try {
                    remote.transact(10, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void set3DofAssistEnabled(boolean enable) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(enable ? 1 : 0);
                try {
                    remote.transact(11, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void setSensorReverse(boolean reversed) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(reversed ? 1 : 0);
                try {
                    remote.transact(12, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void setCameraImageRotation(int degrees) throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                data.writeInt(degrees);
                try {
                    remote.transact(13, data, reply, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public int getSensorDof() throws RemoteException {
                Parcel data = obtain();
                Parcel reply = Parcel.obtain();
                try {
                    remote.transact(14, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}

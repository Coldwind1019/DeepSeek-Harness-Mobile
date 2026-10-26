package moe.shizuku.server;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

public interface IShizukuService extends IInterface {
    IRemoteProcess newProcess(String[] cmd, String[] env, String dir) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IShizukuService {
        private static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
        private static final int TRANSACTION_newProcess = 8;

        public static IShizukuService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IShizukuService) return (IShizukuService) iin;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements IShizukuService {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) throws android.os.RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStringArray(cmd);
                    data.writeStringArray(env);
                    data.writeString(dir);
                    remote.transact(TRANSACTION_newProcess, data, reply, 0);
                    reply.readException();
                    IBinder binder = reply.readStrongBinder();
                    return IRemoteProcess.Stub.asInterface(binder);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
